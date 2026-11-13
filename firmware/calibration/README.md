# MaxBot bench calibration

Open **calibration.ino in this folder** as a separate Arduino sketch. Flashing this replaces the driving firmware temporarily. Do not copy it into `firmware/maxbot/`: Arduino would combine both `setup()`/`loop()` functions.

Run `python3 tools/maxbot/configure.py` from the repository root before compiling. It generates the same motor-voltage mapping used by the runtime: on the current 3S profile, command ±255 maps to physical duty ±170 for both motors. Pulse/PID fields and recorded starting thresholds stay on the logical 0–255 scale; live applied-bridge PWM is the mapped physical value. The verified both-high brake test remains steady HIGH/HIGH. Do not flash an older unscaled calibration build on the 3S setup.

## Saved calibration

[calibration.json](calibration.json) contains the supplied hardware/PWM/PID settings. Its steering table uses the **seven points from the supplied photo**, replacing the three older points in the pasted JSON:

| Wheel angle (°) | ADC |
| --- | --- |
| -26 | 1158 |
| -16 | 975 |
| -7 | 849 |
| 0 | 785 |
| 10 | 688 |
| 16 | 608 |
| 26 | 461 |

Angle is interpolated linearly between adjacent points in this descending ADC table. The file preserves the supplied 0.059999999 s travel time, PID 50/3/1.3, 255 PWM cap, 3.5° deadband and per-direction starting PWM (steering 110, traction 120). Commissioning flags remain false and unwired battery/stop inputs remain null.

Run `python3 tools/maxbot/configure.py` from the repository root to apply this file automatically to the generated Android and driving-firmware configuration. Order of precedence is README baseline → saved calibration → private overrides. The saved file does not load itself into the bench sketch's RAM or change firmware already installed on the ESP32.

## Wiring

| Connection | Classic ESP32 GPIO |
| --- | --- |
| Traction TA6586 IN1 / IN2 | 4 / 16 |
| Steering TA6586 IN1 / IN2 | 22 / 23 |
| Steering potentiometer divider output | **35** |
| Battery divider / physical stop | Unspecified; select only after wiring |

**Move the feedback ADC wire from GPIO14 to GPIO35**, as agreed. GPIO14 is ADC2 and cannot provide reliable readings while Wi-Fi is running. GPIO35 is ADC1 and input-only. Keep the motor and ESP32 grounds common. IN1/IN2 above are physical bridge labels; their actual forward/left directions are measured in the UI.

The documented potentiometer is 4.79 kΩ, with a 1 kΩ series / 2 kΩ-to-ground divider on the wiper, supplied from 5 V. Verify the ADC-node voltage with a multimeter throughout the **working steering range**. At 11 dB attenuation Espressif lists an approximate 0.15–3.10 V measurable range for classic ESP32; attenuation is not overvoltage protection. The nominal unloaded divider ratio is 2/3, and the resistors also load the potentiometer, so resistance cannot be converted directly into a reliable wheel angle. Use the measured ADC table. [ADC reference](https://docs.espressif.com/projects/arduino-esp32/en/latest/api/adc.html), [Wi-Fi/ADC2 restriction](https://docs.espressif.com/projects/esp-idf/en/v4.4/esp32/api-reference/peripherals/adc.html).

The documented 168.5 mm wheelbase is recorded. ±25° front-wheel limits remain provisional until matched to ADC captures. The roughly 200° servo-shaft travel is **not** the front-wheel steering range. Mass (approximately 1.5 ±0.5 kg) and rear-axle-to-COM distance (approximately 60 ±5 mm) are noted in the hardware document; they are not enough to infer grip, braking or COM height.

## Flash and connect

Use ESP32 Dev Module, Arduino ESP32 **3.3.0** (also supports the 2.0.17 PWM API) and ArduinoJson **6.21.5**. This sketch does not include `calibration.generated.h` and needs no configured network or phone.

1. Disconnect motor power while flashing. Open Serial Monitor at **115200 baud**.
2. Raise all four wheels securely. Use a current-limited motor supply and keep a motor-power disconnect within reach.
3. Reset the ESP32. Join Wi-Fi **`MaxBot-Cal`**, password **`MaxBot2026`**, then open `http://192.168.4.1`. These bench-network credentials stay the same after reboot and are also printed in Serial Monitor. Join from the computer or phone; the network has no Internet access. Forget old `MaxBot-Cal-xxxx` networks if saved.
4. Open the address. Verify GPIO35 feedback changes sensibly and does not rail. Check the physical ADC voltage, motor supply and wheel clearance, then acknowledge the bench checks and enable outputs.

Only one Wi-Fi station is allowed. A page reload disables outputs and replaces the page's control token. Measurements remain in RAM across page reloads, but are lost on reset/power loss: download regularly. This is an HTTP bench tool on a password-protected local AP, not the runtime authenticated driving service.

## Calibration sequence

1. Use **PWM calibration · PID bypass** to test each H-bridge directly, with no PID involved. Adjust the numeric PWM field or slider, then pulse IN1/IN2 **away from mechanical stops**. Each click is bounded to 20–250 ms and permits PWM 1–255. The result beside the pulse button reports PWM and steering ADC movement. PWM 255 is full duty cycle; the motor supply determines voltage.
   To measure starting PWM, reduce it until you find the lowest value that reliably starts the motor. Confirm the observed movement and select **Record last pulse as starting PWM** for each direction. Steering requires at least eight ADC counts of movement; traction requires visual confirmation because there is no encoder. The four starting-PWM values are exported under `hardware.pwmCalibration` as measured metadata; they are not automatically imposed as a PID output floor.
2. Approach conservative working angles with short pulses. Measure the front wheels and capture negative-right, zero-center and positive-left positions. Five or more points are preferable. Do not force the mechanism through the full shaft travel or search for hard stops automatically.
3. Select **Derive steering polarity**. It requires a monotonic angle table and opposite ADC changes of at least eight counts from both input pulses. Changing the angle table or contradictory probe movement invalidates polarity; a low-PWM pulse that does not move the motor preserves an already confirmed direction. Once bounds and probe direction are known, outward pulses are stopped at the captured wheel-angle boundaries.
4. Pulse each traction input and **observe wheel rotation**. Select the input corresponding to forward driving and record it. With no encoder, traction direction cannot be inferred electrically. The exported `drivePolarity` is +1 for GPIO4-forward or −1 for GPIO16-forward.
5. Use **Steering PID tuning** after the angle table and polarity are ready. Kp, Ki and Kd accept finite nonnegative values without arbitrary upper ceilings. The output cap defaults to **255**. The controller calculates raw demand, then rounds/clips the final signed output to the chosen cap and always to ±255 at the motor writer. The UI shows raw demand, clipped PID output, actual bridge PWM, P/I/D terms and saturation. These values distinguish insufficient command from output clipping or a stopped test.
   Select a **100–5000 ms** test duration; default is 3000 ms. Control continues through the whole test, coasting inside the deadband and correcting disturbances, rather than ending at the first target crossing. Derivative uses measured angle with a 20 ms low-pass filter; actual elapsed time is used, and conditional integration prevents windup. **Stop PID test** ends a test without disarming the bench. Faults, supervision and measured boundaries still stop it. Measure and record full right-to-left travel time separately. Revalidate exported gains under the driving firmware before commissioning the car.
6. If installed, disable outputs and select unique battery ADC1 / active-low stop pins. Battery sensing needs its own pack-rated divider; **never connect 2S voltage directly to GPIO**. Test the physical stop. Capture two multimeter pack-voltage readings at different voltages, enter the actual divider ratio and thresholds, then fit the ADC model. Verify voltage across the operating range.
7. Both-high electrical braking is available only after explicitly confirming the TA6586 board truth table and current behavior. The calibration sketch otherwise stops by setting both inputs low (coast); that is not a guarantee of immediate wheel stopping. Test real stopping distance later with the driving firmware.
8. Download `maxbot-calibration.json`. It preserves partial ADC captures, records explicit measurements and leaves commissioning flags false. Invalid/incomplete tables remain locked in the runtime configuration.

Import without erasing existing Wi-Fi/pairing credentials:

```sh
python3 tools/maxbot/configure.py --import-calibration /path/to/maxbot-calibration.json
```

This merges measurements into ignored `maxbot.local.json`, then regenerates firmware and phone configuration. The README remains the editable public baseline; private measured overrides take precedence. Autonomous driving still requires its complete measurements and confirmations. The separate [manual commissioning profile](../../docs/manual-drive.md) permits direct supervised testing with the valid actuator calibration while leaving those confirmations unmodified; unverified braking selects coast. Battery monitoring is disabled and the physical stop is optional in the current configuration. Import matching `maxbot.runtime.json` on the phone after actuator changes. The steering PID maximum PWM can be set up to 255; pulse/PID deadlines, feedback limits and supervision stops still apply.

## Stop behavior and verification

The sticky red **STOP** button and Escape latch a stop. Reset clears it while leaving outputs disabled. Disabling outputs, hiding the page or losing focus cancels tests. A separate FreeRTOS task supervises motors at nominal 200 Hz; network processing does not own motor deadlines. Pulses expire at 250 ms maximum, PID tests at their selected deadline (maximum five seconds), and a missing browser heartbeat stops an active test after 350 ms maximum (plus task scheduling). A PID interval over 100 ms or invalid numeric computation stops the test. These are software timing targets, not measured physical stopping distances. A physical power disconnect remains independent of firmware.

Errors and completion messages appear **directly beside the button that initiated the action**. Input-validation errors, request timeouts and device rejections appear at that same control. Immediate HTTP responses report acceptance or rejection; a retained 16-entry event ring delivers asynchronous pulse/PID results through state polling, using the initiating action sequence to return them to the same control. If polling falls behind the retained history, affected pending controls show a local warning to check current state and retry if needed. Global fault and connection status also remain visible in the header. The bench readiness line reports ADC noise/range and initialization state rather than a generic enable failure.

No automated end-stop search, traction speed estimation or automatic motor-direction observation is performed. A compile and host-side logic tests cannot validate actual wiring, task timing, ADC noise, current limits or steering behavior. Those require the raised-wheel bench checks above.

```sh
sh tools/maxbot/test-calibration.sh
```

## Calibration page design

This bench surface extends the MaxBot console and [root design system](../../DESIGN.md): blue actions, light neutral background, existing text/divider/emergency colors, flat sections, 6px control corners, a 28px column gap, visible focus outlines and minimum 44px buttons. The sticky header keeps STOP / Escape available. Missing measurements and stale telemetry use explicit text, and stale telemetry disables motion and arming controls.

The ESP32 page uses system Arial to avoid font assets in flash or network font requests. Two columns stack at 850px; the live-feedback panel sticks beside the working sections on desktop. Per-button messages remain in normal document flow so a result requires no jump to the page top. Root tokens remain authoritative. Live ESP32/network behavior and physical controls still require bench verification.
