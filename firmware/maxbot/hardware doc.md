## ESP32 Pinout

### Traction motor TA6586 H-bridge:

- IN1 - GPIO 4
- IN2 - GPIO 16

### Steering Servo 5 pin servo motor TA6586 H-bridge:

- IN1 - GPIO 22
- IN2 - GPIO 23

### Steering 5 pin Servo Potentiometer Circuit

- Potentiometer total resistance measured: 4.79 K ohm
- Potentiometer middle pin to gnd resistance min physical limit
- measured: 100 ohm
- Potentiometer middle pin to gnd resistance max physical limit measured: 4.61 K ohm

- angle delta between max and min physical angles: ~200 degrees

- Circuit diagram:

```
+5V -> left pin - potentiometer - right pin -> GND
                       |
                    middle pin -> 1Kohm R --- 2Kohm R - GND
                                           |
                                        GPIO 14
```

- We dont know if IN1 pin of the bridge will spin the motor to raise or lower the resistance of the potentiometer. and we also dont know if traction motor bridge's in1 pin spins the wheels forward or backward. make a seperate script that will calibrate servo (send short pulse to each h bridge pin and observe potentiometer feedback, same for drive motor) for calibration script make the esp32 host a website UI where I can have calibration buttons and full control dashboard: everything needed to calibrate pins.


# Car specs & Dimentions

- Wheelbase: 173mm (Tracker measurement 2026-11-12; supersedes 168.5mm)
- Max Steering Angle: +- 26 degrees (saved wheel-angle calibration; initial estimate was +-25)
- weight: approximately 1.5kg +- 0.5kg
- COM approximately 60mm+-5mm from rear wheels towards front wheels (COM height not measured)

## Implementation update

The original GPIO14 diagram above is retained as a record of the initial wiring.
**Move its ADC connection to GPIO35**, as approved: classic ESP32 GPIO14 is ADC2,
which conflicts with active Wi-Fi; GPIO35 is ADC1 and supports the calibration AP
and runtime Wi-Fi connection. Keep the resistor divider and verify the physical
ADC voltage over the working range before enabling motor power.

The standalone website calibration sketch is
[`../calibration/calibration.ino`](../calibration/calibration.ino), with instructions
in [`../calibration/README.md`](../calibration/README.md). It measures steering
feedback direction from short pulses; traction direction is confirmed visually
because there is no encoder. Neither motor's IN1 direction is assumed.

The earlier runtime configuration used wheelbase 0.1685 m and provisional
front-wheel limits ±25°; the current measurements below supersede those values.
The approximately 200° shaft travel is not used as wheel angle.

## Historical sensing (2026-11-10; disabled below)

Saved steering calibration supersedes the provisional values above: ±26°,
seven ADC points with ADC785 at center, both motor polarities +1.
There is **no physical-stop switch**; `physicalStopPin: null` is optional in
runtime firmware. Software emergency stop and watchdog are retained.

The user reported buck **output** 5 V → 1 kΩ upper → sense junction
→ 2 kΩ lower → common ground, with the junction on **GPIO25**.
Its recorded divider ratio is 1.5 and
nominal junction voltage is 3.33 V. This is not a battery monitor: the buck
regulates its output, GPIO25 is ADC2 and conflicts with active Wi-Fi, and
3.33 V exceeds the documented 11 dB ADC measurement range. Saved power
source was `regulated5V`; this initially blocked arming. The 8.4 / 7.0 / 6.0 V settings are pack thresholds,
not thresholds to apply to the regulated 5 V output.

For a later pack monitor, use a free ADC1 pin and a divider sized for the
maximum pack voltage. Do not connect the present 1.5-ratio divider to 2S:
8.4 V would produce 5.6 V on the GPIO. No wiring change has been assumed.

## Manual battery checks (2026-11-12)

The user has disabled battery monitoring and will check the pack before each
run, targeting approximately 12 V on the current 3S pack. Both motor bridges
map logical ±255 to physical ±170 duty, referenced to the former 8.4 V
maximum pack voltage. This limits average applied voltage, not the 12.6 V
switching pulses. Both-high braking remains unchanged. Current saved configuration uses
`power.batteryMonitoring: false`, `power.senseSource: "disabled"` and
`hardware.batteryPin: null`. Runtime firmware does not read GPIO25 or any
battery ADC and does not impose a battery-voltage arming gate. ADC calibration,
divider and voltage thresholds are inactive references. There is no automatic
low-voltage stop or voltage-based PWM compensation in this mode; live voltage
and percentage are unavailable. Motor/power wiring verification and the other
stop mechanisms remain separate requirements. No physical rewiring is assumed.

## Tracker geometry (2026-11-12)

| Measurement | Value | Reference |
|---|---:|---|
| Wheelbase | 173 mm | First screenshot, axle centers |
| Front overhang | 38.94 mm | First screenshot, front axle to front end |
| Front track width | 127 mm | Third screenshot, tire centers; not outside width |
| Camera lens height | 99.24 mm | Second screenshot, ground to sensor/lens |
| Camera setback from front end | 151 mm | Second screenshot, horizontal distance |
| Camera forward of rear axle | 60.94 mm | Derived: 173 + 38.94 − 151 |
| Full mounted width | 144 mm | Latest top view, phone moved to a symmetric position |
| Camera left of centerline | +50.90 mm | Latest top view, 5.090E-2 m; front points image-left |

The rear-axle center remains the vehicle origin. Use the lens location rather
than the phone center for camera calibration. The fourth top view supersedes
the initial asymmetric phone mount: the user centered the phone while retaining
its 151 mm distance from the front. The lens remains 50.90 mm to the car's left;
image-bottom is left when the bumper points image-left. The centered collision
rectangle uses the measured 144 mm width (72 mm half-width), not 127 mm track
spacing. Previous camera height and forward offset are retained. Rear overhang
must include installed electronics behind the chassis; it and camera pitch/yaw
remain unconfirmed. See
[`../calibration/geometry-measurements.json`](../calibration/geometry-measurements.json)
for the numeric evidence and remaining measurement references.
