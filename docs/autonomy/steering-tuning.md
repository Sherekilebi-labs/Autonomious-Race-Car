# Loaded steering tuning

See [target state and feedback acceptance](steering-feedback-fix.md) for the 0.6.1 correction to inactive targets and out-of-range feedback.

The Mac console now has **Steering PID tuning** beneath the manual controls. Settings travel directly from the authenticated Mac console to ESP32 port 4245. The Pixel can stay on Wi-Fi; this feature does not need another APK.

**Read from ESP32** loads the actual active gains. Editing the fields does not change the car. **Apply** changes RAM settings for testing. **Save to ESP32** applies and stores those values in NVS flash, verifies the record by reading it back, and acknowledges completion. Apply and Save require disarmed, zero-output state; Read and the live chart work while steering. Both the host and ESP enforce these conditions. The motor task inhibits arming during a configuration update/flash write.

Saved records contain a version, checksum and actuator-calibration identity. They load at boot only when all three match. Invalid or incompatible records fall back to the firmware's calibrated defaults. No credentials are stored in this tuning record. Normal app-only firmware upload preserves NVS; erasing the chip or writing a full factory image can erase it.

## Why increasing P alone is unlikely to fix this

The previous controller used a 3.5° deadband. Small tracking errors produced no steering effort until the error crossed that boundary, which can look like lag followed by a twitch. It also differentiated error using a fixed 5 ms interval, so changing the target or noisy potentiometer feedback could generate large derivative pulses. P = 50 already requests 250 logical PWM for a 5° error, close to the 255 ceiling.

The runtime now uses actual elapsed time, filtered derivative on measured angle, conditional integration with a bounded integral contribution, a smaller configurable deadband with settle/restart hysteresis, and optional minimum steering effort to overcome loaded friction. Target changes do not generate derivative kick. Stall detection accepts smaller measured progress for small tracking errors instead of always demanding 1° of movement. Steering end stops, blocked-motor detection, motion deadlines, and the existing motor-voltage ceiling remain active.

## Starting point and procedure

**Load starting values** fills these fields; it does not apply them automatically:

| Parameter | Starting value | Purpose |
| --- | --- | --- |
| P | 50 PWM/degree | Correct position error |
| I | 0 PWM/(degree·second) | Start without integral windup |
| D | 0.15 PWM·second/degree | Damp measured wheel motion |
| Deadband | 0.75° | Settle near the target; restart above 1.125° |
| Minimum effort | 0 logical PWM | Measure loaded breakaway effort before adding compensation |
| Maximum effort | 255 logical PWM | Existing configured maximum |
| D filter | 12 Hz | Reduce differentiated ADC noise |

These are starting values, not a measured final tune for this car.

1. Put the car on the intended floor with its normal battery and phone weight. Stop/disarm, load the starting values and Apply. Arm manual control for each test; stop/disarm before changing settings.
2. Use short A/D taps and gentle ramps near center, in both directions. Lower the steering ramp rate temporarily if needed. Watch the target/actual chart, error, steering PWM and P/I/D contributions. Test continuously changing targets, not only large position steps.
3. If small errors produce effort but the loaded wheels do not move, increase **Minimum effort** by 10 PWM per test until small corrections move reliably in both directions. Use the smallest effective value. Do not use the traction motor's 200-PWM threshold as an assumed steering threshold.
4. If it oscillates or chatters, reduce minimum effort or P first. For smooth overshoot, add D in small increments (for example 0.05). If D changes sign sharply while the wheels are nearly still, address ADC noise or reduce the filter cutoff/D gain rather than adding more P.
5. If it follows smoothly but lags while rolling, increase P in roughly 10–20% steps and retest. If steering PWM is already at its maximum, more gain cannot add torque: inspect supply voltage under load, the bridge, linkage friction and the mechanical load. Use a small I only for a repeatable residual error after friction compensation/P tuning; excessive I can produce slow hunting. The [WPILib PID guidance](https://docs.wpilib.org/en/stable/docs/software/advanced-controls/controllers/pidcontroller.html) describes the same integral-windup limitation.
6. Retest while rolling slowly, then at the intended operating speed. Confirm small corrections, reversals and return toward center. Save to ESP32 once the tune works under those conditions. Check the **saved** indicator after a restart. Re-measure steering dynamics before relying on an old autonomous dynamics model.

Logical PWM is still mapped through the existing voltage ceiling: ±255 logical corresponds to ±170 hardware duty on this car. Changing PID limits does not raise that physical ceiling. The displayed steering PWM is the controller's logical command, not a motor-terminal voltage measurement.

## Traction breakaway behavior

W and S now start at ±200 logical PWM, then ramp toward ±255 at the configured rate. Releasing a key decays at that rate; as soon as its magnitude would drop below 200, the request becomes zero. Opposite input reduces the current direction to neutral before the reverse breakaway jump. The phone's existing neutral reversal pause still applies. At the default 100% full-scale/second rate, 200 → 255 takes about 0.216 s. Steering ramp behavior is unchanged.

## Verification

Software coverage includes no target derivative kick, filtered feedback, small-angle response, friction floor, deadband hysteresis, saturation/anti-windup, corrupt/mismatched persistent records, authenticated integer settings transport, disarmed writes, apply/save acknowledgement, and all browser nonzero drive requests staying at or above 200 PWM. The browser fixture exercises real WebSockets and WebRTC with synthetic peers and keeps 640×480 near 30 fps.

`tools/maxbot/verify_steering_storage.py --port /dev/cu.SLAB_USBtoUART --save-current` saves the currently active values unchanged, performs a hardware reset, and verifies a fresh authenticated read of the saved record. It never arms or sends motion. The current hardware check also verifies temporary Apply, repeated settings reads without reset, and restoration of the saved values on reset. Native loaded-plant simulations and browser fixtures are software checks, not evidence of a final physical tune.

Hardware verification on 14 November 2026: the ESP32 was flashed and its signed firmware source identity `3604aa28cef51041ed62c91e089b932084020e357dbe6e12fe2ebd0708a91b3e` was verified. Temporary Apply, repeated authenticated reads, NVS Save/readback and restoration after hardware reset passed with both motor commands at zero. The saved original gains survived the final firmware upload. The starting preset was not applied automatically. Floor-loaded driving remains an operator test; the Mac stayed on its current network, so the actual Wi-Fi settings path was not exercised in this session.
