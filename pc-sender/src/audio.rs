// pc-sender/src/audio.rs
// cpal loopback capture → Opus encode → UDP send
// Works on Windows (WASAPI), Linux (ALSA/PipeWire), macOS (CoreAudio)

use std::sync::{Arc, atomic::{AtomicBool, Ordering}};
use anyhow::{Context, Result};
use crossbeam_channel::{bounded, Sender, Receiver};
use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use cpal::{SampleFormat, StreamConfig};

use crate::app::Stats;
use crate::encoder::OpusEncoderWrapper;
use crate::sender::UdpSender;

const FRAME_SAMPLES: usize = 960; // 20ms @ 48kHz mono

/// Holds the cpal stream + worker thread.
/// Dropping this stops everything cleanly.
pub struct AudioSession {
    // Keep stream alive (cpal drops it on stop when this is dropped)
    _stream: cpal::Stream,
    // Worker thread handle
    _worker: std::thread::JoinHandle<()>,
}

impl AudioSession {
    pub fn start(
        target_ip:  String,
        target_port: u16,
        stop_flag:  Arc<AtomicBool>,
        stats:      Arc<Stats>,
    ) -> Result<Self> {
        let host = cpal::default_host();

        // On Windows this is the WASAPI loopback device.
        // On Linux/Mac it's the default input — user selects loopback in OS settings.
        let device = host
            .default_output_device()   // loopback: capture what's playing
            .context("No output device found")?;

        log::info!("Capture device: {}", device.name().unwrap_or_default());

        // Request 48kHz stereo (WASAPI default; cpal will negotiate)
        let config = find_config(&device)?;
        log::info!("Stream config: {:?}", config);

        let channels    = config.channels as usize;
        let sample_rate = config.sample_rate.0;

        // Channel: raw PCM floats from cpal callback → worker thread
        let (pcm_tx, pcm_rx): (Sender<Vec<f32>>, Receiver<Vec<f32>>) = bounded(64);

        // Build cpal input stream (loopback on Windows = output device + StreamConfig)
        let stream = build_stream(&device, &config, pcm_tx)?;
        stream.play().context("Failed to start audio stream")?;

        // Worker thread: encode + send
        let worker = std::thread::Builder::new()
            .name("audioshare-worker".into())
            .spawn(move || {
                worker_loop(
                    pcm_rx, target_ip, target_port,
                    channels, sample_rate,
                    stop_flag, stats,
                );
            })?;

        Ok(Self { _stream: stream, _worker: worker })
    }
}

/// Find a usable 48kHz config (mono or stereo, f32 preferred)
fn find_config(device: &cpal::Device) -> Result<StreamConfig> {
    // Try exact 48kHz f32 first
    for fmt in device.supported_output_configs()
        .context("Failed to query device configs")?
    {
        if fmt.sample_format() == SampleFormat::F32
            && fmt.min_sample_rate().0 <= 48000
            && fmt.max_sample_rate().0 >= 48000
        {
            return Ok(fmt.with_sample_rate(cpal::SampleRate(48000)).into());
        }
    }
    // Fallback: whatever the device gives us (we'll resample in software if needed)
    Ok(device.default_output_config()?.into())
}

/// Build cpal stream — callback pushes raw PCM into channel
fn build_stream(
    device: &cpal::Device,
    config: &StreamConfig,
    tx: Sender<Vec<f32>>,
) -> Result<cpal::Stream> {
    let err_fn = |e| log::error!("cpal stream error: {e}");

    let stream = device.build_input_stream(
        config,
        move |data: &[f32], _info: &cpal::InputCallbackInfo| {
            // data is interleaved float samples
            // non-blocking: drop if worker can't keep up
            let _ = tx.try_send(data.to_vec());
        },
        err_fn,
        None,
    ).context("build_input_stream failed")?;

    Ok(stream)
}

/// Worker: accumulate PCM → encode 20ms frames → send UDP
fn worker_loop(
    rx:          Receiver<Vec<f32>>,
    target_ip:   String,
    target_port: u16,
    channels:    usize,
    _sample_rate: u32,
    stop_flag:   Arc<AtomicBool>,
    stats:       Arc<Stats>,
) {
    let mut encoder = match OpusEncoderWrapper::new(channels) {
        Ok(e) => e,
        Err(e) => { log::error!("Opus init failed: {e}"); return; }
    };

    let udp = match UdpSender::new(&target_ip, target_port) {
        Ok(u) => u,
        Err(e) => { log::error!("UDP init failed: {e}"); return; }
    };

    // Accumulate mono samples (mix down stereo if needed)
    let mut mono_buf: Vec<f32> = Vec::with_capacity(FRAME_SAMPLES * 4);
    let mut seq: u32 = 0;

    log::info!("Worker started → {target_ip}:{target_port}");

    loop {
        if stop_flag.load(Ordering::Relaxed) { break; }

        let chunk = match rx.recv_timeout(std::time::Duration::from_millis(50)) {
            Ok(c) => c,
            Err(_) => continue,
        };

        // Mix stereo → mono
        if channels == 2 {
            for frame in chunk.chunks_exact(2) {
                mono_buf.push((frame[0] + frame[1]) * 0.5);
            }
        } else {
            mono_buf.extend_from_slice(&chunk);
        }

        // Emit complete 20ms frames
        while mono_buf.len() >= FRAME_SAMPLES {
            let frame: Vec<f32> = mono_buf.drain(..FRAME_SAMPLES).collect();

            match encoder.encode(&frame) {
                Ok(opus_bytes) => {
                    let ts = timestamp_ms();
                    udp.send(seq, ts, &opus_bytes);

                    stats.packets_sent.fetch_add(1, Ordering::Relaxed);
                    stats.last_opus_bytes.store(opus_bytes.len() as u32, Ordering::Relaxed);
                    seq = seq.wrapping_add(1);
                }
                Err(e) => log::warn!("Encode error: {e}"),
            }
        }

        // Don't let mono_buf grow unbounded (e.g. if encoding is slow)
        if mono_buf.len() > FRAME_SAMPLES * 10 {
            log::warn!("mono_buf overflow, dropping {} samples", mono_buf.len());
            mono_buf.clear();
        }
    }

    log::info!("Worker stopped. Sent {seq} packets.");
}

fn timestamp_ms() -> u32 {
    use std::time::{SystemTime, UNIX_EPOCH};
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u32
}
