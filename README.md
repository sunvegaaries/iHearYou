# iHearYou

Stream audio from PC to Android phone over local Wi-Fi.
Phone acts as a wireless speaker — low latency, battery-efficient.

## Architecture

```
PC (cpal loopback) → Opus encode → UDP → Android (JitterBuffer → Opus decode → AAudio)
```

Control plane: adaptive FEC + bitrate based on network conditions + thermal state.

## Structure

```
ihearyou/
├── pc-sender/          # Rust: cpal + audiopus + egui + UDP
├── android-receiver/   # Kotlin + NDK: AAudio + Opus JNI
└── docs/protocol.md
```

## PC Sender (Rust + egui)

### Requirements
- Rust (stable, via rustup)
- Windows: WASAPI (built-in, no extra deps)
- Linux: `sudo apt install libasound2-dev` (ALSA) or PipeWire
- macOS: CoreAudio (built-in)

### Build & Run
```bash
cd pc-sender
cargo run --release
```

GUI window opens → enter Android IP → Connect.

### How loopback works
- **Windows**: cpal uses WASAPI loopback — captures whatever plays on speakers
- **Linux/macOS**: select a loopback device in OS audio settings (e.g. PulseAudio loopback sink)

## Android Receiver (Kotlin + NDK)

### Requirements
- Android Studio with NDK installed
- libopus prebuilt for Android ARM64:
  Place in `android-receiver/app/src/main/cpp/opus/`
  (include/ + lib/arm64-v8a/libopus.a)
  Get from: https://github.com/Reboog711/opus-android/releases

### Build
Open `android-receiver/` in Android Studio → Run

## Protocol
See [docs/protocol.md](docs/protocol.md)

## MVP Checklist
- [x] PC: cpal capture (WASAPI/ALSA/CoreAudio)
- [x] PC: Opus encode (audiopus)
- [x] PC: UDP send
- [x] PC: egui window (IP input, stats, connect/disconnect)
- [x] Android: UDP receive
- [x] Android: JitterBuffer (zero-alloc ring)
- [x] Android: PCM ring buffer
- [x] Android: Opus decode (JNI)
- [x] Android: AAudio output (callback-driven)
- [x] Android: ForegroundService + WakeLock
- [x] Android: AdaptiveReflex (buffer control)
- [x] Android: CodecPolicy (FEC control, slow loop)
