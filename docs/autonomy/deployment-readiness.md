# Task 1 deployment readiness

Current manual-control profile: **[Simple manual control 0.4.0](manual-control.md)**. Manual freshness/watchdog are 300/500 ms; older 100/150 ms manual notes below describe previous releases. Autonomous timing remains 100/150 ms.

Task-1 software implementation and host verification are complete for the checks listed below. **Autonomy is not commissioned. No physical movement, USB flashing, model training or physical course benchmark was performed.** Existing calibration flags remain unchanged.

## Build and artifacts

Run from the repository root:

```sh
python3 tools/maxbot/configure.py
sh tools/maxbot/test-core.sh
python3 -m unittest discover -s tools/maxbot/tests -v
npm --prefix controller/node-js/maxbot test
sh tools/maxbot/test-calibration.sh
sh tools/maxbot/test-image.sh
# Optional retained MPC comparison, with generated solver inputs:
sh tools/maxbot/test-native.sh
```

Android: from `robot/android`, run `./gradlew :maxbot:assembleDebug :maxbot:lintDebug :maxbot-core:verifyCore` with the installed JDK 17 and SDK. Default native builds need no generated MPC solver. APK: `robot/android/maxbot/build/outputs/apk/debug/maxbot-debug.apk`.

Installed Arduino CLI: `/Applications/Arduino IDE.app/Contents/Resources/app/lib/backend/resources/arduino-cli`. Classic target: `esp32:esp32:esp32`. Local ArduinoJson library: `.tools/arduino-libraries/ArduinoJson-6.21.5`. Runtime build directory: `.tools/task1-runtime-build`.

**The generated APK, firmware and runtime config embed private pairing/Wi-Fi configuration. Keep them local.** `python3 tools/maxbot/package_task1.py` produces a mode-0600 bundle in ignored `dist/task1-private`, with hashes, public source identity and actuator calibration identity. The package command never installs, flashes, arms or moves anything. Synthetic fixtures under `maxbot/config/fixtures` and `tools/maxbot/native-fixture` are host tests only and are not deployment inputs.

## Operator deployment, while stopped

1. Review `maxbot.local.json` private overrides, run configure, build and package. Geometry/policy changes do not change the saved actuator identity. Hardware calibration changes require matching deployment to phone and ESP.
2. With wheels raised and traction unable to move the car, use temporary Mac USB to flash the real configured ESP build. Operator command: Arduino CLI `upload --fqbn esp32:esp32:esp32 --port YOUR_USB_PORT --input-dir .tools/task1-runtime-build firmware/maxbot`. Confirm the actual port before running. Never upload compile-only fixtures.
3. Install the APK using Android system setup or operator-run ADB. Open MaxBot; allow camera/ARCore through system UI and join the ESP AP. The app contains no input controls and never arms on open/reconnect.
4. Start `npm --prefix controller/node-js/maxbot start` with the private `MAXBOT_PAIRING_KEY`; the server binds the LAN and advertises only nonce-authenticated AP-local discovery. The default ESP address is 192.168.4.1. Use the Mac's localhost console to pair. Browser disappearance expires manual intent even while Node remains alive.
5. Run the read-only `python3 tools/maxbot/probe.py` over Wi-Fi to verify signed boot, actuator and runtime source identity. A successful probe does **not** verify stop response. Exercise the direct stop with the phone unavailable, confirm the **applied** acknowledgement, then explicitly reset from the stopped phone workflow; reset does not arm.
6. Under operator supervision, verify manual direction, steering table, zero/brake behavior and expiry. Preserve logical ±255 → physical ±170 duty, disabled battery monitoring and absent physical switch. Independently verify floor stopping distance; electrical brake confirmation is not that measurement.

## Wi-Fi connection troubleshooting

Both Mac and Pixel must stay on the ESP access point even when Android/macOS reports no internet. The console requires its private operator pairing as well as the running MaxBot phone app. The phone discovers the dashboard's current AP address before falling back to its configured URL. Joining or changing Wi-Fi rebuilds the vehicle socket on the next handshake, clears queued commands and clock/timing state, and never restores motion authority. A failed send closes the socket so the next attempt can recover. Handshake failures now include the exception type and message on the phone and log details under `MaxBotVehicle`.

The current phone interface shows status text; its ARCore preview surface is hidden. View camera video on the paired Mac console. Camera permissions and Google Play Services for AR must be available independently of the vehicle handshake. A missing preview alone does not identify a camera failure; read the camera status line.

After the Wi-Fi recovery changes, Android assemble/lint and all 2,935 core checks pass. Pixel reconnect and video remain unverified without a connected device. For USB diagnosis, use a data-capable cable, enable Developer options → USB debugging, unlock the Pixel and accept the Mac's debugging authorization. A Pixel need not appear in Finder to support ADB.

## Manual WASD control

After **Arm manual commissioning** is acknowledged, use W/S for forward/brake/reverse and A/D for left/right, or press and hold the corresponding on-screen direction buttons. No separate hold button is required. Drive ramps from zero to full in 1 second; steering reaches full lock from center in 0.4 seconds. Releasing an axis returns its request toward zero at the same rate. Opposite keys move the signed request through zero; holding both opposing keys returns it to neutral. The existing phone/firmware reverse pause and measured-speed braking checks still apply, so a requested reverse ramp does not override those interlocks. Readouts show requested PWM/angle rather than measured braking force.

Edit **Drive ramp** (default 100 %/second) and **Steering ramp** (default 250 %/second) directly on the Mac. Allowed values are 1–1000; time from zero to full is 100 divided by the rate. Values apply immediately and reset to defaults on page reload. Focusing an editable field clears input; keyboard control resumes with a fresh key press after leaving the field. Window focus/visibility loss, disconnect, disarm, stale telemetry or a control-loop pause over 100 ms immediately clear commands. Key repeat cannot restart cleared input. Session/event/deadline checks, the phone control loop and firmware watchdog remain in force. No phone update or ESP reflash is needed for this dashboard change. All 24 Node tests pass; isolated Chrome checks at 1440 px and 390 px pass without overflow or JavaScript exceptions. Browser-to-WebSocket-to-Hub forwarding was verified against an isolated test robot for full drive/steering ramp, mouse-held direction input, release decay, edit-focus zero and window-blur stop. The known manual-arming instruction is shown as informational after confirmed arming; actual faults retain their red status. This does not claim physical response verification.

The **Control delivery** readout distinguishes browser requests from phone-requested PWM/angle and reports the phone link, ESP clock uncertainty and cumulative MCU deadline rejects. **Save control report** downloads the most recent 1,000 browser/phone samples using an explicit field allowlist; it excludes private pairing configuration and camera frames. It does not claim motor application acknowledgement. When diagnosing no motion, record a supervised input attempt and stop/disarm before sharing the report. No physical cause has been confirmed without live device evidence.

## Measurement and artifact workflow

- Phone-side geometry can be updated without an APK reinstall or ESP flash: stage a `geometry` artifact matching the existing actuator ID, using `maxbot/config/profiles/geometry.v1.example.json` as a template. Unknown fields remain null. Geometry changes reset the anchor and invalidate map/reference; scout again. Keep the geometry artifact in each subsequent complete set. Hardware actuator edits remain a matching firmware/APK provisioning boundary.
- Export the normal rear-camera capability report and mounted launch/close-pass CPU frames. Confirm lens, crop/orientation, intrinsics, timestamp source and mount extrinsics. Enter measured stream ID, sensor-to-car rotation/bias/lever arm and empirical state bounds into a reviewed geometry artifact or private calibration; never fill unknowns with the fixture's identity matrices.
- Supply real one-class cone weights plus a manifest. Baseline: YOLOv5n 320 `[1,6300,6]` with normalized XYWH/objectness. Challenger: YOLO26n 320 only when the actual export matches the manifest's end-to-end XYXY/score/class contract. Inspect exported operators and delegate compatibility; the family name never determines an assumed layout. GPU initialization/warm/invoke/dispose stays on the inference worker. No weights are bundled.
- Collect manual measurements of speed/PWM, steering rate/lag, acceleration, braking, grip and estimator delay. Review a dynamics artifact with provenance and valid ranges. No automated fitter or parameter-search training is included.
- Provide measured floor bounds and finish area in app settings. Build canonical sets with `tools/maxbot/artifacts.py --revision N --output DIR kind:path ...`. Upload all files named in `DIR/set.json`, review the staged revision and activate while stopped/disarmed. A model file named `cone.tflite` needs its matching manifest named `cone.tflite.json`. A planned reference also binds the active policy file hash through `planningParametersId`; changing policy parameters requires planning again. Each revision is a complete active set; omitted model/reference components become unavailable. Settings/policy edits and supported model updates need no APK reinstall.
- Scout on one side at the conservative measured limits. Stop/disarm, review independent scene error and multiple measured views, freeze the map, then use **Plan frozen course**. Failed searches expose diagnostics without modifying the active reference. Save the reference and include it with the matching active dynamics/model/policy/settings set. The phone validates it again. Reposition without restarting ARCore, verify original launch pose/heading with fresh landmarks, arm explicitly, then request the timed run. Finish crossing and braking completion are distinct.
- Benchmark with video off/on for at least 15 minutes after warm-up, under thermal and packet pressure. Export runs over Wi-Fi while stopped; completed Mac files appear in `.tools/operator-exports/*.zip`, interrupted files end in `.part`. Preserve all failed/aborted attempts. Record contact review explicitly. `tools/maxbot/benchmark.py EVENTS --output REPORT` reports measured causal percentiles/loss and keeps absent capture/physical timings null.

## Required evidence still outstanding

No authorized Android device appeared in `adb devices -l` during implementation. Hardware measurements, real weights and physical tests remain dependencies, regardless of host test success:

- Actual 200 Hz schedule, overruns, output application deadlines and STOP under sustained network/serial pressure; signed stop acknowledgement confirms commanded outputs, not physical rest.
- Validated exposure-to-phone clock mapping, clock drift/uncertainty and reset behavior on the Pixel/ESP pair. Capture→PWM p95 <100 ms remains unproven. Image-available→PWM is a separate metric.
- Mounted camera coverage and independent metric cone-center error; cone flange footprint; camera/IMU extrinsics and empirical localization bounds.
- Loaded steering response, speed/PWM, acceleration, braking, grip, VIO lag, clear hallway bounds and finish stopping area.
- Real-model tensor/operator/delegate compatibility, recall/base accuracy and at least 15-minute thermal/video-off/on full-load performance.
- Phone failure/direct stop, inference failure/manual path, browser disappearance, real AP discovery/reconnect/update interruption and rollback on the device.
- Feasibility of the actual four-cone course with 150 mm clearance, clean ordered passage, whole-body finish, stopping tail and operator contact review.

Task 2 training, automated dynamics fitting, closed-loop simulator, stochastic/Bayesian parameter search and learned residuals remain deferred.

## Capability checklist and verification result

| Capability | Implemented | Host-tested | Device-tested | Physically validated |
|---|---|---|---|---|
| Contracts, calibration preservation, course migration | Yes | Yes: golden vectors, schema/config and geometry fixtures | No | No new physical claims |
| ESP motor deadlines, epochs and independent stop | Yes | Shared C++ logic, Node role tests and real ESP compilation | No | No |
| Independent manual/control paths and source expiry | Yes | Core delayed-worker/deadline tests; Android build | No | No |
| Clock mapping, causal timing and pot response analysis | Yes | Deterministic clock/trace/steering-step fixtures | No | No |
| ARCore lifecycle, stream binding and raw IMU recording | Yes | Build plus transform/prediction fixtures | No | No |
| Independent native image preprocessing and detector adapters | Yes | Stride/letterbox/quantization/decoder fixtures | No | No real-model accuracy evidence |
| One-sided scout, measured-map freeze and Mac planning | Yes | Occluded-launch fixture, actual planner smoke, continuous validators | No | Actual 700 mm course feasibility unknown |
| Reference tracking, ordered passage and finish/braking separation | Yes | Shared Python/Java reference in rotated frame; passage fixtures | No | No |
| Artifact staging, warm/commit/rollback and geometry revisions | Yes | Schema/package/hash checks; Android transaction code compiled | No: Android storage/model interruption tests pending | No |
| Dashboard manual, direct stop, planning and export | Yes | 11 Node tests and focused desktop/narrow Chrome checks | No real AP/phone integration | No |
| Recording and benchmark export/reporting | Yes | Causal summary, export integrity and loss fixtures | No sustained recording benchmark | No |
| APK and configured runtime firmware | Yes, private bundle prepared | Build/lint and binary identity checks | Not installed/flashed | No |

Final verification: **2,935 Java core assertions**, **26 Python tests passed** in system Python, and the optional numerical MPC test **also passed** in the existing `.tools/training` environment. All **11 Node tests** passed. Calibration/shared deadline tests and independent image JNI tests passed. Retained MPC exercised 46 host path samples and stayed behind the reference/launch gates. Android assemble/lint/core tasks passed with **0 errors and 10 nonfatal lint warnings** (dependency availability, fixed Pixel-oriented ABI/orientation and status text internationalization). ESP compilation passed for the classic ESP32 target: **957,635 flash bytes / 46,588 global RAM bytes**.

Desktop 1440 px and narrow 390 px Chrome checks found no horizontal overflow or JavaScript exceptions. The independently reviewed keyboard deadman and artifact serialization findings were both scored resolved; that verdict applies to those fixes. The Impeccable engine/detector was unavailable, so project context, captures and the independent review supplied evidence instead. DESIGN.md tokens were preserved. Source explorer checks pass for **227 routes / 210 source links** at both layouts.

[Machine-readable host report](benchmarks/task1-host-report.json) contains actual host results and null device measurements. Potentiometer response definitions are explicit: at least a 7° applied target step, 1° onset, settling within 3.5° for 100 ms; telemetry gaps over 60 ms censor the estimate. These definitions do not establish steering dynamics until measured. Traction physical latency remains unavailable. Real-cone evaluation provenance must explicitly approve a model before live autonomy; tensor compatibility/warm-up alone never does.


## Command acknowledgement repair (Android 0.3.1)

The downloaded `maxbot-control-report.json` confirmed receipt of browser input by the phone (42 MANUAL samples, requested PWM up to 255), repeated manual deadline expiry and ESP clock uncertainty from 4.044 to 38.752 ms. The MCU rejection counter remained 11 throughout; that count does not establish new MCU rejection during the attempt. The report had no recorded causal application time.

A code-level defect explains the motion takeover stall: firmware `applied`/`local_applied` messages carry `applied_ms` rather than `time_ms`, but VehicleLink previously read `time_ms` before checking message type. This discarded signed application acknowledgements and prevented the applied-zero takeover gate from completing. Android 0.3.1 scopes `time_ms` to hello/telemetry only. HMAC, boot/session identity, deadline, and zero-ack requirements are retained. The APK must be updated on the Pixel; no ESP reflash is required for this fix. Clock uncertainty above 20 ms and expired browser intents still stop motion and remain separate observed issues.

Regression tests exercise the real VehicleLink receive method with firmware-shaped replies lacking `time_ms`, including invalid signatures/boot/session and still-required hello/telemetry timestamps. New phone telemetry exposes the last sent mode, blocking gate, authenticated applied sequence/age/PWM, and MCU-reported PWM. These describe software command application, not physical motor voltage or movement. Physical verification is pending installation on the Pixel.

Validation: original-parser regression run failed the valid-ack cases as expected; the fixed parser passes all four Android unit tests. Android assemble/lint and 2,935 core checks pass; all 24 Node tests and isolated browser control checks pass. The final private APK is also available as `dist/task1-private/maxbot-0.3.1-debug.apk`. Installation and physical verification remain pending.


## STOP/arm deadline lifecycle repair

`halt()` previously set motor PWM to zero without revoking the previous motion command's absolute deadline. A subsequent STOP, DISARM, RESET or rearm could therefore carry an obsolete motion deadline into the idle/armed state and trigger `ABSOLUTE_EXPIRY`. The firmware now clears that deadline whenever outputs are halted and whenever ARM/ARM_MANUAL explicitly sets drive PWM to zero. Active DRIVE/DRIVE_MANUAL commands still install their original bounded deadlines; expiry and watchdog enforcement are retained. ARM still cannot clear an existing fault. For an existing expiry fault, stop/disarm, use **Reset stop**, then arm explicitly.

`tools/maxbot/test-firmware-stop.py` compiles the real `halt` and `applyCommand` bodies with host actuator/queue stubs. The old firmware fails its STOP/deadline assertion; the fix passes STOP→DISARM→ARM, zero-output ARM during motion, active-command expiry, fault/reset and expired-motion rejection checks. Hardware actuation is not simulated by that test. This change requires an ESP reflash, while keeping the actuator calibration and phone 0.3.1 pairing unchanged.

Flashed the STOP/arm lifecycle repair to the connected classic ESP32 over `/dev/cu.SLAB_USBtoUART`; flash hashes verified. Authenticated firmware identity `535b4457ba0dfa3960e2f1942d9a861f3be038699da77e475020276d338b3ab1` and unchanged actuator identity `210b9c6c3489ffac1a75e66182cf207d60b6042937fac9dc7cd534210accd687` were verified over serial. All 150 samples showed disarmed/manual-disarmed, zero PWM, no fault and zero deadline rejects/overruns/application loss. No arming or motion was sent; physical movement and live packet-timing behavior remain unverified.


## Latency mitigation (Android 0.3.2)

The second downloaded report (`maxbot-control-report (1).json`) shows 20 ms browser command spacing (maximum 21 ms), real nonzero ESP-reported PWM up to 39, then `ABSOLUTE_EXPIRY` after roughly 350 ms of browser input. The MCU rejection counter increased from 55 to 56; it did not show a sustained rejection flood. Clock uncertainty ranged from 3.006 to 34.202 ms over the complete report. The report confirms a real motion-deadline failure but does not isolate radio power saving, packet jitter or phone scheduling as its sole cause.

Android 0.3.2 requests a foreground low-latency Wi-Fi lock (released on pause/destroy), moves large status/camera/map/UI publication onto a separate 100 ms worker, and probes both clocks every 500 ms. Clock sync retains a better recent uncertainty bound instead of replacing it with a slower RTT sample; retained samples continue aging and cannot extend the 5-second validity. Reset/discontinuity detection, 100 ms command age and 20 ms ESP clock uncertainty limits are preserved. New reports include Wi-Fi lock status, maximum control-loop gap, Mac clock uncertainty and remaining manual-intent budget at phone receipt. These changes target observed latency risks; physical resolution remains unverified until the new APK is installed and retested.

Android Wi-Fi lock behavior and foreground/screen-on requirements: https://developer.android.com/reference/android/net/wifi/WifiManager#WIFI_MODE_FULL_LOW_LATENCY . The existing app keeps the display awake while active; the lock is not requested for USB transport.

0.3.2 validation: Android assemble/lint and four acknowledgement unit tests pass; the core suite passes 2,951 checks including clock-sample selection/expiry/discontinuity cases. Pixel was not available through ADB, so the private versioned APK is prepared but not installed. No firmware reflash is needed for the Android latency changes.

## Consolidated manual-control audit (Android 0.3.3)

See [manual-control-audit.md](manual-control-audit.md) for the confirmed defects, fixes, test evidence and physical limits. The versioned private APK is `dist/task1-private/maxbot-0.3.3-debug.apk`. Validation passes: 16 Android unit tests, 2,955 core checks, 26 Node tests, isolated desktop/mobile browser checks and firmware deadline/calibration tests. This release includes the 0.3.2 latency changes and does not require another ESP reflash if the prior STOP/arm repair is installed. USB debugging is unavailable; installation and a sustained physical run remain pending.

## Operator-confirmed working checkpoint

On 14 November 2026 (Asia/Dubai), the operator confirmed manual control 0.4.0 works. The source recovery tag is `manual-control-v0.4.0-working`; the private local artifact/configuration snapshot is `dist/checkpoints/manual-control-v0.4.0-working/`. See the [checkpoint record](checkpoints/manual-control-v0.4.0-working.md). This supersedes the installation-pending status for manual 0.4.0 above; it does not commission autonomy or certify measured stopping performance.
