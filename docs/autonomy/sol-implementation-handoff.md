# GPT-6.1 Sol implementation handoff

Status: ready for a subsequent task-1 implementation request, **2026-11-13**. This handoff and the accompanying review change documentation only. No hardware performance, model accuracy or physical course feasibility has been demonstrated by this review.

Implementation record: [milestone evidence](implementation-evidence.md), [deployment readiness and remaining device evidence](deployment-readiness.md). The accepted specification below remains the implementation contract.

## Mission and authoritative inputs

Implement the [task-1 system plan](implementation-plan.md): a status-only Pixel 5 Android 14 app that starts its services when opened, runs YOLO and visual/inertial estimation, and controls the calibrated MaxBot over Wi-Fi. The Mac dashboard manages settings/models/policies, plans between runs, routes manual intents through the phone, directly stops the ESP, and displays measured response times. Use existing OpenBot/MaxBot code as the skeleton.

Read the system plan first, then the [full training pipeline](policy-training-pipeline.md) for logging/experimental interfaces and the [brief task-2 plan](training/implementation-plan.md) for deferred scope. If legacy README/runtime defaults conflict with the accepted geometry or architecture, migrate them deliberately; do not silently keep contradictory defaults. Preserve unrelated uncommitted work.

| Final decision | Required behavior |
|---|---|
| Camera | Normal rear 1×; enumerate actual ARCore configurations. Ultrawide is conditional on measured coverage failure. |
| Operational transport | Wi-Fi. Android USB control is deferred unless a measured comparison justifies it. |
| Deployment | Temporary Mac→ESP USB flashing is available. OTA is not required. |
| Planning/control | Mac computes the full race reference while stopped. Pixel runs estimation and feedback initially at 50 Hz. |
| Command authority | Phone alone sends motion. Mac manual intent is handled independently of inference; direct Mac→ESP is stop-only. |
| Course | Four orange 200 mm diameter × 150 mm high cones; 500 mm edge gaps; bumper starts 1 m from the first near edge; right/left/right/left. |
| Workflow | One-sided scout, stop/freeze map, Mac plan, manual reposition retaining localization, verify, Mac-started timed run, finish crossing, controlled stop. |
| Learning | Frozen parameters during a run; stochastic parameter search between runs. Task-2 implementation and model training remain deferred. |

Keep Java/Android, the existing Node/plain-JavaScript console, Arduino ESP32, and Python host tooling. Introduce small modules around actual responsibilities; avoid a language/framework rewrite, cloud dependency or new web service. Preserve OpenBot attribution. The native MPC may remain available for later comparisons; the initial policy and image preprocessing must build without requiring its generated solver.

## Work boundaries and evidence

- Preserve the saved steering table, polarities, PID law, starting thresholds and logical ±255 → physical ±170 PWM mapping. This is potentiometer-feedback DC steering, not an RC pulse servo. Preserve disabled battery monitoring and the absent physical stop input.
- Add the user-confirmed 55 mm rear overhang and course dimensions through an explicit migration. Keep unknown camera extrinsics, clear floor dimensions and dynamics marked unknown; no invented calibration or automatically approved commissioning flags.
- Build software and replay tests even if hardware, POV images or real cone weights are unavailable. Record the exact missing validation. Replay success does not authorize an autonomy-ready indicator for live use.
- Implement task-1 measurement/recording support and a conservative initial vehicle model. Defer training, automated dynamics fitting, the closed-loop training simulator, Bayesian optimization and learned residuals to task 2.
- Finish and check each software milestone before broadening scope. Do not ask the user to choose the already accepted camera, transport, tracker or command architecture again. New measured contradictions should be reported with concrete evidence.
- A future implementation request authorizes code/build work; it does not by itself initiate physical movement. Prepare deployable artifacts and operator-run commissioning steps. Never flash synthetic calibration test fixtures onto the car.

## Shared contracts to settle first

Use meters, seconds and radians internally; preserve explicit degrees and logical PWM at the actuator boundary. Vehicle coordinates are rear-axle origin, x forward, y left, z up; positive yaw/steering turn left. Identify world/course frames and transformation revisions explicitly.

| Contract | Required content / invariant |
|---|---|
| `ConeImageObservation` | Frame ID, timestamp/domain, image box/confidence and optional visible base features. It is not a metric cone center. |
| `ConeLandmark` / `CourseMap` | Estimated center/base footprint, observations/provenance, observed versus prior state, empirical error bounds, frame ID and `mapEpoch`. |
| `VehicleState` | Timestamped rear-axle pose, speed, yaw rate, acceleration, actual steering, VIO/IMU IDs, prediction horizon and quality bounds. No fabricated ARCore covariance. |
| `ReferenceTrajectory` | Arc-length pose/curvature/speed samples through braking tail; side constraints; map/frame, calibration, vehicle-model and planning-parameter identities; validator report. |
| `PolicyOutput` | Wheel angle, logical drive PWM, validity deadline, source state/frame IDs and diagnostics. Updating packet sequence alone never refreshes source validity. |
| `ManualIntent` | Browser session/event ID, original event time and mapped deadline, deadman state, steering/throttle; preserved across forwarding. |
| `MotionCommand` | Version, phone session/boot binding, motion epoch/sequence, command ID, absolute expiry, source IDs, calibration identity and authentication. |
| `OperatorStop` | Separate stop-only role/replay state; boot binding, bounded request ID and signed applied-stop acknowledgement. It cannot arm/reset or become the motion owner. |
| `TraceEvent` | Clock domain, event ID, causal source IDs, boot/session/map/config identities, stage timestamps and loss counters. Use integer-safe timestamp serialization. |

Publish shared versioned schemas and cross-language golden fixtures. Bound all message/array sizes; reject malformed, nonfinite, unsupported and mismatched values. Use a deterministic documented serialization/hash convention across Java, Python, Node and ESP. Keep secrets in existing private configuration and out of fixtures/logs.

## M0 — preserve the baseline and introduce contracts

Entry points: `firmware/calibration/calibration.json`, `geometry-measurements.json`, `tools/maxbot/configure.py`, `maxbot-core/Calibration.java`, the README configuration block and the existing Node settings.

Record current code/configuration identities and relevant checks. Separate hardware calibration, app defaults, model manifests, vehicle dynamics, course settings, policy parameters and reference artifacts. Replace README-as-runtime-input incrementally, preserving private override precedence and actuator identity. Keep geometry identity separate from hardware flashing requirements. Write a migration note showing changed values and their evidence.

Acceptance: known steering/PWM behavior is preserved; imported settings retain credentials; missing measurements stay explicit; incompatible schemas/NaNs are rejected; the confirmed course produces centers at x = 1.31194, 2.01194, 2.71194, 3.41194 m in the initial rear-axle frame. Manual commissioning remains available under its existing calibrated limits.

## M1 — enforce ESP deadlines and independent stop

Entry points: `firmware/maxbot/maxbot.ino`, `firmware/common/actuator_pwm.h`, the separate-task pattern in the calibration firmware, `maxbot-core/Protocol.java`, `VehicleLink.java` and the Node server.

Extract a nominal 200 Hz motor task with bounded command handoff and independent absolute expiry. Preserve the calibrated steering law initially; measure elapsed time/overruns without silently substituting the bench sketch's different PID formulation. Parse/HMAC/sign/serialize outside the motor task. Capture RX, validation and actual application timestamps, including local expiry/stop events. The application ring must not block actuation when full.

Implement a separate authenticated operator stop role. A stop updates an atomic latch consumed by the motor task; acknowledgement follows application. Keep phone session, sequence, telemetry destination and calibration matching intact. Ordinary UDP traffic cannot starve stop processing or local expiry. Reset remains explicit through the stopped phone workflow and never rearms.

Acceptance: test nearly expired packets, queue delay after validation, reordering/replay, reboot/session/motion-epoch changes, clock uncertainty, watchdog versus absolute expiry and flood pressure. Verify that an operator stop neither hijacks phone routing nor grants arm/reset/motion authority, and that subsequent ordinary phone packets cannot clear the latch. Host tests verify logic; only device traces establish the actual 200 Hz schedule and application deadline.

## M2 — isolate phone control and expose truthful timing

Entry points: `MaxBotActivity.java`, `RunSupervisor.java`, `VehicleLink.java`, `DashboardLink.java`, `LatencyStats.java`, `ArCamera.java`, and the Node telemetry/UI.

Extract lifecycle/orchestration, independent TX/RX, a 50 Hz state/policy scheduler, and a lightweight manual/stop path. Avoid running inference, network I/O or recording under the supervisor monitor. Publish immutable snapshots and newest-output handoffs. Send completed outputs promptly without adding another full TX phase. Implement explicit zero/apply acknowledgement and motion-epoch change for manual takeover. Preserve each browser intent's original expiry; focus loss, stale events and reconnect cannot sustain throttle.

Implement camera timestamp capability reporting and phone↔ESP four-timestamp clock sync before claiming capture latency. Include drift/reset/uncertainty. If exposure mapping cannot be validated, show unavailable capture latency and separately label image-available latency. Trace the first applied response to each genuinely consumed frame plus per-command information age; separate detector, VIO and IMU lineage. Show measured p50/p95/p99, samples, failed deadlines and missing events. Add policy-output→PWM and dashboard/video age.

Acceptance: a deliberately slow inference/replay worker cannot block manual stop, local command expiry or telemetry handling; a repeated old intent with a new packet sequence expires; transitions cannot revive queued commands; clock reset invalidates estimates. A deterministic delayed trace fixture verifies percentile/causal accounting and shows missing data as missing. Real latency and scheduling independence still require the sustained device benchmark.

## M3 — validate camera/geometry and integrate detector adapters

Entry points: `ArCamera.java`, `ConeDetector.java`, `LatestFramePool.java`, `Geometry.java`, `CourseMap.java`, `NativeMpc.java`, `mpc_jni.c` and `CMakeLists.txt`.

Enumerate the normal camera's ARCore image dimensions/FPS/ID, timestamp source and actual intrinsics. Preserve a functioning ARCore rendering/session lifecycle despite the status-only UI. Bind lens/stream/crop/transform identity to calibration. Use calibrated CPU-image geometry, including orientation and distortion treatment. Capture mounted POV frames and capability reports when the phone is available.

Separate image preprocessing JNI and loading from the MPC solver. Implement stride-correct pooled crop/resize/YUV conversion and normalized input. Preserve newest-frame scheduling, handle the worker wakeup race, and release each owned image/buffer exactly once. Fan optional video/recording out separately and cap their load.

Add explicit model-family/decoder manifests. Start with the existing YOLOv5n 320 contract; integrate a supplied YOLO26n 320 artifact as the first challenger, with GPU creation/inference/disposal on one worker thread. Validate actual tensor/operator/delegate compatibility; do not guess the output layout. CPU INT8 and YOLO11n are conditional comparisons. Record absent real weights as an outstanding dependency; synthetic detections are test-only.

Convert image observations into metric cone-center estimates using calibrated base geometry and multiple views. Keep visible rim, inferred center and prior positions distinct. Reject clipped/grazing projections. Align VIO/IMU, gravity, sensor-to-car transform and lever arm; do not double-count correlated ARCore/raw IMU measurements or rely on raw acceleration integration for speed.

Acceptance: fixtures cover YUV strides, rotation/letterbox decoding, quantization metadata, empty detections, latest-frame wakeup/ownership, missing native MPC and model rollback. Geometry fixtures must expose rim/center bias and low-camera pitch sensitivity. Independent measured scene views establish real error bounds; repeated residual scatter alone is insufficient.

## M4 — collect baseline dynamics, scout and plan on the Mac

Entry points: `HardwareRecorder.java`, `RunRecorder.java`, `CourseMap.java`, `Trajectory.java`, Node server/services and a new `tools/maxbot` offline-planner entry point.

Provide logged manual measurement support for conservative speed/PWM feedforward, steering response/rate, acceleration, braking and VIO/filter lag. Store reviewed tables with provenance and valid ranges; no high-speed defaults derived solely from starting PWM or the saved 60 ms steering prior.

Implement bounded one-sided scouting with conservative row-envelope clearance and observed/prior distinction. Permit an occluded launch view; stop if the next scout segment cannot be justified. Freeze only an adequately observed map and report quality. Preserve the localization session while repositioning and reject unexplained jumps.

Implement a stopped Mac planner as Python/SciPy tooling invoked asynchronously by the existing Node server. Use a curvature/arc-length bicycle representation, multiple seeds and constrained optimization (SLSQP is an initial implementation choice), then independent continuous-path validation. Optimize approach headings and longitudinal pass locations instead of retaining zero-heading lateral quintic peaks. Check the oriented full vehicle envelope against all cone bases, floor limits and uncertainty margins. Retiming must respect measured steering rate/lag, acceleration, lateral grip and stopping. Produce a complete JSON reference with braking tail and structured diagnostics. Planner subprocess work must not block dashboard stop handling; cancel/discard results if the vehicle leaves the stopped state or dependencies change.

First establish feasibility, then rank feasible retimed candidates by estimated launch-to-finish-crossing time with small secondary smoothness terms. Stopping after the finish remains a hard constraint. A shortest-distance solution is not necessarily fastest, and a local optimizer does not prove global optimality. The phone independently validates reference samples/interpolation, dependency identities and constraints before staging. Keep the inherited 150 mm clearance visible; do not shrink it to make optimization succeed. A failed search is a failed search, not proof that the course is impossible.

Acceptance: regression evidence detects the old nominal path's approximately 51.2° requirement against the 26° limit. Add feasible and infeasible geometry fixtures, between-sample collision, rear-overhang/rotated-footprint clearance, wrong side, braking-tail failure, altered map epoch and interrupted/stale planner result. A fixture-generated reference proves software behavior only; measured geometry/dynamics must support the physical course.

## M5 — follow the reference and score the run

Entry points: new policy/tracking modules extracted from `RunSupervisor.java` and `Trajectory.java`, plus estimation, logs and dashboard mode controls.

Implement `cone_slalom_v1` using the exact pure-pursuit/yaw-damping baseline in system-plan section 7. Use bounded progress projection, actual elapsed time, predicted application-time state, calibrated angle/rate limits and measured PWM feedforward plus PI speed feedback/anti-windup. Do not add duplicate curvature feedforward. Follow reference speed by actual progress; reduce speed/stop on tracking or quality violations instead of rerouting during motion.

Separate image, landmark, VIO and IMU freshness. A mapped cone leaving view is expected; stale essential localization is not. Enforce bounded propagation and error limits independently of network heartbeats. Validate launch position/heading and map/reference identity before the Mac start is accepted. Start the primary timer at phone acceptance, including launch delay. Require ordered right/left/right/left swept-footprint passage; finish when the entire vehicle clears the last cone, then execute the braking tail. Preserve failed/aborted runs for review and prohibit an automatic clean-contact verdict.

Acceptance: straight/curved and delayed-state fixtures, saturated PWM anti-windup, zero/behind preview, variable tick timing, progress jumps, cone occlusion with good VIO, stale VIO despite fresh empty detections, wrong-side pass, last-cone departure before rear clearance, finish-before-rest, manual takeover and reconnect. Do not use replay of fixed camera footage as evidence that a changed closed-loop policy will be faster on the car.

## M6 — complete remote management and operator flows

Entry points: `controller/node-js/maxbot/{server.js,hub.js,public/}`, `DashboardLink.java`, configuration/lifecycle modules and `MaxBotActivity.java`. Follow the existing root `DESIGN.md` when changing UI.

Add authenticated AP-local dashboard discovery with expiring registration, Wi-Fi-bound phone sockets, and connection-order recovery. Implement staged JSON/model/reference upload, capability/schema/hash validation, stopped-only atomic activation and rollback. Apply compatible artifact sets together; a new revision cannot leave an old incompatible reference active. Construct/warm components before reporting readiness. Support installed policy engines/model operators without reinstalling the APK; new native code still needs an app update.

Expose policy selection, scout/plan/reposition/verify/run, held-deadman manual drive, persistent direct stop, health and measured latency, planning diagnostics, configuration diffs, benchmark/export and run/contact review. Preserve bounded video bitrate and pause large uploads during motion. The phone has status indicators only and starts services on open; first-time Android permissions/Wi-Fi/ARCore setup can occur through system UI. Reconnect/reboot/update never arms the car.

Acceptance: browser disappearance while Node lives; inference failure with working manual path; phone failure with working direct stop; Mac/ESP reconnect; discovery spoof/mismatch; unknown policy; partial upload; invalid model; app restart during activation; map/config/reference mismatch and rollback. Check keyboard/focus-loss controls, truthful stale/unavailable statuses, and zero app input buttons. Do not add a second direct Mac motion path.

## M7 — build, measure and report deployment readiness

Run checks appropriate to changed components. Existing entry points from the repository root:

```sh
sh tools/maxbot/test-core.sh
python3 -m unittest discover -s tools/maxbot/tests -v
npm --prefix controller/node-js/maxbot test
```

From `robot/android`, build/check `./gradlew :maxbot:assembleDebug :maxbot:lintDebug :maxbot-core:verifyCore`. Use `sh tools/maxbot/test-calibration.sh` when shared calibration/actuator code changes. Use `sh tools/maxbot/test-native.sh` when retained MPC code changes and its generated OSQP inputs are present; the new non-MPC path needs independent preprocessing/build verification. Add focused planner/protocol/deadline tests to the appropriate existing suites. Do not repeat unaffected suites without a reason.

Compile runtime ESP firmware for classic ESP32 Dev Module with the available Arduino toolchain; the repository documents ESP32 core 3.3.0 and ArduinoJson 6.21.5 as previously used. Inspect local CLI/library locations rather than guessing them. Keep private generated calibration and credentials out of published artifacts. Compile synthetic fixtures separately and label them nondeployable. Refresh source references/system maps when files actually move.

Prepare the real calibrated firmware for temporary Mac USB flashing and the APK for Wi-Fi deployment (wireless ADB is optional). Flashing availability does not imply the hardware is currently connected. Verify the updated firmware's identity and stop behavior before floor runs. After one-time Android setup, app opening must connect/warm/report automatically without starting motion.

Follow system-plan section 6 for warm-up plus at least 15-minute full-load benchmarks, video off/on, thermal behavior, packet pressure, applied-command traces and clock quality. Start at 50 Hz; higher rates/models are controlled experiments. Publish actual values in a benchmark report, including misses and sample loss. The initial capture→PWM p95 <100 ms goal is unproven until measured. Steering onset/settling uses the potentiometer; traction physical latency remains unmeasured unless independently observed, for example using slow motion.

Finish with a capability checklist distinguishing **implemented**, **host-tested**, **device-tested** and **physically validated**. Missing weights, mount measurements or device access block only dependent validation; complete the remaining authorized software work and state the exact outstanding evidence. Do not describe the project as autonomously commissioned based on unit tests or synthetic replay.

## Pasteable implementation request

> Implement task 1 from `docs/autonomy/sol-implementation-handoff.md` and `docs/autonomy/implementation-plan.md`. Follow milestones M0–M7 and the accepted decisions, preserving existing uncommitted work and calibrated actuator behavior. Complete the authorized software, tests and build artifacts; keep task-2 training/search implementation deferred. Use the existing Java/Node/Arduino/Python stack. Continue independent software work when hardware or real cone weights are unavailable, and report those validation gaps honestly. Do not start physical motion. Keep a concise milestone/evidence log and finish with changes, checks, artifact locations and remaining device commissioning steps.
