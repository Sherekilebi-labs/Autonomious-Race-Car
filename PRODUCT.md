# MaxBot

MaxBot is a local Android/ESP32 control system for one indoor 1:16 car. A single supervising operator scouts a four-cone course autonomously, repositions the car, verifies localization, then starts a timed right/left/right/left slalom. The operator must be able to stop or take over immediately.

Hardware facts: Pixel 5, classic ESP32, DC traction motor through TA6586, five-wire DC steering motor with potentiometer through another H-bridge, 3S LiPo and buck supply (logical ±255 mapped to physical ±170). No wheel encoder; speed is a visual estimate. Car/cone geometry and steering calibration are user-supplied measurements. Unknown actuator values block manual arming; unknown geometry/model values block autonomy. Manual control uses a separate 300 ms input window and 500 ms command watchdog.

The console is an operating surface, adapted from OpenBot's local controller. Video and vehicle state lead, emergency stop stays visible, manual drive uses explicitly armed WASD control with ramped release and immediate stop on focus loss, and missing/stale data is explicit. Local camera/telemetry, screenshot, map, recording and calibration status are required. No synthetic vehicle data may appear as live telemetry.
