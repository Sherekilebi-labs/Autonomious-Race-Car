# Manual-control audit — Android 0.3.3

This is the historical 0.3.3 audit. **[Manual control 0.4.0](manual-control.md)** supersedes its manual timing and takeover restrictions.

This audit follows the current WASD/on-screen controls through the Mac WebSocket hub, Pixel supervisor/UDP sender, signed ESP acknowledgements and firmware motion deadline. It keeps the 100 ms motion-intent limit, 20 ms ESP clock uncertainty limit, calibrated steering, logical ±255 → physical ±170 PWM, and explicit stop/reset/arm workflow.

## Confirmed defects and corrections

| Finding | Correction | Evidence |
| --- | --- | --- |
| Firmware acknowledgements lack `time_ms`; the old phone parser discarded them before checking message type. | Read timestamps per message type. | Real receive-path tests accept signed `applied`/`local_applied` without that field and reject bad signatures/session/boot. Included since 0.3.1. |
| STOP left the previous motion lease active. | Clear expiry on zero-output halt/arm. | Real firmware function bodies fail the old STOP assertion and pass stop/disarm/rearm/reset/expiry tests. Firmware `535b4457…` was flashed and authenticated earlier. |
| Large JSON/status publication ran on the control scheduler. | Separate 100 ms status worker; foreground low-latency Wi-Fi lock and bounded clock filtering. | Android 0.3.2 changes retained. Core tests cover selected-bound aging, discontinuity and expiry. |
| Recorder JSON copying/serialization still ran under the vehicle transport monitor. | Enqueue owned immutable event objects; materialize on the background writer, with identity captured at enqueue. | An intentionally blocked serializer cannot block subsequent control-event enqueue; queued events retain the correct identity. |
| Manual arm was deferred onto the UI thread and could execute after a later Stop. | Handle arming on the ordered command callback; only presentation uses the UI thread. | Code-path review plus ordered transport/stop tests. |
| Reset could be sent while armed and silently rejected; pending arm/old motion could survive special commands. | Reset queues DISARM then RESET. Stop/reset/arm advance the local input cutoff; stale output is rejected at enqueue and transmit. Safety commands drop pending arm work. | Real VehicleLink loopback UDP tests exercise queue order and an old output racing after Stop. |
| A previous takeover state could be reused after Stop/rearm. | A fresh motion phase must receive a new applied-zero acknowledgement. | Old acknowledgement cannot unlock a later epoch; valid zero acknowledgement unlocks real signed motion serialization. |
| Near-expired motion could be clamped into a new one-millisecond lease. | Send zero when the conservative remaining budget is insufficient. | Test first enables real motion, then supplies a two-millisecond remaining intent and verifies STOP rather than motion. |
| The sender waited for a new producer output even when the last manual lease was about to expire. | Best-effort early STOP if the producer stalls; it never refreshes propulsion. | Deterministic transport test verifies zero is sent before the old lease expires. ESP expiry remains active if this STOP is lost or late. |
| WebSocket close/authentication stalls could leave the phone logically connected or prevent reconnect. | Revoke authority on closing, ignore old-socket callbacks, cancel uncompleted authentication after five seconds. | Three lifecycle/deadline tests run against actual DashboardLink callbacks. |
| Sending with no open transport looked successful. | Throw/report transport failure. | No `command_sent` event is emitted without a socket/serial transport. |
| A registered phone that never emitted telemetry could occupy the server role indefinitely. | Start registration liveness at authentication and expire it after 1.5 seconds. | Hub test verifies expiry and replacement registration. |
| Fast clock discontinuities bypassed the old >500 ms drift check. | Check the bounded offset envelope at every later sample. | Core tests reject a clock step between closely spaced probes and revoke the map. |
| A retry could hide an actual MCU fault behind a generic “arm first” message. | Preserve the MCU fault; show link/fault blocking state on the dashboard and keep Reset available. | Core and dashboard tests cover `ABSOLUTE_EXPIRY`, missing link, disabled motion, and enabled reset. |

## Verification and limits

- Android assembly/lint: passed, zero lint errors; 16 unit tests passed.
- Core: 2,955 checks passed.
- Real Java UDP sender: 1,500 fresh inputs over 30 simulated seconds, signed acknowledgement handling, original-deadline preservation, stop/rearm and clock rejection passed. This uses localhost and an injected clock, not the Pixel radio.
- Dashboard: all 26 Node tests passed. Isolated Chrome checks at 1440 px and 390 px passed without horizontal overflow or JavaScript exceptions; keyboard and mouse commands crossed the actual WebSocket hub to an isolated test robot.
- Firmware calibration/deadline/stop lifecycle host checks passed. Firmware was not changed again for 0.3.3.

The latest physical report proved brief nonzero MCU output followed by a real expiry, with steady 20 ms browser input. It did not uniquely identify the radio or scheduling delay. Android 0.3.3 includes the preceding low-latency changes and adds the above transport fixes, but is not physically certified by host tests.

Install `dist/task1-private/maxbot-0.3.3-debug.apk` over the existing MaxBot app, refresh the Mac console, then Stop/disarm → Reset stop → Arm manual commissioning. Use raised wheels for the first sustained forward/release/reverse/steering check. Export a control report if a fault returns. No further ESP reflash is required when the earlier STOP/arm repair is installed. USB debugging is not required for this APK/report workflow.
