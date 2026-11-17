# MaxBot manual-control signal propagation experiment

Prepared 2026-11-16. Latest collector: **0.6.1-timing-fix**, Android version code **11**. [First hardware session review](reports/2026-11-16-signal-propagation-review.md) and [collector changes](signal-propagation-v0.6.1.md).

## Purpose and current results

Measure two independent paths while an operator drives manually:

1. Camera image → YOLOv5n decision → collision-warning indicator on the Pixel and Mac.
2. Keyboard/pointer input → phone → ESP32 → completed PWM API write, followed by physical motor/movement onset when independently observed.

**The first hardware results are available in the linked review. Updated capture-clock correlation still needs an on-device confirmation session.** Host tests verify implementation, arithmetic, authentication and export behavior. They do not measure the car. The exported Markdown fills in the software timing results from your session; a missing measurement stays `—`.

| Result | Current evidence |
| --- | --- |
| YOLOv5n baseline bundled in APK | Real official weights, TFLite tensor/finite-output smoke check on the Mac |
| Warning decision and indicator timing | Instrumented; on-device results pending |
| Manual input → completed PWM write | Instrumented across three clocks; hardware session pending |
| Actual traction motor rotation onset | No rotation sensor installed; requires external evidence |
| Steering movement | Raw ESP potentiometer readings and existing onset/settling telemetry retained |
| Chassis movement | Raw phone IMU samples and available calibrated visual state retained; these are movement proxies |
| Export | Continuous Mac journal plus final JSON and generated results Markdown |

## Install and collect

The matching private deployment bundle is `dist/signal-monitor/`. It contains the APK, compiled ESP32 files, an offline Mac dashboard, the guide and a checksum manifest. It contains your existing private connection configuration; share the **session JSON**, not the deployment bundle.

1. Install `maxbot-0.6.1-timing-fix.apk` over the existing MaxBot app. Flash the supplied matching firmware using the existing commissioning procedure; the new `output_written` evidence requires this firmware. Compiling/package creation does not install or flash devices.
2. Start the new dashboard with `start-mac.command`. If another dashboard uses port 8081, stop that instance first. Join the usual ESP Wi-Fi, open MaxBot on the Pixel, pair the Mac dashboard, and wait for fresh telemetry.
3. In **Camera → Signal propagation**, describe the test condition: battery voltage measured externally, floor/load, phone temperature, lighting, obstacle, Wi-Fi arrangement and whether video/collection are enabled.
4. Press **Start monitoring** before the first test input or obstacle appearance. This does not arm or move the car. The existing manual arming and WASD/on-screen controls still determine motion.
5. For vision, move an ordinary COCO object into/out of the central lower image region several times. For command response, perform isolated starts/releases and separate steering trials, with a stationary pause between trials. Preserve the normal commissioning precautions and stop authority.
6. Press **Stop and save**. This ends monitoring; use **Stop / disarm** separately to end driving. The browser downloads one JSON automatically. **Download data JSON** and **Download results .md** remain available for retry.
7. Send the `maxbot-signals-….json` file for the report. Include any external motion evidence if physical onset is part of the result.

Sessions finish automatically after **15 minutes or 32 MiB of journal data**, whichever occurs first. Use separate sessions for different conditions. First five analyzed vision frames are excluded from steady-state vision statistics; they remain in the raw records. Model loading/warm-up happens once at app start, before ordinary inference. Starting monitoring does not reload the model.

### Saved files and recovery

Default location when running the repository dashboard: `runs/signal-propagation/`. In the offline bundle, the same relative directory is inside the bundle. `MAXBOT_SIGNAL_DIRECTORY` overrides it.

| File | Contents |
| --- | --- |
| `maxbot-signals-….json` | Self-contained raw events, model/device/clock/config metadata, calculated samples, statistics and completeness counters |
| `maxbot-signals-….md` | Results table, calculations, missing evidence and optimization experiments |
| `maxbot-signals-….jsonl` | Append-only raw journal; written during monitoring, with an asynchronous filesystem sync requested each second |

Final parsing/statistics/export run in a separate worker thread so a large report does not block dashboard control or heartbeats. The timing export contains detections and timestamps rather than raw images/video. Existing steering-onset summaries are retained as phone-lifetime counters; raw session potentiometer samples remain the basis for session-specific physical steering analysis.

An operator/phone disconnect triggers an incomplete export of received evidence. A server crash may leave only the journal. Recover all complete lines with:

```sh
node tools/maxbot/signal-report.cjs runs/signal-propagation/SESSION.jsonl
```

This produces a recovered JSON and results Markdown marked incomplete. Recompute a final JSON's Markdown with the same command. Download links are session-specific random tokens held by the running server; after restart, use the saved local files. Recording errors appear in the monitoring status. Do not interpret “complete” as proof that every input reached the motor: it means the received trace lanes drained without the tracked recording gaps.

## Baseline model and warning rule

| Setting | Baseline |
| --- | --- |
| Model | Original Ultralytics YOLOv5n v7.0, COCO 80 classes |
| Source commit | `915bbf294bb74c859f0b41f1c23bc395014ea679` |
| Original checkpoint SHA-256 | `4f180cf23ba0717ada0badd6c685026d73d48f184d00fc159c2641284b2ac0a3` |
| Bundled TFLite SHA-256 | `17a6c7117fc0e1bede33533e3df9183ed3b0a23efa561fea5dc70861343134bf` |
| Storage / interface | FP16 weights; FLOAT32 input/output |
| Input / output | `[1,320,320,3]` / `[1,6300,85]` |
| Camera | ARCore rear CPU image, 640 × 480, target 30 fps |
| Runtime | LiteRT 1.4.2 CPU, XNNPACK requested, 2 threads |
| Preprocessing | Native I420 → RGB, normalize to 0–1, 320-square letterbox with 114 padding |
| Score | `objectness × highest class probability ≥ 0.35` |
| Postprocessing | Undo letterbox; class-aware NMS at IoU 0.45; best 128 proposals, maximum 20 retained |
| Warning | Retained box center x in `[0.2,0.8]`, bottom y ≥ 0.65, clipped box area ≥ 0.08 of the camera image |
| Freshness | Warning becomes unavailable/stale after 750 ms without a new decision |

The Pixel and Mac display **Collision warning · object ahead** or **No warning detected**. The rule is a two-dimensional proximity heuristic. It provides no calibrated range, time-to-collision or clear-path guarantee. It has no automatic braking authority. COCO has no dedicated traffic-cone class, so this baseline is unsuitable for claiming cone detection performance. The trained one-class autonomous cone detector remains a separate model and contract. The baseline runs outside autonomous SCOUT/RUN; it does not require mounted VIO geometry to show a manual advisory.

Export/provenance: `tools/maxbot/export_baseline.py`, `requirements-baseline.txt` and the APK's `assets/maxbot/yolov5n-baseline.json`. The exporter verifies the upstream commit and checkpoint hash before loading, validates tensor shape/type and runs a finite-output smoke test. The v7.0 license is bundled. A host smoke test is not an obstacle-detection accuracy evaluation.

## Camera → warning: every contributing stage

```text
Scene changes
  → wait for camera sampling / exposure / rolling-shutter readout / ISP
  → ARCore update and CPU-image availability
  → acquire CPU image; stage hardware-stream video when enabled
  → pack Y/U/V planes into I420
  → latest-frame inference queue
  → resize / letterbox / RGB conversion / normalize
  → YOLOv5n interpreter invocation
  → decode / confidence / NMS / image-zone warning rule
  → Android UI dispatch / layout / first warning-view draw callback
  → display compositor / scanout / visible light

Mac indicator branch after the decision:
  → optional warning publication / WebSocket / Node forwarding
  → browser receipt / DOM update / two animation callbacks
  → browser compositor / display scanout / visible light
```

The live video takes its own hardware encode → radio → WebRTC jitter buffer → decode → presentation path. Browser interval metrics retain FPS, bitrate, jitter-buffer residence, decode time, freezes and input-route RTT. Video playback statistics do not establish camera-to-warning latency. The warning is inferred on the phone before video encoding, so it does not wait for the Mac to decode video.

| Timestamp | Definition |
| --- | --- |
| `sensor_timestamp_ns` | Unmodified `Image.getTimestamp()` |
| `camera_timestamp_ns` | Associated ARCore frame timestamp |
| `image_available_ns` | Application time after ARCore update/preview; existing pipeline's image-available boundary |
| `image_acquired_ns` | Immediately after obtaining the CPU image |
| `pack_start_ns`, `published_ns` | Before I420 packing and just before frame publication |
| `inference_start_ns`, `prepared_ns` | Inference worker starts; input preparation completes |
| `inferred_ns`, `decision_ns` | Interpreter returns; decode/NMS/rule completes |
| `draw_ns` | First `CollisionWarningView.onDraw()` callback for that decision, after drawing |
| `presented_wall_ms`, `presented_perf_ms` | Mac presentation opportunity after two animation callbacks; not actual screen scanout |

All phone application timestamps use Android elapsed realtime. Sensor subtraction is enabled only when the selected camera advertises `SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME`, CPU and explicit Android camera timestamps agree, and timestamp age is nonnegative and under one second. Otherwise sensor/exposure-derived results stay absent; image-available timing remains usable. This is a clock-consistency eligibility check, with no external verification of exposure accuracy. Raw timestamps and camera capabilities are retained so this decision can be audited.

## Manual input → motor: every contributing stage

```text
Physical key / pointer action
  → operating-system input delivery / browser event callback
  → 20 ms manual scheduler / drive and steering ramps
  → manual packet creation with immutable input ID / event ID / deadline
  → authenticated unordered WebRTC data channel, or WebSocket fallback
  → Pixel validation / clock mapping / freshness and reversal gates
  → immediate supervisor output
  → latest-output transmit queue, nominal 2 ms poll
  → JSON construction / HMAC / socket submission
  → Wi-Fi contention / radio / ESP network stack / JSON parse
  → HMAC / session / sequence / deadline validation
  → motor-task command queue, nominal 5 ms cycle
  → sensor/deadline checks / steering PID / traction and steering PWM API calls
  → signed output_written evidence
  → PWM peripheral phase / H-bridge / current rise / friction / mechanical inertia
  → motor rotation / wheel movement / chassis movement
```

Acknowledgements travel back through the ESP task, signed UDP, phone receive and trace publication; this return path is separate from the motor-output time. A measured RTT is not a one-way delay. Raw `applied` marks command consumption **before** the PWM calls. The added signed `output_written` is emitted **after** the motor iteration writes outputs, and identifies only the final command consumed by that iteration. Intermediate commands can have `applied` evidence without any corresponding output write. Do not treat those automatically as network packet loss.

`rx_ms` is sampled after JSON parsing, so its difference from phone send includes ESP receive scheduling and parsing. PWM completion is a software API endpoint, not measured motor-terminal voltage or motor rotation. Firmware timestamps have one-millisecond granularity. The older timing panel is labeled command consumption accordingly; the new monitoring report uses `output_written` for PWM API completion.

The export joins browser session/input/event IDs → phone `manual_input.source_state` → signed command boot/session/sequence → signed `output_written`. Periodic repetitions of a held input do not count as new key presses. The first eligible corresponding write supplies each input-transition result; packet-level results remain available separately. A release STOP keeps its input lineage. Direct/fallback duplicates retain their original event ID and deadline.

## Calculations

For two times on one monotonic clock:

```text
duration_ms = (end_ns − start_ns) / 1,000,000
```

Nanoseconds are exported as decimal strings and subtracted as integers before conversion, avoiding JavaScript precision loss.

For four-timestamp clock mapping, `t1/t4` are local send/receive and `t2/t3` remote receive/send, in milliseconds:

```text
remote_minus_local_offset = ((t2 − t1) + (t3 − t4)) / 2
network_RTT = (t4 − t1) − (t3 − t2)
initial_uncertainty = network_RTT / 2 + 1 ms
age_allowance = sample_age_ms × 0.002
effective_uncertainty = initial_uncertainty + age_allowance
```

Runtime maps expire after five seconds without a usable sample; resets/implausible drift invalidate them. A worse probe does not renew a better map's age. Two maps are needed for Mac input → ESP output, so their uncertainty bounds add. Cross-clock estimates with unavailable bounds or a combined bound above 150 ms are excluded. Negative estimates within their bound are counted as clock-censored rather than silently set to zero; inconsistent intervals are counted separately. Raw evidence remains in the file.

```text
capture_to_phone_draw = draw − sensor_timestamp               [eligible sensor clock only]
image_to_phone_draw   = draw − image_available
preprocess           = prepared − inference_start
inference            = inferred − prepared
postprocess          = decision − inferred
input_to_PWM_write   = mapped_phone_output − mapped_input
input_to_actual_onset = input_to_PWM_write + electrical_and_mechanical_response
```

Use each sample's clock interval `[max(0, estimate−bound), estimate+bound]`. The results table includes n, nearest-rank p50/p95/p99, maximum and maximum clock bound; JSON also includes minimum, mean, population standard deviation and per-sample intervals. Nearest-rank quantile index is `ceil(p × n) − 1`. Percentiles of individual stages cannot be added to obtain an end-to-end percentile. Small n makes tail quantiles weak evidence; report n alongside every percentile.

### Nominal scheduling calculations, not hardware results

| Process | Calculation | Interpretation |
| --- | --- | --- |
| Camera sampling | `1000 / 30 = 33.333 ms` | Scene appearance can wait approximately one frame; 16.667 ms average only for uniformly random phase at exactly 30 fps |
| Browser manual scheduler | `1000 / 50 = 20 ms` | Nominal phase wait 0–20 ms; average 10 ms under uniform phase |
| Phone transmit poll | `2 ms` | Nominal phase wait 0–2 ms; average 1 ms under uniform phase |
| ESP motor task | `1000 / 200 = 5 ms` | Nominal phase wait 0–5 ms; average 2.5 ms under uniform phase |
| Sum of the three manual phase waits | `10 + 1 + 2.5 = 13.5 ms` average; `20 + 2 + 5 = 27 ms` nominal phase sum | Excludes OS scheduling, transport, validation and actuation; not a measured bound or total latency prediction |
| Drive ramp 200 → 255 at 100%/s | `(255−200)/255 = 0.215686 s` | Full output ramp time; first forward request already starts at logical PWM 200 |
| Steering center → full at 250%/s | `100/250 = 0.4 s` | Requested steering-ramp duration, separate from physical steering response |
| Reversal neutral pause | `300 ms` configured | Intentional direction-change gate; preserve it when comparing latency |
| PWM period | `1000 / 10000 = 0.1 ms` | Peripheral cycle; API completion does not timestamp the first physical output edge |
| Display period | `1000 / display_refresh_hz` | Queue/compositor/scanout can add delay after the instrumented draw callback |

The current logical ±255 duty limit maps to physical ±170 at the 12.6 V reference. Voltage/load/friction can dominate actual onset. Battery monitoring is disabled in the current configuration, so record the externally measured pack voltage in notes. Trace processing itself also costs CPU, radio and storage bandwidth: compare a representative run with monitoring enabled against the normal control-loop gap/stream counters.

## Physical onset evidence

There is no wheel/traction encoder. A PWM acknowledgement alone cannot answer “when did the motor actually start rotating?” IMU acceleration measures chassis vibration/acceleration, not rotor rotation; VIO measures filtered chassis motion and currently requires missing mounting calibration. Steering potentiometer samples measure steering angle, at the exported sensor timestamps, with telemetry gaps and a nominal 20 ms reporting period.

For an independently observed onset, record a high-frame-rate external view containing the input witness and a marked wheel/shaft. Keep the original video and annotate the input witness frame, last stationary frame, first moving frame, frame rate, trial/input ID and how the witness relates to the browser input. A visible key press, a highlighted dashboard key and an instrumented electrical edge are different start boundaries; state which was used. At frame rate `F`, a one-frame interval is `1000/F` ms. Bracket onset between the last stationary and first moving observations and include input-witness and clock-alignment uncertainty.

For stronger electrical/rotational evidence, use a logic analyzer on a documented command witness/PWM edge plus an optical encoder or tachometer. Select/configure any additional GPIO only after the hardware wiring is specified. Keep current-rise onset, shaft rotation, wheel movement and chassis motion as separate endpoints. Attach this evidence alongside the session JSON for the final report.

## Optimization experiments

Change one factor per session, repeat several isolated trials, and compare p50/p95/p99, clock bounds, missing evidence, thermals and detection quality. Compare cold startup separately from warmed steady-state frames.

| Contributor | Experiment / possible change | What to inspect or preserve |
| --- | --- | --- |
| Camera / ARCore availability | Measure sensor→acquired, light/exposure conditions; investigate a manual-only Camera2 pipeline if ARCore dominates | Preserve a single camera owner and correct timestamp source; do not estimate exposure by callback offset |
| Model inference | Benchmark a separately validated GPU delegate, thread counts, INT8 or smaller input | GPU creation/run/close on one thread; revalidate shapes, quantization, confidence and warning accuracy |
| I420 / RGB / resize | Use SIMD/native buffer paths and reduce copies if preprocessing dominates | Correct YUV strides, colors, padding and immutable ownership |
| Inference queue | Keep the existing latest-frame replacement policy; avoid inference backlog | Frame drops/replacements are explicit; compare age as well as FPS |
| NMS / rule | Bound proposal count; profile postprocessing before tuning | Record any threshold/ROI change as a different experiment |
| Pixel indicator | Publish directly after inference; inspect decision→draw | Measure UI scheduling and physical display separately |
| Mac indicator | Publish decisions immediately; avoid waiting for 10 Hz telemetry | Keep stale state explicit; the reliable warning lane may queue under congestion |
| Video contention | Compare the same session with streaming/collection off, then reduced bitrate/FPS | Current target is 640×480/30 fps, 3 Mb/s. Disabling VideoBridge also removes the direct input channel: hold the input route constant or report that confound. |
| Browser input cadence | Compare an explicitly designed immediate dispatch after a changed input | Current initial change can wait for the next 20 ms sample; preserve ramp behavior and release handling |
| Input transport | Compare direct WebRTC versus WebSocket fallback | Keep authentication, immutable deadlines, duplicate rejection and stop authority |
| Phone transmit queue | Profile poll delay and signing; consider event-driven wakeup | Prevent old periodic outputs overtaking a newer input or STOP |
| Wi-Fi / radio | Keep devices close, document contention and AP channel; retain low-latency Wi-Fi mode | Inspect RTT and clock bounds; half an RTT is not a measured one-way delay |
| ESP loop | Inspect validation/queue/write intervals, overruns and rejected deadlines | Motor task priority/stop task/watchdog must remain effective |
| Actuation | Measure voltage, load, breakaway threshold, backlash and current rise | Distinguish commanded ramp from electrical/mechanical response; retain reversal/stop constraints |
| Thermal / recording overhead | Repeat warmed runs at comparable temperature with optional collection off | Keep thermal status, trace gaps, writer drops and control-loop gaps in the comparison |

Do not shorten the 300 ms manual freshness window or 500 ms lost-link watchdog to make an observed latency number smaller. These are control-authority limits, not processing delays.

## Software verification and reproduction

Host verification for this change: official model export + finite-output invocation; core decoder/NMS/timestamp tests; dashboard authentication/export/disconnect tests; Android unit tests and lint; ESP32 compilation and existing motor deadline/stop fixtures; isolated Chrome manual-control and monitoring flow at desktop/mobile sizes. Any simulated or Mac-only numbers are excluded from the hardware results table above.

```sh
npm --prefix controller/node-js/maxbot test
sh tools/maxbot/test-core.sh
sh tools/maxbot/test-calibration.sh
node tools/maxbot/test-browser-latency.cjs
```

Android checks: from `robot/android`, run `./gradlew :maxbot:testDebugUnitTest :maxbot:lintDebug :maxbot:assembleDebug` using the installed JDK 17/SDK. Model reproduction requires an isolated Python 3.11 environment with `requirements-baseline.txt`, then `tools/maxbot/export_baseline.py`. Build the firmware after `python3 tools/maxbot/configure.py`; the runtime fingerprint changes with firmware source.

## Primary references

- [Official YOLOv5 export documentation](https://docs.ultralytics.com/yolov5/tutorials/model-export/): pretrained model/export workflow.
- [Pinned v7.0 TensorFlow implementation](https://github.com/ultralytics/yolov5/blob/v7.0/models/tf.py): normalized decoded xywh/objectness/class tensor.
- [Android camera timestamp definition](https://developer.android.com/reference/android/hardware/camera2/CaptureResult#SENSOR_TIMESTAMP): sensor boundary and REALTIME comparison rules.
- [Android elapsed realtime clock](https://developer.android.com/reference/android/os/SystemClock#elapsedRealtimeNanos()): phone application timebase.
