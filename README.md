# MaxBot 1.0

Pixel 5 + ESP32 autonomy for a 1:16 four-wheel-drive car with a DC traction motor, TA6586 H-bridge and five-wire potentiometer-feedback steering. The phone runs ARCore localization, cone detection, mapping and 50 Hz pure-pursuit feedback. The Mac supplies the local dashboard and plans the full reference while stopped; model training remains a later task. Original OpenBot documentation: [README.openbot.md](README.openbot.md).

Explore the [interactive system map](docs/system-diagram.html), from the project overview through L4 code and contracts. [Usage and maintenance](docs/system-diagram.md).

**Manual commissioning is a separate ready-to-build profile.** It uses signed `ARM_MANUAL` / `DRIVE_MANUAL` commands, the measured steering calibration and direct PWM up to ±255; it does not need ARCore tracking, a cone model, course geometry or an unmeasured speed/PWM model. Physical confirmations remain unmodified. Until `driveBrakeVerified` is actually confirmed, runtime zero/stop requests coast instead of assuming electrical braking. The current local build config creates **MaxBot-Drive** on the ESP32 for both Pixel and Mac; private credentials, matching APK/firmware, offline Mac launcher and upload script are packaged under ignored `dist/manual-drive/`. [Manual setup and first-test guide](docs/manual-drive.md).

## Configuration and deployment

Task-1 software and private build artifacts are described in [deployment readiness](docs/autonomy/deployment-readiness.md); [implementation evidence](docs/autonomy/implementation-evidence.md) separates host checks from missing device/physical validation. The app now starts services on open and displays status only. Use the Mac dashboard for controls and staged JSON/model/reference updates.

### Legacy baseline block (export compatibility)

**Recorded steering limits are −26° right / +26° left; commissioning confirmation is still required.** The saved seven-point ADC table comes from your calibration photo. Confirm travel time and backlash before setting `steeringMeasured`. Five-wire steering needs a DC motor feedback controller; it does not accept hobby-servo RC pulses.

Distances are **meters**, angles **degrees**, speeds **m/s**. Steering: positive left, negative right. Camera offsets: forward/left/up from the rear-axle center. Cone base diameter must enclose the whole base (including a square base's diagonal). `lateralSpaceM` is total clear corridor width centered on the launch heading. Leave unknown values `null`.

Runtime defaults now come from [app-defaults.v1.json](maxbot/config/profiles/app-defaults.v1.json), followed by [saved calibration](firmware/calibration/calibration.json), then private `maxbot.local.json` overrides. Run `python3 tools/maxbot/configure.py` after private hardware changes and build the matching APK/firmware. This README block remains a legacy export fallback. Compatible app, geometry, policy, model and reference artifacts activate from the Mac while stopped; hardware actuator changes require matching firmware deployment. Unknown measurements keep autonomy locked.

Tracker measurements supplied on 2026-11-12 replace the earlier 168.5 mm wheelbase with **173 mm**. Recorded front overhang is **38.94 mm**, tire-center spacing **127 mm**, and camera lens height **99.24 mm**. Camera-to-front distance **151 mm** gives a rear-axle-relative forward offset of **173 + 38.94 − 151 = 60.94 mm**. The latest top view supersedes the earlier asymmetric phone placement: the user centered the phone without changing that front-end distance, measured **144 mm full width**, and marked the lens **50.90 mm left of the car centerline** (`camera.leftM: +0.05090`; front points left in the image, making image-bottom the car's left). The tire-center spacing is descriptive `trackWidthM`, not collision `widthM`. Rear overhang is user-confirmed at **55 mm**. Four cones are **200 mm diameter × 150 mm high**, with **500 mm edge gaps** and a **1 m bumper launch gap**; first pass is to the right. Camera pitch/yaw, mounted stream/IMU alignment, floor bounds and dynamics still need measurement. [Measurement record and reference views](firmware/calibration/geometry-measurements.json).

<!-- MAXBOT_CONFIG_START -->
```json
{
  "schemaVersion": 1,
  "car": {
    "wheelbaseM": 0.173,
    "trackWidthM": 0.127,
    "widthM": 0.144,
    "frontOverhangM": 0.03894,
    "rearOverhangM": null
  },
  "manual": {
    "enabled": true,
    "maxPwm": 255,
    "reversePauseMs": 300
  },
  "steering": {
    "leftDeg": 25,
    "rightDeg": -25,
    "travelTimeS": null,
    "deadbandDeg": null,
    "adc": null,
    "degrees": null,
    "kp": null,
    "ki": null,
    "kd": null,
    "maxPwm": null
  },
  "camera": {
    "forwardM": 0.06094,
    "leftM": 0.05090,
    "heightM": 0.09924,
    "pitchDownDeg": null,
    "yawLeftDeg": null
  },
  "cones": {
    "heightM": null,
    "baseDiameterM": null,
    "count": 4
  },
  "power": {
    "batteryMonitoring": false,
    "packCells": 3,
    "packMaxV": 12.6,
    "pwmReferenceMaxV": 8.4,
    "manualCheckTargetV": 12.0,
    "senseSource": "disabled",
    "dividerRatio": null,
    "adcReferenceMv": null,
    "adcOffsetMv": null,
    "fullV": null,
    "warningV": null,
    "stopV": null
  },
  "limits": {
    "scoutSpeedMps": 0.2,
    "maxSpeedMps": null,
    "accelerationMps2": null,
    "brakingMps2": null,
    "lateralMps2": null,
    "pwmPerMps": null,
    "tractionMinPwm": null,
    "launchClearM": null,
    "finishClearM": null,
    "lateralSpaceM": null,
    "clearanceM": 0.15,
    "commandTtlMs": 100,
    "watchdogMs": 150,
    "supervisorMs": 500
  },
  "commissioning": {
    "steeringMeasured": false,
    "brakingTested": false,
    "powerVerified": false,
    "floorVerified": false,
    "cleanBaselineRuns": 0
  },
  "network": {
    "transport": "wifi",
    "espHost": "",
    "espPort": 4242,
    "dashboardUrl": "",
    "pairingKey": "",
    "baud": 460800
  },
  "hardware": {
    "driveForwardPin": 4,
    "driveReversePin": 16,
    "steerForwardPin": 22,
    "steerReversePin": 23,
    "steerPotPin": 35,
    "batteryPin": null,
    "physicalStopPin": null,
    "steerPolarity": null,
    "drivePolarity": null,
    "driveBrakeVerified": false,
    "wifiSsid": "",
    "wifiMode": "station",
    "wifiPassword": ""
  }
}
```
<!-- MAXBOT_CONFIG_END -->

`steering.degrees` runs from measured right limit through zero to measured left limit. Its corresponding `adc` array may increase or decrease but must be strictly monotonic. Use >=3 points, preferably >=5. Tune gains and PWM with wheels raised and a current-limited supply. `dividerRatio = (Rtop + Rbottom) / Rbottom`; calibrate ADC reference/offset and pack voltage against a multimeter. Voltage-based percentage is approximate and cannot detect cell imbalance.

**The current supply is 3S LiPo, up to 12.6 V.** Both traction and steering map logical commands ±255 to physical duty ±170 using `255 × 8.4 / 12.6`. The 8.4 V reference preserves the earlier 2S average-voltage envelope; commands, PID gains and recorded starting-PWM values stay on the logical 0–255 scale. The runtime and bench calibration sketches share the generated mapping. Electrical braking keeps steady HIGH/HIGH inputs rather than scaling its logic levels. PWM still switches the full pack voltage; it is not a regulated 8.4 V supply or a peak-current limiter. Confirm motor/driver/buck compatibility and retain the buck's regulated 5 V logic output.

**Battery monitoring is disabled**, as requested. The operator measures the 3S pack before each run, aiming for approximately **12 V** (`power.manualCheckTargetV`; this is a target, not a live measurement or an arming requirement). `power.batteryMonitoring: false` removes the battery ADC/scale/threshold requirements from MCU and phone arming. The generated battery pin is `-1`, no battery ADC is read, voltage compensation is skipped, and voltage/percentage telemetry is unavailable. There is **no automatic low-battery stop** in this mode. `commissioning.powerVerified` still confirms motor-driver/power wiring, independently of battery measurement.

The prior GPIO25 connection sensed the regulated 5 V buck output through 1 kΩ / 2 kΩ; it was not a pack monitor and is now inactive in runtime firmware (`hardware.batteryPin: null`). The current **12.6 / 10.5 / 9.0 V** full/warning/stop values are inactive 3S references. If monitoring is reenabled later, a newly calibrated 3S-rated divider and ADC1 pin are required. Do not connect the old 1.5-ratio divider to the pack: 12.6 V would put 8.4 V on the GPIO.

`hardware.physicalStopPin: null` means **no physical switch installed** and does not block runtime arming. A configured switch remains active-low and must use a distinct supported input. Dashboard emergency stop, fresh manual-intent deadlines, focus-loss stop and watchdog remain enabled. Manual driving uses armed WASD control with configurable drive/steering ramps; release decays toward zero. The [simple manual profile](docs/autonomy/manual-control.md) uses 300 ms input freshness and a 500 ms ESP watchdog; autonomous commands retain 100/150 ms timing.

For plain-language definitions, units and measurement guidance for every remaining field, see [calibration parameters](docs/calibration-parameters.md).

Keep credentials out of this tracked README. Copy [maxbot.local.example.json](maxbot.local.example.json) to ignored `maxbot.local.json`, enter addresses/Wi-Fi and a random pairing key >=32 characters, then rerun configuration. Generated files contain private configuration and are ignored. Do not share them.

Hardware notes are in [firmware/maxbot/hardware doc.md](firmware/maxbot/hardware%20doc.md). Known wiring is entered above: traction IN1/IN2 = 4/16, steering IN1/IN2 = 22/23, feedback ADC = **35**. The saved calibration now supplies measured ±26° wheel limits, seven photo-derived ADC/angle pairs, PID gains 50/3/1.3, and the recorded PWM thresholds. Piecewise linear interpolation uses every captured pair, including ADC **785 = 0°**. Commissioning remains unconfirmed. `driveForwardPin`/`driveReversePin` retain the physical IN1/IN2 assignments; measured `drivePolarity` (+1 or −1) defines which is actually forward. `steerPolarity` likewise defines which bridge direction increases positive-left wheel angle.

Use the separate [Wi-Fi calibration sketch](firmware/calibration/calibration.ino) and its [step-by-step guide](firmware/calibration/README.md) before the driving firmware. It hosts a local dashboard for bounded pulses, direction identification, ADC-angle capture, supervised PID tests and battery calibration. Join its fixed bench network **`MaxBot-Cal`** with password **`MaxBot2026`**, then open `http://192.168.4.1`. These credentials survive resets and are printed at **115200 baud**. Download measurements, then run `python3 tools/maxbot/configure.py --import-calibration /path/to/maxbot-calibration.json`. This preserves private credentials and keeps commissioning flags unconfirmed.

## Android build

Requirements: Java 17, Android SDK 36/build tools 36.0.0, NDK 28.2.13676358, CMake 3.22.1. MaxBot is a separate `org.maxbot.app` application; it does not initialize upstream OpenBot's motor/USB/Bluetooth services or require Firebase/login.

```sh
python3 tools/maxbot/configure.py
python3 -m venv .tools/training
.tools/training/bin/pip install -r tools/maxbot/requirements-mpc.txt
.tools/training/bin/python tools/maxbot/generate_mpc.py
cd robot/android
./gradlew :maxbot:assembleDebug
```

Install `robot/android/maxbot/build/outputs/apk/debug/maxbot-debug.apk`. Install Google Play Services for AR and grant camera/USB permission. Import calibration/model through the app. Use a release build for final profiling.

The cone model must be trained/exported separately; no fake model is included. Autonomous arming stays locked until a compatible model and native MPC are loaded. Tensor contract: RGB letterboxed 320×320, batch 1, decoded YOLOv5 predictions `[1,6300,6]` containing normalized center-x, center-y, width, height, objectness and cone probability, without embedded NMS. Float32 I/O with FP16 weights is the GPU configuration; int8/uint8 I/O is the CPU alternative. Unsupported types/shapes fail closed.

## ESP32 firmware

Build `firmware/maxbot/maxbot.ino` with classic **ESP32 Dev Module**, Arduino ESP32 **2.0.17** or **3.3.0**, ArduinoJson **6.21.5**, after generating calibration. Both PWM APIs are supported; this workspace verified compilation with installed core **3.3.0**. Do not use upstream differential-drive firmware. Select unique, non-strapping, PWM-capable output pins. Potentiometer/battery inputs must use ADC1 on classic ESP32. The physical stop input is optional (`null` when absent); if installed, it is active-low on a GPIO supporting an internal pull-up. A motor-power disconnect can operate independently of software.

Power: 3S (12.6 V maximum) → appropriately rated motor-driver supply; 3S → buck → board's documented power input. Verify motor stall-current/voltage ratings, driver logic levels, decoupling, common ground, potentiometer voltage and USB VBUS isolation. Do not feed 5 V into the 3.3 V pin or pack voltage into GPIO. Verify TA6586 brake behavior/current before setting `driveBrakeVerified`. Disarmed serial diagnostics expose ADC values for calibration.

USB serial (460800 baud by default) and Wi-Fi UDP (4242) share an authenticated protocol. The larger authenticated JSON packets need >=230400 baud for 50 Hz traffic; use the same generated baud on the phone and ESP32. Change transports only while stopped; reconnecting never rearms. Populate `network.espHost` with the ESP32 LAN address. Commands expire after 100 ms; the ESP32 watchdog stops after 150 ms without a valid command. Heartbeats cannot renew old throttle. Measure actual electrical stopping distance before floor tests.

The phone and firmware compare an authenticated actuator-calibration fingerprint. Changing steering, voltage sensing, motor/potentiometer wiring, either measured motor polarity or serial baud requires regenerating configuration and reflashing the firmware. Camera/course settings and dashboard addresses can be imported on the phone without changing actuator calibration.

## Local dashboard

```sh
cd controller/node-js/maxbot
npm ci
MAXBOT_HOST=0.0.0.0 MAXBOT_PAIRING_KEY='your-private-pairing-key' npm start
```

Open `http://localhost:8081` here; set the phone's `dashboardUrl` to `ws://COMPUTER_LAN_IP:8081/robot`. Computer, Pixel and ESP32 share a trusted LAN. Default bind is loopback; the LAN bind above is needed for the phone. Do not expose the server to the Internet. Pairing protects control; HTTP/WS on a trusted LAN does not provide TLS confidentiality.

Video uses WebRTC without public STUN/TURN. Telemetry includes vision-estimated speed when tracking is fresh, actual/target steering, separate manual/autonomous readiness, map/path, latency and faults. Battery voltage/percentage are unavailable while monitoring is disabled. **Arm manual commissioning** enables held direct control up to the configured PWM cap; release, focus loss, hidden tab or disconnection requests zero output. Unverified electrical braking uses coast. Manual takeover cancels autonomy. Operator/server disconnection disarms; emergency stop latches; reset leaves the car disarmed. Screenshots include the displayed frame and telemetry sidecar, distinct from clean training frames.

## Autonomous workflow

1. Measure/import calibration and test steering, braking, command expiry and any installed physical stop with wheels raised.
2. Position the car facing the first visible cone within the measured clear launch/corridor/finish area. Warm ARCore up on textured floor. The car waits rather than searching blindly without a confirmed cone.
3. Pair the dashboard, load the cone model, arm the ESP32, and select **Scout**. It maps four stable cones at a conservative 0.2 m/s cap. Impossible paths, stale inputs or excess cone landmarks stop the scout.
4. Reposition at the original start without restarting the app or moving the phone mount. Verification requires matching start pose/heading, stationary estimated speed and matching cone observations. Session restarts or pose jumps require rescanning.
5. Start **Timed run**: car-left, car-right, car-left, car-right, then stop in the finish area. Passage is checked after the rear of the car clears each cone. Timers record launch-to-stop and first-cone-to-stop.
6. Review contact manually. Enter the verified clean count only after 20 clean baseline runs; then increase measured speed limits gradually. Advertised 30 mph is not a validated operating limit.

Tracking, telemetry, 500 ms operator supervision, battery and native solver health are independent arming/driving gates. A passing build cannot establish zero collisions on untested hardware.

## Training and data

Run export contains JSONL for pose, detections, commands, steering/battery and faults, plus selected clean frames. Collection saves 2 Hz through a bounded writer queue that drops optional images rather than delaying control. Local hardware-encoded video is capability-checked; WebRTC is separate.

```sh
python3 tools/maxbot/dataset.py extract --video run.mp4 --output datasets/session-a --fps 2
python3 tools/maxbot/dataset.py split --source datasets --output datasets/cones
python3 tools/maxbot/dataset.py labels --dataset datasets/cones
python3 tools/maxbot/train.py --dataset datasets/cones/data.yaml --output models/cone-v1
```

Use source session directories with `images/` and human-reviewed YOLO `labels/` matching names. Empty frames need empty `.txt` files. Split complete sessions, never neighboring frames. Include distant/occluded cones, blur, glare, orange distractors and empty floor. Train off-device using `tools/maxbot/requirements-training.txt`; evaluate the exported TFLite artifact on held-out sessions and the actual Pixel. Collision labels evaluate driving; they do not replace cone boxes.

Training uses a separate Python 3.10/3.11 environment from the MPC generator. Clone `ultralytics/yolov5` at tag `v7.0` into `.tools/yolov5`, then install that repository's requirements together with the pinned `requirements-training.txt` constraints. The wrapper checks the exact tag, records its commit and dataset hashes, exports FP16-weight and INT8 models, and evaluates both exports on the locked test partition. Use `--device 0` on a configured CUDA machine. No cone footage or trained weights are fabricated in this repository.

## Performance and tests

Use a dedicated Pixel: keep MaxBot foreground, screen awake at modest brightness, 60 Hz when available, pause backups/downloads, restrict unused third-party apps and use airplane mode with Wi-Fi reenabled when cellular is unnecessary. Keep ARCore/Google Play services and networking. Thermal protection stays enabled. Optional sustained mode is checked for device support. `tools/maxbot/pixel-profile.sh inspect` reports CPU/memory/thermal state; `stop-unused` accepts an explicit third-party allowlist and never disables packages.

The app uses three YUV slots, newest-frame replacement, reusable tensors/output arrays, fixed workers and a dedicated GPU thread. Initial stream: 640×480 at 15 fps; dashboard updates: 10 Hz. Control targets 50 Hz, steering 200 Hz, detection 20–30 Hz, capture-to-command p95 <100 ms. These are acceptance targets until measured. Compare CPU/GPU with ARCore/streaming active for 15 minutes. Inspect frame age, p95/p99 latency, memory growth, deadlines and tracking; free RAM alone is not the goal.

```sh
sh tools/maxbot/test-core.sh
sh tools/maxbot/test-calibration.sh
sh tools/maxbot/test-native.sh
python3 -m unittest discover -s tools/maxbot/tests -v
cd controller/node-js/maxbot && npm test
cd robot/android && ./gradlew :maxbot:assembleDebug :maxbot-core:verifyCore
```

Before increasing speed: validate estimated speed/map scale against physical measurements, command-loss stops on both transports, manual override/kill controls and 20 consecutive collision-free correct-order runs. Cone-motion collision suggestions are review candidates; off-camera contact cannot be ruled out.

Software verification in this workspace: debug APK assembly and Android lint; pure Java control tests; generated OSQP/JNI tests including simulated scouting, manual reposition verification and timed slalom; Node HTTP/WebSocket authentication/control tests; dataset/configuration tests; ESP32 3.3.0 builds of both locked and synthetic calibrated branches. Synthetic firmware fixtures are **compile-only and must never be flashed**. No Pixel/car was attached, so live video, VIO quality, physical stopping, detector accuracy and sustained on-device performance still require commissioning. The conservative simulation result is not a real-car performance claim.
