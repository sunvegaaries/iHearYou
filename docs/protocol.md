# AudioShare v2 — Protocol

## Audio Packet (PC → Android, UDP)

```
 0               1               2               3
 0 1 2 3 4 5 6 7 0 1 2 3 4 5 6 7 0 1 2 3 4 5 6 7 0 1 2 3 4 5 6 7
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                        seq (uint32)                           |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                     timestamp (uint32, ms)                    |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                    opus_data (variable)                       |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

- `seq`: monotonically increasing, wraps at 2^32
- `timestamp`: sender clock in milliseconds
- `opus_data`: raw Opus frame, 20ms, 48kHz, mono

Max packet size: 8 + 400 = 408 bytes (well under MTU)

## Control Packet (Android → PC, UDP)

```
 0               1               2               3
 0 1 2 3 4 5 6 7 0 1 2 3 4 5 6 7 0 1 2 3 4 5 6 7 0 1 2 3 4 5 6 7
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|  type (uint8) |            value (int32, 3 bytes)             |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

### Control Types

| type | meaning        | value                      |
|------|----------------|----------------------------|
| 0x01 | set bitrate    | 0=low(32k), 1=mid(64k), 2=high(96k) |
| 0x02 | set FEC PLP    | 0–40 (percent)             |
| 0x03 | ping           | timestamp echo             |

## Codec Parameters

- Sample rate: 48000 Hz
- Channels: 1 (mono) — stereo not needed for speech/music over Wi-Fi
- Frame size: 20ms = 960 samples
- Application: OPUS_APPLICATION_AUDIO
- FEC: always ON (OPUS_SET_INBAND_FEC 1)
- PLC: always ON on decoder side

## Bitrate Levels

| level | bitrate | use case               |
|-------|---------|------------------------|
| 0     | 32 kbps | high loss / thermal    |
| 1     | 64 kbps | normal                 |
| 2     | 96 kbps | clean network          |
