# WiFi Audio Stream

Streams the device's system audio over local WiFi. Anyone on the same network opens
`http://<phone-ip>:8080` in a browser and listens live, with a per-client latency slider.

## How it works
- `AudioStreamService.kt` — captures system audio via MediaProjection + AudioPlaybackCaptureConfiguration,
  runs an embedded Ktor server, and pushes raw PCM frames to connected clients over a WebSocket (`/stream`).
- `assets/index.html` — the web player. Uses an AudioWorklet with a jitter buffer; the slider
  controls buffer size in real time.
- `MainActivity.kt` — requests screen/audio capture permission and starts/stops the service, shows the join link.

## Push to your new repo

```bash
cd wifi-audio-stream
git init
git add .
git commit -m "Initial scaffold: system audio capture + local WebSocket streaming"
git branch -M main
git remote add origin https://github.com/<your-username>/<your-repo>.git
git push -u origin main
```

Once pushed, GitHub Actions (`.github/workflows/build.yml`) builds automatically and produces
a **debug-signed APK** as a downloadable artifact on the Actions run (Actions tab → latest run →
Artifacts → `app-debug-apk`).

## Notes / next steps
- This is a first-pass scaffold — build order per the earlier plan: (1) get capture → server → client
  working end-to-end on one phone, (2) test from a second device, (3) tune the jitter buffer,
  (4) add QR code + connection count to the UI, (5) add reconnect logic and wake-lock handling.
- Debug APK is unsigned for release purposes — fine for sideloading/testing. Say the word when you
  want a release keystore wired into the pipeline for a signed release APK.
