// pc-sender/src/encoder.rs
// Thin wrapper around audiopus (safe Rust Opus bindings)

use anyhow::{Context, Result};
use audiopus::{
    coder::Encoder,
    Application, Channels, SampleRate,
    TryFrom,
};

pub struct OpusEncoderWrapper {
    enc:      Encoder,
    out_buf:  Vec<u8>,
}

impl OpusEncoderWrapper {
    pub fn new(channels: usize) -> Result<Self> {
        let ch = match channels {
            1 => Channels::Mono,
            _ => Channels::Stereo,   // we always mix down to mono before encoding
        };

        let mut enc = Encoder::new(
            SampleRate::Hz48000,
            Channels::Mono,          // always mono
            Application::Audio,
        ).context("Opus encoder create")?;

        // Defaults matching our protocol
        enc.set_bitrate(audiopus::Bitrate::BitsPerSecond(64_000))
            .context("set_bitrate")?;
        enc.set_inband_fec(true).context("set_inband_fec")?;
        enc.set_packet_loss_perc(5).context("set_packet_loss_perc")?;

        let _ = ch; // channels arg kept for future stereo support

        Ok(Self {
            enc,
            out_buf: vec![0u8; 400],
        })
    }

    /// Encode one 20ms mono f32 frame (960 samples).
    /// Returns slice of encoded bytes.
    pub fn encode(&mut self, pcm: &[f32]) -> Result<&[u8]> {
        let n = self.enc
            .encode_float(pcm, &mut self.out_buf)
            .context("opus encode")?;
        Ok(&self.out_buf[..n])
    }

    pub fn set_bitrate(&mut self, bps: i32) {
        let _ = self.enc.set_bitrate(audiopus::Bitrate::BitsPerSecond(bps));
    }

    pub fn set_fec_plp(&mut self, plp: i32) {
        let _ = self.enc.set_packet_loss_perc(plp);
    }
}
