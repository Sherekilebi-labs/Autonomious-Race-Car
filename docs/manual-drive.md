# Manual commissioning build

**Task-1 migration:** this page preserves setup for the older manual APK bundle. The current app has status indicators only, connects on open and uses the Mac dashboard for manual arm/deadman/reset. Follow [task-1 deployment readiness](autonomy/deployment-readiness.md) for current builds, AP discovery, signed direct stop and JSON/model activation. The calibrated actuator limits below remain unchanged.

This build enables direct, held manual steering/traction control without cone measurements, a model, course dimensions, a speed/PWM model or ARCore tracking. It does not mark physical commissioning checks complete. Autonomous arming remains locked until its own calibration is complete.

The ESP32 creates **MaxBot-Drive** Wi-Fi at **192.168.4.1**. The Pixel and Mac both join it; there is no phone-to-ESP32 USB cable. The private Wi-Fi password and browser pairing key are in `connection-details.txt` inside the build bundle. They are different credentials and remain constant across resets. Do not use the old MaxBot-Cal network for this build.

**Current power profile: 3S LiPo, 12.6 V maximum.** Traction and steering both map logical ±255 to physical ±170 duty (2/3), preserving approximately the former 2S average-voltage envelope. Start thresholds and PID limits remain logical commands; do not scale them a second time. Both-high electrical braking stays steady HIGH/HIGH. The H-bridge still switches 12.6 V pulses; PWM is not a buck converter or peak-current limiter. Confirm driver, actuator and buck input compatibility; keep the regulated logic/feedback supply at 5 V. Recheck steering and stopping at low command after changing packs.

## First setup

1. Raise the wheels and disconnect traction power while flashing. Connect the ESP32 to the Mac by USB for upload. In the extracted bundle run `sh flash-esp32.command /dev/cu.YOUR_ESP32_PORT`; running it without a port lists detected boards. It uses this Mac's installed Arduino CLI and ESP32 core. This replaces the standalone calibration sketch.
2. Install `maxbot-manual.apk` on the Pixel. For video, ensure Google Play Services for AR is installed while internet is available. Import the bundled `maxbot.runtime.json` through **Import calibration**, even if an older app/configuration was installed. This pairs the app with this firmware's actuator fingerprint and credentials. Manual control does not require a cone model or tracking.
3. Join **MaxBot-Drive** on both Mac and Pixel using the Wi-Fi password in `connection-details.txt`. This hotspot has no internet. Keep the phone on this Wi-Fi connection and MaxBot foreground. Allow camera permission for video.
4. Run `start-mac.command` from the bundle. It includes the Node `ws` dependency and fonts for offline operation; Node.js 20+ must be installed. Open **http://localhost:8081** on this Mac, then enter the **browser pairing key** from `connection-details.txt`. Allow Node incoming connections on the Mac if prompted.
5. The launcher prints the Mac's current `ws://...:8081/robot` address. On the Pixel, use **Set Mac dashboard address** if it differs from the bundled default `ws://192.168.4.2:8081/robot`, then **Connect vehicle + dashboard**. DHCP addresses depend on connection order, so the Mac is not guaranteed to receive `.2`. Address changes do not change actuator calibration or require reflashing.
6. Confirm fresh ESP32 telemetry. Video can be displayed before camera geometry/tracking is complete; if unavailable, its status is separate from manual readiness. Select **Arm manual commissioning** in the Mac dashboard or on the Pixel. Nothing arms automatically after startup, reconnect or reset.

## First actuator tests

With wheels raised, connect traction power and verify direction and steering response. The saved traction starting command is about **120** in each direction, now mapped to **80** physical duty on 3S. The dashboard requests signed PWM from **−255 to +255**, mapped in firmware to **−170 to +170**; there is no automatic minimum-command jump. Move the request gradually and hold **Hold to drive manually** only while observing the car.

Steering is limited to the saved **−26° right / +26° left** range with potentiometer feedback, output clipping and stall checks. The runtime PID must still be checked physically with the saved gains. A full request is not a measured speed limit.

Release the deadman, press Stop/Escape, hide the tab and disconnect the operator in turn to check that output commands cease. Manual demands expire after 100 ms; the MCU watchdog stops outputs after 150 ms without a valid command. These are software deadlines, not measured stopping distances. Commands require fresh signed MCU telemetry and operator heartbeats. There is a 300 ms neutral pause before a reversal request is admitted; fresh VIO, when available, also prevents reversal while visually moving the opposite way.

**Zero/stop behavior follows `hardware.driveBrakeVerified`.** When true, both traction inputs are driven high for electrical braking; when false, both inputs are low to request coast. The build manifest records the selected setting. The user has now verified the brake behavior and set this flag true; the other commissioning flags remain separate and are not automatically confirmed. Start the updated firmware with wheels raised to confirm deadman release and Stop select braking, then use low-speed floor tests to check actual stopping distance. Electrical braking does not promise instantaneous physical stopping.

Battery monitoring and the physical-stop input are disabled as requested. Manually check the 3S pack near **12 V before every run**; voltage/percentage and automatic low-voltage stop are unavailable. Emergency stop, deadman, actuator-feedback faults, signed command expiry, thermal stopping and the MCU watchdog remain active.

After these raised-wheel checks, use a clear floor and low PWM for initial supervised driving. Autonomous Scout/Run remain unavailable until full measurements, checks and model are supplied.

## Build verification and contents

`esp32/` contains the compiled classic ESP32 Dev Module firmware and its upload images. `maxbot-manual.apk` embeds the matching configuration; `maxbot.runtime.json` is the same configuration for import. The packaging tool checks both the APK asset and the firmware binary's calibration fingerprint before creating the bundle. `manifest.json` records hashes and the unconfirmed commissioning flags. The source command is `python3 tools/maxbot/package_manual.py`, run after assembling the APK and compiling runtime firmware with `--output-dir dist/manual-drive/esp32`.

Software tests cover manual readiness with absent autonomy measurements/tracking, full-range PWM, command/heartbeat expiry, neutral reversal, continued autonomy locks, battery-disabled operation, browser controls and dashboard transport. Compilation and simulation do not verify the real wiring, radio/video behavior, steering or physical stopping. No upload or app installation is performed by packaging.
