# What the calibration parameters mean

Distances are meters, speeds m/s, acceleration m/s², and wheel/camera angles degrees. `null` means not measured; it is not equivalent to zero.

## Power and wiring

**Current policy:** 3S LiPo, with `power.batteryMonitoring: false`. The operator checks the battery before every run, targeting approximately **12 V**. Both motors have a fixed 170/255 physical duty ceiling; there is no live-voltage compensation, reported battery percentage or automatic low-voltage cutoff. The manual target is not a sensor reading and is not a new arming gate. The ADC/divider/voltage-threshold fields below are optional and inactive in this mode; `commissioning.powerVerified` still concerns motor-driver and supply wiring.

| Parameter | Meaning / purpose |
|---|---|
| `power.batteryMonitoring` | Explicitly enables/disables voltage sensing and related arming/low-voltage checks. Current value is `false`. Legacy configurations without the field keep monitoring enabled. |
| `power.manualCheckTargetV` | Operator's pre-run target, now 12.0 V for 3S. Descriptive, not an automatically measured value. |
| `power.packCells`, `packMaxV`, `pwmReferenceMaxV` | Current values 3, 12.6 and 8.4. Generated drive and steering ceilings are `floor(255 × reference / packMaxV) = 170`. Logical commands remain ±255. PWM pulses still reach pack voltage. |
| `hardware.batteryPin` | Current value is `null`; generated firmware uses `-1` and does not read GPIO25. If monitoring is enabled later, choose a valid ADC1 divider input. |
| `power.senseSource` | Current value `disabled`. When monitoring is enabled, `battery` means the divider measures the pack before the buck. A regulated rail cannot provide pack voltage. |
| `power.dividerRatio` | Source voltage divided by GPIO voltage: `(Rupper + Rlower) / Rlower`. Your 1 kΩ upper / 2 kΩ lower circuit has ratio 1.5. |
| `power.adcReferenceMv` | Effective full-scale slope of this application's raw-ADC-to-pin-millivolts model, including attenuation. This is not simply the ESP32's nominal internal reference voltage. Fit it against multimeter readings. |
| `power.adcOffsetMv` | Additive correction in millivolts at the GPIO. Fit with the slope; do not invent a zero offset as a measurement. |
| `power.fullV` | Inactive 3S reference 12.6 V. Used for the percentage scale and compensation only when monitoring is enabled. |
| `power.warningV` | Inactive 3S warning reference 10.5 V. |
| `power.stopV` | Inactive 3S stop reference 9.0 V. Only enforced when monitoring is enabled. Pack voltage does not reveal an individual weak cell. |
| `hardware.physicalStopPin` | Optional active-low switch input. `null` means absent and does not block firmware arming. A specified pin must be valid and distinct from other pins. |
| `hardware.driveBrakeVerified` | Confirmation that the H-bridge's implemented brake state actually stops the drivetrain as intended. A checkbox cannot determine electrical behavior. |

When monitoring is enabled, the firmware model is:

`pack volts = (raw ADC / 4095 × adcReferenceMv + adcOffsetMv) × dividerRatio / 1000`

It is meaningful for pack sensing only when the divider is connected to the pack. The previous regulated 5 V/GPIO25 circuit cannot support pack percentage or the 8.4/7/6 V thresholds: the buck regulates its output, the nominal GPIO voltage was 3.33 V, and ADC2 conflicts with Wi-Fi. That connection is now inactive in runtime firmware; it is not a drive blocker in manual-check mode.

## Car, steering and camera

| Parameters | Why needed / how to obtain |
|---|---|
| `car.wheelbaseM` | Front-to-rear axle spacing; converts steering angle into path curvature. Latest Tracker label is 0.173 m, replacing 0.1685 m. |
| `car.frontOverhangM`, `rearOverhangM` | Distances from the respective axles to the frontmost/rearmost installed parts. Front is 0.03894 m; rear remains unconfirmed because electronics extend behind the chassis. |
| `car.widthM` | Width of the collision rectangle centered on the car centerline. Recorded as **0.144 m** after the user centered the phone in the fourth Tracker image, giving a 0.072 m half-width. For any future asymmetric mount, use twice the larger left/right reach from the centerline, including installed parts. |
| `car.trackWidthM` | Front tire-center spacing, 0.127 m from the third Tracker screenshot. Optional recorded geometry; it does not replace full collision width and is not needed by the current bicycle model. |
| `steering.adc`, `degrees`, `leftDeg`, `rightDeg` | Measured ADC-to-wheel-angle map and operating endpoints. Already saved from your seven-point photo. |
| `steering.travelTimeS` | Measured steering response time; limits how fast a planned trajectory can change steering. Recorded value is about 0.06 s; confirm its measurement. |
| `steering.kp`, `ki`, `kd` | Feedback gains: present error, accumulated error and rate of change. Runtime and bench PID currently differ; validate runtime behavior. |
| `steering.deadbandDeg` | Allowed target-angle error before the steering motor rests; saved 3.5°. |
| `steering.maxPwm` | Final absolute PWM output ceiling, saved 255. It does not limit which gain values may be entered. |
| `camera.forwardM`, `leftM`, `heightM` | Camera lens location relative to the rear-axle center: forward / left / up. Camera-to-car geometry lets observations and ARCore pose refer to the same vehicle point. |
| `camera.pitchDownDeg`, `yawLeftDeg` | Mount tilt downward and heading offset left. Needed to project observations onto the floor. Zero is valid if the measured mount aligns that way. |
| `cones.heightM`, `baseDiameterM` | Cone size and full base envelope for perception and clearance. For a square base use its diagonal as the enclosing diameter. |

The 2026-11-12 Tracker images supply lens height **0.09924 m** and front-end-to-lens horizontal distance **0.151 m**. The camera forward coordinate is **0.173 + 0.03894 − 0.151 = 0.06094 m** ahead of the rear axle, not 0.151 m. The latest top view measures lens lateral offset **+0.05090 m left**: front points to image-left, making image-bottom the vehicle's left. Centering the phone does not put its lens on the car centerline. The user retained the front-end distance during the move; the previous height is retained, not remeasured by this top view. Optical-axis pitch/yaw remain unconfirmed. The latest values and provenance are in [geometry-measurements.json](../firmware/calibration/geometry-measurements.json); the screenshots themselves are in the conversation, not stored as repository image files.

## Driving dynamics and available space

| Parameters | Why needed / how to obtain |
|---|---|
| `limits.maxSpeedMps` | Highest speed actually validated on this car/floor; not the advertised top speed. |
| `accelerationMps2` | Validated forward acceleration; prevents plans that demand unreachable speed changes. |
| `brakingMps2` | Measured deceleration; determines stopping distance. |
| `lateralMps2` | Validated sideways acceleration limit; keeps cornering speed within available grip/stability. |
| `pwmPerMps` | Approximate traction PWM needed per m/s. Fit from slow test runs using the phone's estimated speed; PWM alone is not measured speed. |
| `tractionMinPwm` | Lowest effective traction PWM, used by the trajectory planner. Saved forward/reverse start values are both 120, but the separate planner field has not been filled or validated under driving load. |
| `launchClearM`, `finishClearM` | Free distance for launch and stopping. Measure the test area. |
| `lateralSpaceM` | Total available clear corridor width, centered on the launch heading. |
| `scoutSpeedMps`, `clearanceM` | Conservative scouting speed and extra separation margin, currently 0.2 m/s and 0.15 m. |

`commissioning.steeringMeasured`, `powerVerified`, `brakingTested` and `floorVerified` record completed checks; they do not replace the underlying measurements. Network credentials/addresses establish the links, not the dynamics. `cleanBaselineRuns` controls progression beyond the baseline, not initial arming.

Manual commissioning now uses its own `manualMissing()` check: valid steering calibration, actuator fingerprint, pairing key, protocol timing and explicit manual profile/PWM limits. It does not require car/camera/cone/course geometry, VIO tracking, a speed model or unconfirmed autonomous commissioning flags. Direct manual PWM is bounded by `manual.maxPwm` (255), with `manual.reversePauseMs` (300) before reversal; it is not interpreted as measured speed. The ESP32 permits only authenticated `DRIVE_MANUAL` after explicit `ARM_MANUAL`, separately from autonomous authority. Unverified `driveBrakeVerified` selects coast on zero output rather than falsely claiming braking was confirmed. Full `missing()` checks still gate autonomous arming, Scout and Run. See [manual-drive.md](manual-drive.md) for the paired build, first tests and stop behavior.
