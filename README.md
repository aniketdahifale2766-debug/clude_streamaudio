# WiFi Audio Stream + USB-C Dual Audio

The original WiFi mode streams system audio over a local network. A new **USB-C Dual Audio** section has been added to the same APK.

## USB-C Dual Audio

The USB mode is designed for two Android phones connected by USB-C using Android USB tethering/RNDIS as the transport.

### Architecture
- `UsbDualAudioActivity.kt` — Host/Client UI and pairing.
- `UsbDualAudioService.kt` — Host capture, Client playback, TCP transport, clock synchronization and timestamped PCM frames.
- `UsbAudioProtocol.kt` — framed TCP protocol with `SESSION_START`, `SYNC_REQUEST`, `SYNC_RESPONSE`, `AUDIO_FRAME`, `HEARTBEAT`, and `SESSION_STOP`.
- `UsbNetworkUtil.kt` — detects likely USB/RNDIS interfaces and the USB-side default gateway.

### Audio format
- 48,000 Hz
- Stereo
- 16-bit PCM
- 20 ms frames
- 3,840 bytes per full audio frame
- TCP with `TCP_NODELAY`
- 150 ms timestamp look-ahead for synchronized presentation

### Phone setup
1. Connect the two phones with a USB-C data cable.
2. On the Host phone, enable **USB tethering** if Android requires it.
3. Open this APK and select **USB-C Dual Audio**.
4. On Phone A select **HOST — Share Audio** and approve system-audio capture.
5. On Phone B select **CLIENT — Play Synced Audio**. The app attempts to detect the USB gateway automatically; if needed, enter the Host USB IP shown on Phone A.
6. Both phones should enter synchronized playback after the client fills its startup buffer.

### Important limitation
This is a synchronized playback implementation, not literal zero-latency audio. Android audio hardware, MediaProjection capture, USB tethering and `AudioTrack` each add processing/buffering. The timestamp/clock-sync design targets simultaneous presentation of the two outputs rather than eliminating absolute source-to-speaker latency. USB tethering/RNDIS support also varies by Android OEM and device.

## Existing WiFi mode
- `AudioStreamService.kt` captures system audio via MediaProjection + AudioPlaybackCaptureConfiguration and serves raw PCM over WebSocket.
- `assets/index.html` is the browser AudioWorklet player with a jitter buffer.
- `MainActivity.kt` controls the original WiFi mode and now also opens the USB-C Dual Audio section.

## APK build
GitHub Actions (`.github/workflows/build.yml`) builds a debug APK on pushes to `main` and uploads `app-debug-apk` as an artifact.
