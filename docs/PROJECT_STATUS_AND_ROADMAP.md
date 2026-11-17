# MaxBot — implementation status, evidence and roadmap

Updated **17 November 2026**, Asia/Dubai. This is the project handoff for the latest source checkpoint. It consolidates the implementation, hardware work, experiments, operational instructions and remaining plans. Detailed linked records remain the authority for individual measurements.

## Current state

MaxBot is a local Pixel 5 + classic ESP32 control system for an indoor 1:16 car. Manual driving, steering feedback/tuning, camera streaming and signal-propagation monitoring are implemented. An operator has driven the car on the floor and supplied a real timing session. Autonomous slalom software exists, but physical autonomous commissioning remains incomplete.

| Baseline | Status |
| --- | --- |
| Confirmed manual recovery point | Git tag `manual-control-v0.4.0-working`; operator-confirmed on 14 November |
| Most recent recorded floor run | App `0.6.0-signal-monitor`, version code 10; 16 November, reported 12 V supply |
| Latest source / APK | `0.6.1-timing-fix`, version code 11; builds, unit tests and lint pass |
| Latest timing corrections on Pixel | Prepared; the short camera-clock validation recording is still pending |
| ESP runtime firmware | Flashed and authenticated over USB on 16 November; unchanged by the subsequent APK timing correction |
| Physical traction onset | Not measured by a rotation sensor or synchronized external video |
| Autonomous course completion | Not commissioned or physically validated |

“Latest source” and “confirmed on-car behavior” are separate evidence levels. Preserve the 0.4.0 recovery tag and its private local artifacts when experimenting with newer versions.

## Hardware and coordinate conventions

- Pixel 5, Android 14 in the recorded run; rear camera via ARCore.
- Classic ESP32-D0WD-V3; DC traction and five-wire potentiometer-feedback DC steering through TA6586 H-bridges.
- Traction pins 4/16; steering pins 22/23; potentiometer GPIO35 on ADC1. Steering does not accept hobby-servo pulses.
- 3S supply, up to 12.6 V. Logical ±255 maps to hardware duty ±170, preserving the configured 8.4 V average-duty envelope at the reference voltage. This is not a regulated motor supply.
- Battery monitoring is disabled. The operator measures pack voltage externally; there is no automatic low-voltage stop in this configuration.
- No wheel encoder or physical stop switch is configured. Software stop, emergency-stop, freshness and watchdog paths remain active.
- Positive steering means left; negative means right. Calibrated command limits are −26° to +26°.
- Recorded geometry includes 173 mm wheelbase, 144 mm overall width, 127 mm tire-center spacing, 38.94 mm front overhang and 55 mm rear overhang. Camera lens offsets from the rear axle are +60.94 mm forward, +50.90 mm left and 99.24 mm high.
- Course priors: four cones, 200 mm base diameter, 150 mm height, 500 mm edge gaps, 1 m bumper launch gap; first pass to the right. Priors are not observed map landmarks.

Camera pitch/yaw, stream binding, IMU alignment/bias/lever arm, empirical localization error, floor bounds and vehicle dynamics still require appropriate measurements before autonomy. See [calibration parameters](calibration-parameters.md), [measurement history](../firmware/calibration/geometry-measurements.json) and [configuration contracts](../maxbot/config/CONTRACTS.md).

## Implemented system architecture

| Component | Responsibilities | Main source |
| --- | --- | --- |
| Mac browser | Pairing, live video/state, WASD and pointer control, steering chart/tuning, warning indicator, experiment monitoring and downloads | `controller/node-js/maxbot/public/` |
| Mac Node service | Authenticated single-operator/robot hub, signalling, discovery, direct stop, steering settings, planner/artifact coordination and experiment persistence | `controller/node-js/maxbot/` |
| Pixel application | Camera ownership, video encoding, YOLO inference, manual supervisor, IMU/VIO acquisition, authenticated ESP transport and tracing | `robot/android/maxbot/` |
| Shared Java core | Calibration, control authority, frame/output ownership, clock mapping, model decoding, geometry, planning/control abstractions and timing | `robot/android/maxbot-core/` |
| ESP firmware | Motor outputs, potentiometer feedback, steering PID, deadlines/watchdogs, authenticated commands, stop task, telemetry and tuning persistence | `firmware/maxbot/`, `firmware/common/` |
| Offline tools | Configuration generation, packaging, model export, data/report analysis, planning/training scaffolding and host verification | `tools/maxbot/` |

All runtime inference and control are local. The dashboard does not require internet after dependencies/assets are installed. GitHub contains source, the public baseline model, public configuration examples and reviewed documentation; private deployment configuration and recorded raw sessions stay local.

## Completed work

### Manual driving and control authority

Manual mode has a separate commissioning path. It does not require a trained cone model, ARCore tracking, a course map or an unmeasured speed/PWM model to drive an otherwise commissioned vehicle.

Browser inputs retain their original event IDs and deadlines. The Pixel validates freshness and produces an immediate supervisor output; periodic control continues for neutral/watchdog behavior. Latest-output ownership prevents older periodic results replacing newer input or STOP.

Implemented behavior includes:

- Explicit manual arming; no automatic rearming after reconnect or reboot.
- WASD and press-and-hold pointer controls, editable drive/steering ramps and release toward neutral.
- Drive requests start at ±200 logical PWM, ramp toward ±255, and cut to zero below the 200 threshold on release/reversal.
- Default drive ramp 100%/s; default steering ramp 250%/s. Drive 200→255 takes about 0.216 seconds; steering center→full request takes 0.4 seconds.
- A configured 300 ms neutral reversal pause, with existing direction/stop gates retained.
- Manual input freshness 300 ms and lost-link watchdog 500 ms; autonomous timing remains separate.
- Session authentication, replay rejection, signed ESP commands, epochs and absolute command deadlines.
- Focus-loss, visibility-loss, stale telemetry and disconnect handling that clears input and requests stopped/disarmed behavior.
- Direct authenticated operator-stop path to the ESP, separate from the phone-owned motion channel.

Details: [manual control](autonomy/manual-control.md), [confirmed recovery checkpoint](autonomy/checkpoints/manual-control-v0.4.0-working.md).

### Loaded steering control and persistent tuning

Steering now uses actual elapsed time, filtered derivative on measured angle, anti-windup, configurable friction floor and output ceiling, deadband hysteresis, endpoint protection and stall/feedback checks. Target changes do not inject derivative kick.

The dashboard can read active settings, temporarily Apply settings, and Save to ESP NVS with readback verification. Writes require stopped/disarmed state. Saved records are bound to the actuator calibration identity and validated by version/checksum.

STOP, expiry, faults and arming clear stale steering targets and leases. Diagnostics distinguish inactive PID, a valid zero target, actual steering angle and the phone's requested angle. Feedback plausibility accepts at least ±60° while commanded travel remains ±26°; inward recovery from overshoot remains possible. Median-of-three ADC sampling and transient-versus-persistent feedback rejection are implemented.

The settings preserved during the latest flash were P=50, I=3, D=1.3, deadband=3.5°, minimum effort=0, maximum effort=255 and D filter=12 Hz. These are observed saved settings, not a claim that the final loaded tune is optimal. The separate suggested starting preset is not automatically applied.

Details: [steering tuning](autonomy/steering-tuning.md), [target/feedback corrections](autonomy/steering-feedback-fix.md).

### Video streaming and direct manual inputs

ARCore is the single camera owner. Video uses native strided YUV packing into pooled direct I420 buffers, a latest-frame dispatcher, hardware encoding and WebRTC. Inference/optional recording use separate frame ownership. The requested stream is 640×480 at 30 fps, maximum 3 Mb/s, with resolution preservation under adaptation.

Manual inputs can use an authenticated unordered WebRTC data channel with zero retransmissions. Health checks switch to the reliable WebSocket relay when that lane becomes stale/unavailable. Both routes retain the same event IDs/deadlines, and release/neutral also uses the reliable route. Arming, stops, presence and signalling remain on the reliable connection.

The browser requests minimal extra receiver buffering and records interval FPS/bitrate/jitter-buffer/decode metrics. The phone reports encoder counters, staging and pool/replacement counters. A foreground low-latency Wi-Fi lock is requested. These mechanisms reduce avoidable queues but do not guarantee zero WebRTC buffering or measured glass-to-glass latency.

Details: [streaming and direct input](autonomy/streaming-latency.md).

### Bundled YOLOv5n baseline warning

The APK bundles official YOLOv5n v7.0 COCO weights exported at 320 pixels: FP16 weight storage with FLOAT32 input/output, LiteRT CPU execution and two threads. It loads and warms automatically. Model SHA-256, upstream commit, tensor contract and license are retained; the build checks model integrity.

Warnings use decoded boxes, objectness×class confidence ≥0.35, class-aware NMS at 0.45 IoU, and a central lower-image area rule. The Pixel and Mac display warning/no-warning/stale states. This is an advisory image-zone heuristic, with no calibrated distance, time-to-collision or automatic braking authority. COCO has no dedicated traffic-cone class.

The autonomous one-class cone model and its manifest are separate from the general-object manual warning baseline. The public baseline file and its license are committed; private/custom model exports remain ignored.

Public baseline SHA-256: `17a6c7117fc0e1bede33533e3df9183ed3b0a23efa561fea5dc70861343134bf`.

### Signal-propagation monitoring and report generation

The dashboard's **Start monitoring / Stop and save** flow records browser inputs, phone control/inference/draw events, signed ESP applications/output writes, clock probes, thermal state, raw IMU, available visual state and streaming metrics.

Monitoring saves continuously to a Mac JSONL journal, then writes a self-contained JSON dataset and Markdown results. Final parsing/statistics run in a worker thread so large reports do not block the control/heartbeat event loop. Recording is bounded, drops/gaps are explicit, and sessions end after 15 minutes or 32 MiB. Disconnects preserve incomplete reports; a journal recovery tool is included.

The added signed `output_written` event occurs after PWM API calls. Earlier `applied` events mark command consumption before those calls. Neither proves physical motor rotation. Joins retain browser session/input/event IDs and ESP boot/session/command sequence. Clock uncertainty is retained, nanoseconds stay decimal strings, and impossible/censored intervals are counted.

Details: [experiment/calculation guide](autonomy/signal-propagation.md), [collector correction](autonomy/signal-propagation-v0.6.1.md).

### Implemented autonomy and offline tooling

The task-1 stack includes geometry/vehicle-state models, cone observations, a staged scout/map/freeze workflow, stopped Mac reference planning, footprint validation, pure-pursuit/yaw-damped tracking with speed control, ordered cone-passage scoring, finish interpolation, artifact-set validation/activation and reviewed run exports. Policy/model/reference identity and calibration compatibility are explicit.

These are implemented software capabilities, not proof of autonomous completion. Missing camera calibration, floor/dynamics measurements, real cone-model evaluation and empirical localization/control validation continue to block commissioning. Optional numerical MPC and later learned-policy work are not prerequisites for current manual operation.

Details: [implementation evidence](autonomy/implementation-evidence.md), [deployment readiness](autonomy/deployment-readiness.md), [system map](system-diagram.md), [future training plan](autonomy/training/implementation-plan.md).

## Hardware deployment evidence

On **16 November 2026**, the connected ESP32 was flashed with the signal-monitor firmware. Flash-tool hashes passed; a signed USB handshake matched the intended firmware/calibration identity; 150 signed telemetry samples remained disarmed at zero drive PWM with no fault. Active/saved steering settings matched the pre-flash snapshot. Verification sent no arm or movement commands.

| Identity | Value |
| --- | --- |
| ESP firmware source | `3ffb24104d2ee8675504551af0ce5c837eb9c654ea9437b55b7a1f331ded1b9e` |
| Firmware binary SHA-256 | `14e85e48a52c7ca411b461d185c46b2a1cebc806603bdafd65af779cfd72a480` |
| Actuator calibration identity | `210b9c6c3489ffac1a75e66182cf207d60b6042937fac9dc7cd534210accd687` |

Private local evidence is retained under `.tools/signal-monitor-flash-verification.json` and the before/after steering snapshots. The subsequent floor-drive session independently confirms operator use of the car, not a timestamped physical-onset result. The 0.6.1 APK correction does not change this ESP firmware and requires no additional flash.

## First real experiment and measured results

Session `6cf0c96f820f82d7c6c9b975ff23181c` ran on 16 November, 23:23:59.712–23:24:28.096 Asia/Dubai: **28.384 seconds**. The operator reports movement on the floor, a 12 V pack and multiple obstacles including a chair leg. Video was enabled, optional training collection disabled, and the phone reported thermal status 2 throughout.

The session contains 12,864 records, 176 vision frames, 31 positive warning frames across nine contiguous warning episodes, and 62 matched input transitions. Dedicated trace-drop/gap counters are zero. Five initial vision frames are excluded from steady-state results.

| Endpoint | Median | p95 | Evidence limit |
| --- | ---: | ---: | --- |
| Image available → Pixel warning draw | 185.016 ms | 219.247 ms | n=31 positive frames; not exposure/object-appearance → visible light |
| Image available → Pixel indicator draw, all updates | 188.730 ms | 227.622 ms | n=171; Android draw callback endpoint |
| Input change → first corresponding ESP PWM API write | 63.000 ms | 130.000 ms | n=62 mixed press/release/axes; median clock bound ±23.488 ms |
| YOLO interpreter | 87.788 ms | 103.660 ms | n=171; recorded thermal condition |
| Decode/NMS/rule stage | 53.601 ms | 59.184 ms | Almost all cost is in decoding; rule/box construction median ≈0.024 ms |
| First Mac warning opportunity from image availability, retrospectively deduplicated | 289.314 ms | 612.497 ms | n=31; clock estimate, excludes physical display scanout |

Inference plus postprocessing accounted for about **81% of mean image-available → decision time**. Effective vision throughput was about 6.24 decisions/s while capture publication remained near 30 fps. Latest-frame replacement prevents accumulation but skips many camera frames.

### Video latency diagnosis

| Component | Recorded contribution |
| --- | --- |
| Phone image-available → encoder submission | 5.569 ms median; 12.381 ms p95 |
| Hardware encoding | 18.726 ms mean over the observed 840-frame encoder-counter interval |
| Mac WebRTC jitter-buffer residence | 128.347 ms median; 173.858 ms p95 |
| Mac video decoding | 1.319 ms median; 1.698 ms p95 |
| Network RTT | 35 ms median; 135 ms p95 |

Receiver buffering is the largest measured video contribution. At 30 fps, 128 ms is roughly four frames of residence. Variable network delivery, phone workload/heat and shared hotspot traffic are investigation candidates; their individual causal contributions have not been isolated. The video path does not wait for YOLO inference, although the two workloads share device resources. Video jitter buffering is not an addend in the separate warning-message path. Full camera-to-screen latency remains unvalidated, and statistics from different sample sets must not be summed into an exact total.

### Problems found and corrected in collector 0.6.1

1. The initial sensor-clock gate compared CPU image time with ARCore's general frame timestamp. All frames failed that exact-equality check. The new collector retains/correlates ARCore's explicit Android camera timestamp and still fails closed if the source/age checks fail.
2. Mixed live-warning messages and telemetry snapshots could re-display older frames. The browser now rejects older/duplicate frame IDs and does not refresh their age. Analysis counts only the first presentation per frame and excludes warm-up frames consistently.
3. Phone processing originally ended at a tick-entry marker. Analysis now uses the recorded post-supervisor marker and labels the transmit interval according to the consumed output's producer timestamp.

The original dataset and generated Markdown are preserved locally. Analysis revision 2 intentionally differs from the original revision-1 summary. See the [full session review](autonomy/reports/2026-11-16-signal-propagation-review.md) and [derived audit](autonomy/reports/2026-11-16-signal-propagation-audit.json).

## What remains uncertain

- Capture-clock eligibility after the 0.6.1 correction needs an actual Pixel recording.
- Exposure/object-appearance → physical warning display and input → physical traction onset are not established.
- Clock bounds are large enough that small input-path improvements cannot be claimed confidently from this run.
- The recording is short, with correlated frames/packets and mixed control inputs. Exploratory p99 values are not robust tail estimates.
- Warning boxes had implausible indoor labels, including 19 “airplane” frames. Without saved source imagery, false-positive/false-negative performance and preprocessing/orientation issues cannot be resolved.
- IMU vehicle-axis calibration and usable calibrated visual state were absent. IMU vibration does not substitute for a shaft-rotation sensor.
- Six MCU deadline rejections occurred. Missing write evidence also includes supersession/session boundaries and cannot be labelled directly as packet loss.
- Live video buffer/network-delay causes still need controlled comparisons. Current 0.6.1 work improves timing/freshness accounting, not video transport latency or model inference speed.
- Electrical brake configuration does not establish measured stopping distance, friction, grip or load-dependent steering/traction dynamics.

## Running and rebuilding

### Existing private local bundle

`dist/signal-monitor/` contains the APK, matching firmware images, offline Node dependency/fonts, launcher, runtime configuration, documentation and checksum manifest. It contains credentials and is excluded from Git.

From the repository root:

```sh
sh dist/signal-monitor/start-mac.command
```

Open `http://localhost:8081` and pair with the configured car key. Keep the launcher Terminal open; Ctrl+C stops it. If port 8081 is occupied, stop the existing identified MaxBot instance before starting a second one. The older private `start-dashboard.command` / `stop-dashboard.command` manage a different host launcher; do not mix the two launchers simultaneously.

Both Mac and Pixel must join **MaxBot-Drive** for the current AP setup. A Mac on its normal internet Wi-Fi cannot discover the Pixel on the isolated ESP subnet. Stay connected despite the no-internet notice. “Authenticated vehicle link” on the Pixel proves Pixel↔ESP connectivity, not Pixel↔Mac connectivity. Live data on the Mac confirms the latter. Once local assets are installed, runtime does not need internet; GitHub/tool work can be done after returning the Mac to an internet network.

### Fresh source checkout

1. Install Node 20+, a suitable Python environment, JDK 17, Android SDK/build tools and the configured ESP32 Arduino toolchain. Android module versions are specified in Gradle; ArduinoJson 6.21.5 is the firmware dependency used by this setup.
2. Create private `maxbot.local.json` from the example and existing measured hardware configuration. Enter the real network/pairing values locally; never commit them. Preserve unknown commissioning measurements as unknown.
3. Run `python3 tools/maxbot/configure.py` to generate private runtime/Android assets and firmware headers. Arming remains locked if required hardware inputs are missing.
4. The public YOLOv5n TFLite baseline, license and provenance are included in source. `verifyBaselineModel` checks its hash before APK construction. To reproduce an export, use isolated Python 3.11 with `tools/maxbot/requirements-baseline.txt`, then `tools/maxbot/export_baseline.py`.
5. Build/test the Android `maxbot` module with JDK 17 and the installed SDK:

```sh
cd robot/android
./gradlew :maxbot:testDebugUnitTest :maxbot:lintDebug :maxbot:assembleDebug
```

6. Build firmware from the repository root after configuration, using the local library directory appropriate to your installation:

```sh
arduino-cli compile --fqbn esp32:esp32:esp32 --libraries .tools/arduino-libraries --build-path .tools/signal-monitor-firmware-build firmware/maxbot
```

7. Install the Node dependency with `npm --prefix controller/node-js/maxbot ci`. Run `python3 tools/maxbot/package_signal_monitor.py` after matching APK/firmware builds to recreate the private offline bundle. The packager checks firmware source/calibration fingerprints, APK configuration and model hash. Installation/flashing is a separate explicit operation.

For a source-only console without packaging, the server accepts `MAXBOT_PAIRING_KEY`, `MAXBOT_ESP_HOST` and optional host/port settings through its environment. Prefer the generated private launcher for the current car; do not put its secret values in shared shell transcripts or source files.

### Record and review

Use **Start monitoring** before the first trial and **Stop and save** afterward. Stopping monitoring does not stop/disarm the car. Reports default to `runs/signal-propagation/` under the running dashboard's root; a bundled dashboard writes inside the bundle. Add test notes: voltage, floor/load, object, lighting, thermal condition, video settings and trial order.

```sh
node tools/maxbot/signal-report.cjs PATH_TO_SESSION.json
node tools/maxbot/signal-report.cjs PATH_TO_PARTIAL_SESSION.jsonl
python3 tools/maxbot/review_signal_run.py PATH_TO_SESSION.json --output AUDIT.json
```

The second command recovers complete journal lines and marks recovered evidence incomplete. Retain raw files locally alongside reviewed reports.

## Validation at this source checkpoint

Rechecked on 17 November unless otherwise stated:

| Check | Result / scope |
| --- | --- |
| Dashboard Node suite | 41 tests passed, including authentication, direct/fallback behavior, tuning, timing arithmetic, frame ordering, export/worker and download paths |
| Shared Java core | 2,971 checks passed |
| Android module | 26 unit tests passed; lint and debug APK build passed |
| Native image path | Byte-exact direct/heap I420, strided planes, letterbox, quantized/float normalization and buffer-bound checks passed |
| Firmware host fixtures | Calibration, deadline/replay/queue invariants, steering PID/storage and actual STOP/disarm/rearm function tests passed |
| Python tools | 27 tests run, 26 passed, one optional numerical MPC test skipped because OSQP is not installed in that environment |
| Isolated Chrome | Desktop/mobile layout and browser→WebSocket/WebRTC→synthetic robot→saved report flow covered; this is not radio/car evidence |
| ESP compile | Current source builds for `esp32:esp32:esp32`; no device flash is part of this publication task |
| Public model integrity | Bundled model matches provenance SHA-256; original export smoke tests passed, including a known-image person detection on the Mac |
| Physical evidence | Manual recovery confirmation and recorded floor drive; 16 November firmware flash/signed stopped-state verification |

The installed Gradle/SDK combination emits compatibility/deprecation warnings, although checks pass. SDK/Gradle upgrades should be an isolated maintenance change with rerun validation. Historical host-only JNI or synthetic loopback timing numbers must not be presented as Pixel/car latency.

## Future plan and acceptance criteria

| Priority | Work | Acceptance evidence |
| --- | --- | --- |
| P0 | Install collector 0.6.1 and make a 10-second stationary, disarmed capture | Actual Android-camera/CPU timestamp correspondence or enough raw evidence to diagnose failure; zero trace gaps; ordered Mac frames |
| P0 | Measure physical traction onset | 10 isolated forward and 10 reverse starts, full stops and ≥3 s between trials, session JSON plus synchronized high-frame-rate wheel/input-witness footage or encoder data |
| P0 | Validate warning recognition | 20 isolated entries/exits of one identifiable object, stationary/disarmed car, retained source imagery and labels for missed/spurious warnings |
| P1 | Reduce and explain video latency | Add minimum/target jitter-buffer, transport/retransmission and presentation diagnostics; compare lower bitrate, monitoring load and YOLO load one at a time; obtain glass-to-glass evidence |
| P1 | Optimize decoder and sustained inference | Profile/bound class-score reads and buffer conversion; preserve output correctness; compare sustained warmed/cooled runs; evaluate delegate/thread/quantization variants separately |
| P1 | Characterize loaded steering | Repeated left/right ramp-response trials at zero traction, onset/settling intervals from raw potentiometer samples, explicit gap censoring and documented saved gains |
| P1 | Improve operational diagnostics | Separate Pixel↔ESP and Pixel↔Mac status, remove stale generic pairing hints, consolidate dashboard launcher ownership and port-conflict recovery |
| P2 | Complete autonomous commissioning | Measured camera/IMU/floor/dynamics/braking data, evaluated cone weights, repeatable scout/map/launch verification and clean conservative closed-loop runs |
| P2 | Improve detector/policy | Session-disjoint labelled data, exported-model accuracy/latency comparisons, measured simulator/dynamics and bounded heuristic search before a learned residual |

A video-off comparison currently also removes the direct input data channel. Keep the input transport matched or document that confound. Never shorten freshness/watchdog authority limits merely to improve a timing statistic. Keep software endpoint changes separate from performance changes when comparing experiments.

Exact experiment protocols and witness definitions are in the [first hardware review](autonomy/reports/2026-11-16-signal-propagation-review.md#recommended-next-runs-in-priority-order). In particular, a displayed key indicator, a physical key press and an electrical command witness are different timing boundaries.

## Repository and recovery policy

The upstream OpenBot repository remains a reference remote. Publish MaxBot checkpoints to `Sherekilebi-labs/Autonomious-Race-Car`, preserving the existing history and confirmed recovery tag. This checkpoint includes the complete manual/streaming/steering/monitoring source changes and latest timing correction, rather than reverting newer work to the historical recovery version.

Private runtime configuration, generated calibration headers/assets, compiled credential-bearing APK/firmware bundles, local tool caches and raw root-level `maxbot-signals-*` exports are ignored. The Git snapshot includes only public model assets, source, examples, test fixtures and reviewed summaries. A fresh checkout needs the operator's private measured configuration to reproduce this specific car deployment.

Do not mark autonomous operation, capture timing, collision-detection reliability or physical motor onset as validated until the corresponding acceptance evidence exists.
