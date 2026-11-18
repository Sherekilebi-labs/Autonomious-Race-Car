# MaxBot USB migration: implementation and bench evidence

18 November 2026. This is a **Mac ↔ ESP32, disarmed USB bench**. It is not a Pixel benchmark, floor test, motor-onset measurement or autonomous-driving validation.

## Changes completed

- MaxBot 0.7 APK: Pixel USB serial, explicit shared-Wi-Fi dashboard address, separate vehicle/dashboard status, bounded local experiment runner, signed session messages and trial-input display witness.
- Mac: attended experiment dashboard, one local MCP coordinator, automatic trace/report bundles, original observer uploads, stopped onboard ZIP export for warning/slalom sessions and background analysis.
- Observer Android APK: foreground camera, recording-mode capabilities, original MP4, sensor/callback timestamps and encoded presentation timestamps, remote recording, hash-checked uploads and snapshots.
- Calibration/training: stationary IMU proposals, reviewed optical motion extraction, held-out dynamics fitting, bounded slalom parameter proposals, reviewed detector training/export, preserved locked test split and stopped artifact staging.
- Course: four 135 mm × 300 mm bright-orange cones; 0.5–1 m center spacing; white floor; good indoor lighting; 2 m × 10 m walled corridor. Actual placement and floor-frame bounds remain measured inputs.

## Firmware findings and corrections

The old firmware emitted application acknowledgements only on UDP, despite accepting serial commands and providing serial telemetry. USB acknowledgements now use the same signed protocol. Serial baud is 921600 in the matching firmware/APK configuration.

An initial sustained test obtained **71/150** acknowledgements. Increasing the RX buffer alone did not make sustained traffic reliable. The final firmware additionally batches serial replies into a single write, enables a 2048-byte TX buffer, and drains up to 1024 input bytes per protocol loop. RX buffering is 4096 bytes. Motor deadlines, arming, power mapping and steering limits remain unchanged. The combined intervention passed the test below; this does not isolate the contribution of each buffering/serialization change.

Firmware source ID: `2612aa1b9a0e5ea6d6e66ae348c7484b2aad63a76f3cb48c69efc5f662b11f5d`. Binary SHA-256: `6e5952ed00e02289cce81f4f5b1a372178120f7c145e750291bf42ba6a53ece5`. Actuator configuration ID: `456fa21052556ba8a74aac1e2944117489ba12e71edbe3cf1818357f0f39b98a`. Flash-tool hashes verified. The serial-baud change changes the actuator binding; the exact saved steering settings were explicitly preserved/rebound, then remained valid across subsequent flashes.

## Measured results

| Measurement | Result |
|---|---:|
| UART configuration | 921600 baud, 8N1 |
| Signed settings reads | 150 |
| Disarm commands sent / signed application replies verified | 150 / 150 |
| Achieved command send rate | 49.922 Hz |
| Verified telemetry samples during load | 153 |
| Application-loss counter increase | 0 |
| Motor-overrun counter increase | 0 |
| Command-send → signed application-reply RTT, median / p95 | 26.092 / 28.338 ms |
| Settings-request RTT, median / p95 | 20.670 / 20.923 ms |
| Outputs | Disarmed; traction 0; steering 0 |

No ARM, DRIVE or RESET command was sent by these runtime bench probes. Flashing necessarily reset the MCU. Settings-save operations preserved the pre-flash values: P=50, I=3, D=1.3, deadband=3.5°, minimum=0, maximum=255, derivative filter=12 Hz. Signed reads after subsequent resets confirmed saved=true and valid steering feedback.

## Calculations and limits

UART 8N1 uses approximately 10 transmitted bits per payload byte. Payload capacity is therefore `baud / 10`.

Largest observed zero-output packets: telemetry 458 bytes, application 274 bytes, output-written 223 bytes. One of each per 20 ms gives:

`(955 bytes/cycle) × 50 cycles/s = 47750 bytes/s = 477500 line bits/s`.

At 460800 baud this is `103.6%` of theoretical capacity; at 921600 it is `51.8%`. Hello/settings traffic and longer driving values add overhead. This is a budget calculation using the observed packet sizes, not a worst-case guarantee.

The measured request/reply RTT contains Mac scheduling/USB-driver buffering, outbound UART time, ESP receive/parsing/authentication, the motor-task application point, reply serialization, return UART/USB time and host reading. It does **not** identify each stage separately. A signed application reply precedes or differs from actual PWM-write evidence; neither establishes physical motor movement. Pixel USB has a different host/driver stack and needs its own recording.

## Verification and remaining work

Software validation: 48 Node tests, 32 Android unit tests, 2971 shared-core checks, 30 Python tests passed with one optional OSQP test skipped, both APK builds/lint, firmware host stop/deadline checks and real Chrome desktop/mobile simulation. Synthetic values from those tests are not hardware measurements.

MCP `maxbot` is registered in Codex; reload/restart the client to load tools. The dashboard launch/status/stop cycle was checked on a separate test port and stopped afterward. Neither phone was attached to ADB, so APK installation, Pixel USB permission/power/throughput, observer camera/encoder mode, enterprise LAN peer access and physical experiments remain pending.

Next: install the matching APKs; connect Pixel ↔ ESP by USB and both phones ↔ Mac over approved Wi-Fi; validate the disarmed camera-clock trial and failure stops; then collect attended floor starts, known-object warning trials, loaded steering and dynamics. Physical wheel/shaft onset and contact-free completion require suitable external evidence/review. Policy proposals are not automatically promoted.

Raw local bench records: `runs/bench/2026-11-18-usb-readonly.json`, `2026-11-18-usb-settings-restored.json`, `2026-11-18-usb-50hz-before-buffer.json`, and `2026-11-18-usb-50hz-load.json`. These remain private. See [setup, interfaces and report contracts](../usb-experiments.md) and [project status](../../PROJECT_STATUS_AND_ROADMAP.md).
