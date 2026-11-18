# MaxBot 0.7: USB, MCP and attended experiments

Updated 18 November 2026. This implements the experiment infrastructure. Successful host tests and APK builds do not establish Pixel USB reliability, physical motion latency, detector accuracy, or autonomous driving readiness.

## Current course

Four bright-orange cones: **135 mm base diameter, 300 mm height**. White floor, good indoor lighting. Walled corridor: **2 m wide × 10 m long**. Adjustable **0.5–1 m center-to-center spacing** leaves **0.365–0.865 m between bases**. The complete cone row spans 1.635–3.135 m including the bases. Record actual measured centers for each placement. Corridor dimensions alone do not determine its bounds relative to the robot's start-anchor frame.

Scouting protects the complete declared possible cone-row envelope, rather than assuming a fixed 700 mm layout. Finish clearance uses the observed fourth cone, or a conservative maximum-row prior before a map exists.

The profile is `maxbot/config/profiles/course-indoor-corridor.v1.json`. Current configuration generation includes cone geometry in `geometryId`, invalidating references from the older 200 mm × 150 mm cones. Existing test fixtures about that old course remain historical/synthetic examples. Do not reuse old nominal cone coordinates.

## Connection architecture

```text
Codex --local STDIO MCP--> Mac dashboard / experiment coordinator
                                  |
                         Internet-connected Wi-Fi
                             /             \
                  Pixel 5 agent          Android observer agent
                         |
                      USB serial
                         |
                       ESP32
```

Both phones use the organization's normal Wi-Fi login. The ESP needs no enterprise credentials. The firmware USB path now returns signed command application acknowledgements, which the older firmware emitted only over UDP. The connected ESP was updated for this fix and **921600 baud**; use the matching 0.7 APK/configuration. Saved steering settings were preserved and rebound to the new configuration identity. The existing ESP AP remains a legacy recovery path until Pixel USB commissioning is complete. No enterprise Wi-Fi bypass or public internet motor-control endpoint is added.

Pixel and observer messages use session-bound HMAC envelopes with separate directions and increasing sequence numbers. Modified, replayed and unsigned post-authentication messages are rejected. This provides integrity/authentication, not encryption; use an approved trusted lab network or supported `wss://` endpoint when confidentiality is required. The operator browser and automation API accept loopback connections only. Use the dashboard on the Mac at `http://localhost:8081`.

Internet connectivity does not establish phone-to-Mac reachability. If the organization blocks peer connections, use an approved lab network; do not disable its security. Keep the Mac firewall configured to allow the dashboard's inbound phone connections.

## First setup

1. Check the ESP board's USB VBUS/external 5 V wiring before connecting a powered robot to the Pixel in host mode. Use a short data cable. Charging while hosting USB requires a compatible, separately verified powered hub; ordinary cable availability does not prove simultaneous charging support.
2. Install `dist/automation/maxbot-0.7.3-timing.apk` on the Pixel and `maxbot-observer-0.7.2-rotation-fix.apk` on the external Android phone (Android 11 or newer). They are separate applications. The MaxBot APK includes the verified YOLOv5n COCO baseline.
3. Join both phones and the Mac to the same approved Wi-Fi. Start the Mac dashboard with the command below. The launcher prints each candidate Mac LAN address.
4. On the Pixel, open **Connection settings**, choose **USB serial**, and enter `ws://MAC_LAN_ADDRESS:8081/robot`. Choose **Stop and connect**. Grant Android's USB permission when asked. If the Pixel reports that the ESP is reachable but owned by the bench session, use **Reset stop** on the Mac dashboard once while disarmed to claim the new phone session. Confirm **Pixel ↔ ESP** and **Pixel ↔ Mac** independently. USB matching defaults to the current CP210x bridge, VID `10c4`, PID `ea60`; other adapters require explicit configuration.
5. On the observer, grant camera permission, enter `ws://MAC_LAN_ADDRESS:8081/observer` and the existing pairing key, and choose **Connect observer**. Keep the app in the foreground. Mount the phone so the marked wheel and Pixel's black/white trial-input witness are visible. Reframe for other experiments as necessary.
6. Pair the Mac dashboard with the existing key. In **Attended experiments**, confirm the USB power check, clear floor and observer framing. This grants a 10-minute attended session while dashboard heartbeats remain fresh. It does not move the car.
7. Begin with **Stationary camera clock validation**. Validate USB detach, network loss, observer loss and manual takeover while supported/disarmed before any powered floor trial. Then collect one bounded motion trial at a time and reposition manually.

Native observer capture probes Camera2 modes: it requests fixed 120 fps where advertised, otherwise a regular available recording mode. Actual support, encoder compatibility, frame timestamps and capture failures remain in the evidence. A device advertising a mode can still reject that combination; the app reports failure and the trial does not proceed. Do not describe a 30/60 fps recording as 120 fps.

## Fresh-terminal commands

```sh
cd "/Users/dachi.tchotashvili/Sherekilebi hub/MaxBot 1.0"
node tools/maxbot/dashboard.cjs start
node tools/maxbot/dashboard.cjs status
node tools/maxbot/dashboard.cjs stop
```

The packaged equivalents are `dist/automation/start-mac.command`, `status-mac.command`, and `stop-mac.command`. The launcher checks authenticated service ownership before stopping a process. An older dashboard on port 8081 must be stopped through its own launcher. Stopping revokes trial authority immediately, then saves available evidence; large observer uploads may continue briefly.

Source runs save in `runs/experiments/`; packaged runs save beneath `dist/automation/runs/experiments/`. The launcher logs to `.tools/automation-dashboard/dashboard.log` beneath the selected root. Keep this private bundle local: it contains the pairing key and calibrated APK.

## MCP tools

The server is `controller/node-js/maxbot/mcp.cjs`. It uses newline-delimited JSON-RPC over STDIO; stdout contains protocol messages only. The server calls the existing dashboard over authenticated loopback HTTP. It does not start a second control server or impersonate the supervising operator.

```sh
codex mcp add maxbot -- node "/Users/dachi.tchotashvili/Sherekilebi hub/MaxBot 1.0/controller/node-js/maxbot/mcp.cjs"
```

Reconnect/restart the Codex client after registration to load its tools. `MAXBOT_CONFIG` can select a different private runtime file; `MAXBOT_PORT` selects the matching dashboard port. Do not put the pairing key into committed configuration.

Tools: `maxbot_status`, `prepare`, `start`, `abort`, `list`, `read`, `preview`, `analyse`, `policy_search`, `train_detector`, `job`, `plan`, `stage`, and `activate`, each prefixed `maxbot_`. Preview returns a timestamped snapshot, not continuous model vision. Original observer video is retained independently.

Typical sequence: inspect status → prepare `camera_clock` → start its returned `prepared_id` → inspect status/job → read saved analysis. `prepare` accepts optional `{parameters:{pwm:120}}` for bounded pulse recipes, enabling separate 110/120/130/140 PWM characterization sessions. `parameters.pulse_ms` (20–1000 ms) can shorten powered segments, preserving all neutral/recovery intervals; it never extends a recipe’s original pulse. Both requested parameters and effective steps are retained in the manifest. For a narrow observer field of view, start with `{pwm:110,pulse_ms:100}` and review visibility and stopping before any repeat. It never changes a running trial. Stage/activate use the existing artifact-set protocol and return request acknowledgement; confirm the phone's applied hashes before a run.

## Trial contracts

| Recipe | Action | Endpoint / limitation |
|---|---|---|
| `camera_clock` | 10 s stationary, disarmed | Camera-clock checks; stationary IMU bias proposal if enough samples |
| `warning` | 30 s stationary, disarmed | Present/remove a known object; retain labels and footage |
| `video` | 20 s stationary, disarmed | Video buffering/encoding/network comparison; no assumed glass-to-glass clock |
| `forward_onset`, `reverse_onset` | 3 s neutral, one 0.5 s pulse, 4 s neutral | Reposition before each repeat; signed PWM timing and observer motion candidates |
| `steering` | Five left/right cycles, zero traction | ±10° commands; loaded potentiometer response |
| `speed_stop` | One 1 s pulse, 6 s neutral | Observed speed/coast response; neutral is not proof of complete stopping |
| `turn` | One 1 s pulse with 8° steering | Low-speed yaw/curvature observations |
| `slalom` | Existing validated autonomous run, ≤60 s | Requires calibrated `READY` phone, compatible model/dynamics/reference and verified launch pose; contact review remains required |

Manual experiments are bounded to 140 logical PWM, ±12°, ≤1 s per powered segment, ≤2 s powered total and ≤60 s overall. Slalom also aborts above 140 PWM or ±26°; it may be infeasible under this initial envelope. Do not raise limits to conceal a feasibility failure. The underlying autonomous supervisor retains its stricter measured-state deadlines.

The Mac renews a ≤300 ms trial lease only with fresh devices and attended dashboard heartbeats. The Pixel schedules segments locally, gives outputs ≤80 ms validity, and cancels after a >100 ms control gap. Expired/cancelled trials cannot be revived by a late lease. USB detach, app pause, severe thermal state, stale telemetry, manual input, observer failure, explicit stop and disconnect stop the trial. Reconnection never arms or resumes it. Pixel-mediated stop depends on that link; the old direct Mac→ESP UDP stop is unavailable in USB mode.

## Saved evidence and calculations

Each experiment directory contains `manifest.json`, `events.jsonl`, `summary.json`, `report.md`, `signals.jsonl`, `signals.json`, `signals.md`, and `observer/{video.mp4,frames.jsonl,samples.jsonl,observer.json}` when successfully collected. Warning and slalom experiments also request a stopped Pixel run ZIP under `pixel/`, retaining onboard recordings and scalar events for review/training; image collection follows the dashboard’s explicit collection setting and is not silently enabled during latency comparisons. Missing/corrupt uploads remain incomplete. Source/configuration/device identities, course profile, measured battery metadata, trace gaps, clock uncertainty, thermal/network metrics and outcomes are retained. Post-processing adds `analysis.json` and `analysis.md` automatically while stopped. Raw recording files remain on the observer; collection does not delete them.

The existing signal analyser computes matched capture/inference/decision/draw/Mac and command/PWM stage distributions. Warm-up exclusion, first-presentation deduplication, integer nanoseconds and clock bounds remain in effect. Do not sum medians or p95 values from unmatched records.

For event intervals A=[a₀,a₁], B=[b₀,b₁], the duration interval is **[b₀−a₁, b₁−a₀]**. Distinguish:

- Camera exposure vs image availability; comparable clocks must be established.
- Mac input vs Pixel-local scripted step. Preloaded schedules do not measure a fresh Mac command at each step.
- Signed PWM API write vs wheel motion vs internal motor-shaft motion.
- Android witness draw callback vs emitted display light. Display scanout/render delay is additional uncertainty.
- Observer sensor time vs encoded presentation time. Matching frame counts alone does not prove sensor-to-encoded alignment.

`experiment_analysis.py BUNDLE --calibration FILE` can extract reviewed wheel-ROI change candidates and a colored-marker floor trajectory. The calibration must bind the exact video SHA-256 and supply reviewed ROI coordinates, witness threshold and four pixel/floor point correspondences. Floor mapping applies to the marker's plane: a raised marker requires a matching plane calibration, not a floor homography applied blindly. Candidate motion events require frame review before promotion.

Reviewed same-video witness-to-wheel intervals go in `review.json` with `reviewed:true`, `video_sha256`, and `trials:[{endpoint:"visible_witness_to_wheel",witness_interval_ms:[...],wheel_interval_ms:[...]}]`. These measure visible witness → wheel onset, not source input → motor shaft. Exact shaft onset still needs suitable sensing. Camera calibration, optical tracking and physical timing quality cannot be certified without device recordings.

## Dynamics and policy automation

Analysis requirements are in `tools/maxbot/requirements-automation.txt`. The current Mac already has a compatible private Python environment at `.tools/yolo-export/bin/python`; jobs use it when available, otherwise `MAXBOT_PYTHON` or `python3`.

Store reviewed input datasets under `runs/datasets/`. Policy search expects schema version 1 with `reviewed:true`, measured `geometry`, `maneuvers`, `training_references`, and independent `validation_references`. Each maneuver names `session`, `split` (`train` or `validation`), `reviewed:true`, measured `alignment_uncertainty_ms` ≤20, and ≥30 samples with `time_s,pwm,speed_mps,target_deg,steering_deg`. Require four training and two validation maneuvers, no overlapping sessions, and ≥3 forward PWM levels. Gaps outside 5–100 ms must be split/censored. These are aligned physical observations, not fabricated labels inferred solely from requested PWM.

The fitter searches delay, robustly fits forward traction gain/deadband/lag and loaded steering lag, and checks independent velocity residuals. It does not invent a coast/braking model. Candidate search varies the existing pure-pursuit/yaw-damping/PI parameters within fixed bounds. References contain dense `{x,y,yaw,curvature,s,speed}` samples and actual cone coordinates. Geometry includes measured body dimensions, steering range/rate, cone radius, clearance and floor bounds in `[xmin,xmax,ymin,ymax]` order. Simulations reject footprint/cone/floor violations and operation outside identified PWM. Outputs include provenance, seed, every candidate, held-out results and a proposed policy.

This is a preliminary kinematic search, **not a deployment certificate**. It lacks tire-slip/contact physics and an independently identified coast/braking model. Existing swept-path/reference validation, uncertainty stress testing and repeated reviewed real runs remain required. It never activates a candidate or automatically labels a run contact-free. Baseline and candidate trials should be interleaved after manual repositioning.

Detector training reuses pinned YOLOv5n v7.0 for compatibility. It requires reviewed session-separated images/labels. Dataset `review.json` must attest `reviewed:true`, the exact `manifest_sha256`, and `labels_sha256` (SHA-256 over each sorted relative label path followed by that file's bytes). Regular search uses validation data; only explicit `train.py --final-test` evaluates the locked test split. Exported models remain `NOT_VALIDATED_ON_PIXEL` until held-out accuracy and sustained device timing pass. This implementation does not add the later YOLO26n challenger.

## Validation status

Host tests cover signed-session tampering/replay, real loopback MCP/HTTP/WebSocket collection with simulated devices, ordered/hash-checked uploads, manual takeover regressions, local trial limits/leases, held-out synthetic dynamics recovery and timing interval arithmetic. Android builds and lint are run for both apps. The connected ESP was flashed with the USB acknowledgement fix; identity, unchanged saved steering settings and zero-output signed USB application acknowledgements were checked. Bench commands never arm or drive the motors. **Pixel USB permission/throughput, observer Camera2 recording modes, enterprise LAN reachability and physical floor trials still require device validation.**

Physical stop hardware, automatic battery measurement, camera/IMU mounting alignment, corridor bounds in the robot frame, grip/braking and contact ground truth remain separate measured prerequisites where applicable. The software does not turn corridor dimensions or a powered-on phone into proof of readiness.

Actual Mac/ESP USB results and UART budget calculations: [18 November bench review](reports/2026-11-18-usb-migration-review.md).

## Observer preview correction, 0.7.1

The first observer UI stretched its TextureView into the remaining height beneath the connection fields and always exported a 320×180 bitmap. A live Pixel 10 Pro image showed the car on the left and cone on the right with visibly unreliable proportions. Do not use that preview for dimensional calibration.

Observer 0.7.1 uses a centered camera view with preserved buffer aspect ratio and black letterboxing; connection fields collapse after pairing to give the image more room. Remote snapshots preserve the view aspect ratio, up to 640×480, with dimensions and source-buffer sizes included in metadata. This is a display/snapshot correction; the MediaRecorder retains its separate encoder surface and configured recording size. Native video geometry still requires a test clip before accepting measurements. Camera orientation metadata is retained; this patch does not certify all device rotation/crop combinations.

The 0.7.1 update was installed on the Pixel 10 Pro observer. The operator confirmed better proportions but reported a remaining 90° rotation error. Install the 0.7.2 update below. The package identity is unchanged, retaining its paired address/key and local recordings. Tap Connect observer again. The source builds, lint passes, and two host tests check proportional scaling across viewport and image shapes. No observer was attached through ADB for a hardware screenshot/install; verify the new image and reported preview dimensions after installation.

## Observer rotation correction, 0.7.2

The aspect-ratio correction alone missed the camera sensor/display orientation relationship. Observer 0.7.2 computes the displayed image dimensions from sensor orientation minus display rotation, then applies the TextureView transform for the actual display rotation and uniform scaling. It listens for display changes, including a 180° change without a size change. The rear-camera preview is not mirrored.

Remote snapshots now use PixelCopy of the rendered camera viewport instead of TextureView.getBitmap, so the remote image uses the same rotation and proportions as the phone screen. Status and frame messages include display rotation, sensor orientation, buffer/view dimensions, app version and snapshot source. Snapshot timestamps identify the rendered window copy and must not be used as sensor exposure timestamps. The separate recording encoder path is unchanged; validate native video orientation with an actual clip before calibration.

Install `dist/automation/maxbot-observer-0.7.2-rotation-fix.apk` as an update on the observer, open MaxBot Observer and tap **Connect observer**. Saved pairing details and recordings remain under the same app identity. The debug APK builds and Android lint passes. Four host tests cover proportional fitting, all 16 sensor/display quarter-turn combinations, correct rotation direction, full-image bounds and absence of mirroring. No ADB device was available to install the update or capture the corrected display; physical visual verification remains pending.

## Bounded thermal allowance, MaxBot 0.7.3

At the operator's explicit request, `prepare` can use `parameters.allow_severe_thermal:true` for timing trials. The default remains false. With the allowance, PWM defaults to 110 and `pulse_ms` to 100; higher values and slalom are rejected. The Pixel independently validates at most 100 ms powered total, PWM magnitude ≤110 and steering magnitude ≤10°. Stationary trials do not arm the motors. Android SEVERE status (3) is recorded and allowed for that active trial; CRITICAL (4) and higher remain stopping conditions. No thermal allowance survives cancellation/completion or changes the default threshold outside the trial. Device faults, app pause, takeover, attendance, lease and motor deadlines remain effective.

Example: `prepare({recipe:"camera_clock",parameters:{allow_severe_thermal:true}})`, followed by its normal attended `start`. Numeric `thermal_status`, policy capability and the active allowance appear in status/traces. Manifests retain requested parameters and the effective bounds. Unknown/older Pixel apps cannot use the allowance. The updated APK must be installed on Pixel 5; building it does not update a phone. Host tests and Android build/lint pass; this release has not supplied a new physical measurement.

The [18 November final latency review](reports/2026-11-18-final-latency-report.md) distinguishes the accepted baseline, synthetic exports, aborted hardware attempts and the separate USB bench. No same-endpoint latency improvement is established by the supplied files.
