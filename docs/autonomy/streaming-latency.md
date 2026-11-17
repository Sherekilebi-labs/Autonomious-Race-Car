# Streaming and manual input latency — 0.5.1

Install the private local `dist/task1-private/maxbot-0.5.1-30fps.apk` (version code 9) over the current app, reconnect the phone, and reload the Mac console. The ESP32 firmware is unchanged. The confirmed working recovery point remains [manual-control-v0.4.0-working](checkpoints/manual-control-v0.4.0-working.md).

## Data paths

- Manual input: browser → authenticated, unordered WebRTC data channel → Pixel → existing signed UDP → ESP32. The channel uses zero retransmissions so an obsolete input cannot hold up a newer one. Session-bound HMAC challenge authentication runs before any input is accepted.
- Fallback: the existing browser → Node → Pixel WebSocket route takes over when the direct channel is unavailable, congested, stale, or stops acknowledging inputs. Neutral/release is sent on both routes. A shared monotonic event sequence rejects duplicates and late fallback messages.
- Arming, stop, emergency stop, configuration, operator presence and signalling remain on the reliable connection. Manual freshness (300 ms), ESP watchdog (500 ms), focus-loss stop, steering limits and reversal pause remain. Direct-channel clock probes use the Mac's clock and the same conservative deadline mapping.
- Fresh manual input is submitted to the existing latest-output sender immediately, removing the wait for the next 20 ms supervisor tick. Output timestamps prevent an older periodic result from replacing it.
- Video: ARCore image planes → one native pack into a pooled direct I420 slot → latest pending frame → hardware encoder → WebRTC → browser. Video no longer takes the Java heap/inference/recording copy route. Storage is reference-counted until the encoder releases it; the queue holds one in-flight frame and one replaceable pending frame.
- Stream settings target **640 × 480, 30 fps, 3 Mb/s maximum**, with the same hardware encoder selection. Direct and original pixel packing are byte-for-byte equivalent. WebRTC owns frame-rate adaptation; duplicate application frame limiters have been removed from video.
- The 0.5.1 profile doubles the 0.5.0 frame-rate and bitrate caps, keeping the same resolution and bitrate allowance per frame. The encoder prefers maintaining resolution; constrained bandwidth/CPU may still lower actual frame rate or encoding quality. Actual received FPS is shown below the camera.
- Camera intrinsics reporting avoids repeatedly hashing unchanged metadata. Optional recording/inference still receive their original CPU images when needed. Telemetry is skipped when its WebSocket already has more than 16 KiB queued, avoiding a backlog of old state.

The browser requests zero extra receiver buffering where supported. This is a hint, not a promise of zero network or decoding delay; see the [WebRTC receiver specification](https://www.w3.org/TR/webrtc/#dom-rtcrtpreceiver-jitterbuffertarget).

## Measurements

The line below the camera reports actual received dimensions/frame rate/bitrate, average jitter-buffer residence, decode time, active input route and return-trip time. **Save control report** includes those browser metrics, direct/fallback counts, and phone encoder/staging counters. Jitter-buffer and decode values are interval deltas of WebRTC counters, not averages over the whole run. These values do not measure screen-to-motor latency or exposure time.

Verification on the development Mac (not the Pixel/car):

| Check | Result |
| --- | --- |
| JNI staging, 800 samples at 640 × 480, strided/interleaved chroma | Old p50/p95 0.087/0.132 ms; direct 0.048/0.071 ms |
| Byte-equivalence and bounds | Exact I420 equality for padded/strided planes; undersized buffers rejected |
| Chrome loopback with synthetic video | 640 × 480 at 29 received fps in the sampled interval (30 fps target); concurrent direct inputs, fallback/recovery, no reported freezes or frame drops |
| Direct input and fallback | Direct delivery, deliberate reply loss → WebSocket fallback → direct recovery; release delivered reliably |
| Replay/lifecycle | Wrong key/session/nonce, stale duplicate event, malformed input and operator revocation covered |

Loopback timings and Mac JNI timings must not be presented as measured on-car latency. Install this version and save a control report during a representative session to measure the real radio/encoder/decoder path.

Reproduce software checks from the repository root:

```sh
npm --prefix controller/node-js/maxbot test
sh tools/maxbot/test-core.sh
sh tools/maxbot/test-image.sh
node tools/maxbot/test-browser-latency.cjs
```

The browser test requires Chrome (override its path with `MAXBOT_CHROME`). It creates an isolated localhost server, a synthetic robot/key and loopback WebRTC peers. It never contacts or arms the real vehicle. Android unit tests, debug APK build and lint also pass using the local Android SDK/Gradle toolchain.
