# Task 1 deployment readiness

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
