# Simple manual control — 0.4.0

Current follow-ups: [streaming and direct input](streaming-latency.md), [loaded steering tuning and the 200-PWM traction threshold](steering-tuning.md). The record below describes the frozen 0.4.0 recovery point.

Use the matching `dist/task1-private/maxbot-0.4.0-manual.apk` and current firmware. The ESP32 was flashed over USB and its signed firmware identity `19b9632ee8b0b3dcee73b448b6b6621dbc869adee551eae9322386bf3d9321ba` was verified. The actuator calibration identity remains `210b9c6c3489ffac1a75e66182cf207d60b6042937fac9dc7cd534210accd687`.

Join MaxBot-Drive on the Mac and Pixel, open MaxBot, and use the paired localhost console. Press **Arm manual control**, then use WASD or the on-screen direction buttons. Release keys to decay toward neutral; Stop/disarm and Emergency stop still request immediate zero. Focus loss clears input and disarms. The drive and steering ramp fields retain their 1 second and 0.4 second defaults.

## Manual behavior

| Property | Manual | Autonomous DRIVE |
| --- | --- | --- |
| Original input/command freshness | Up to 300 ms | Up to 100 ms |
| MCU command watchdog | 500 ms while manually armed | 150 ms |
| ESP clock uncertainty bound | 75 ms | 20 ms |
| Extra applied-zero takeover handshake | Skipped after explicit manual arming | Required |
| Motion deadline expires | Neutral output, stays manually armed | Disarms with ABSOLUTE_EXPIRY |
| Fresh input after a short gap | Resumes while manually armed | Existing preflight applies |
| Camera/model/geometry readiness | Not required | Required |

The 500 ms watchdog measures loss of phone-to-ESP commands. It still disarms with WATCHDOG; stop/disarm then Reset stop before arming after a real fault. A brief manual input gap no longer requires Reset. No command is held indefinitely, and repeatedly transmitting the same intent cannot extend its original deadline. Manual commands older than 300 ms or beyond the clock bound still become zero. Authentication, boot/session/sequence checks, measured steering limits, reverse neutral pause, logical ±255 → physical ±170 duty, and emergency stop are retained.

Timing is defined consistently in the browser/Node `MANUAL_PROFILE`, Java `ManualControl`, and firmware deadline helpers. Autonomous timing fields in calibration remain separate and no longer block manual preflight.

## Verification

The actual firmware halt/apply/deadline bodies were tested for neutral-on-manual-expiry, fresh-input recovery without reset, 500 ms watchdog disarming, rejected expired input, and preserved autonomous expiry faults. Host packet tests verify 300 ms manual commands and strict 100 ms autonomous commands. Android transport tests verify immediate manual transmission without the zero-ack gate, jitter tolerance above 20 ms, original-deadline preservation, stale-command rejection and the separate autonomous gate. Core tests verify a 200 ms manual gap is tolerated and a 301 ms gap neutralizes. Browser tests verify 300 ms intents and the existing WASD ramps/focus-loss handling.

After flashing, 150 authenticated hardware telemetry samples showed disarmed/manual-disarmed, zero PWM, no fault and no deadline rejects/overruns/application loss. No arm or motion commands were sent during verification. The operator subsequently confirmed working manual control on 14 November 2026; host tests and that confirmation do not establish measured radio latency or stopping distance.

Final validation: 19 Android unit tests, 2,961 core checks, 27 Node tests and isolated browser checks pass. Firmware compilation/flash hashes and authenticated zero-output reboot verification pass. The operator subsequently confirmed this version works. The frozen recovery point is [manual-control-v0.4.0-working](checkpoints/manual-control-v0.4.0-working.md).

Working checkpoint saved after operator confirmation: **`manual-control-v0.4.0-working`**. See the [checkpoint record](checkpoints/manual-control-v0.4.0-working.md) for exact identities, artifact hashes and recovery notes.
