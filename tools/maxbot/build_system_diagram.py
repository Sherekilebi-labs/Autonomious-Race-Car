#!/usr/bin/env python3
"""Build the offline four-level project explorer; never connects to the robot."""
import json
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parents[2]
APP = 'robot/android/maxbot/src/main/java/org/maxbot/app/'
CORE = 'robot/android/maxbot-core/src/main/java/org/maxbot/core/'
FW = 'firmware/maxbot/maxbot.ino'
BENCH = 'firmware/calibration/calibration.ino'
LOGIC = 'firmware/calibration/calibration_logic.h'
WEB = 'controller/node-js/maxbot/'
TOOLS = 'tools/maxbot/'


def reference(path, symbol=''):
    file = ROOT / path
    if not file.is_file():
        raise ValueError('Missing architecture source: ' + path)
    lines = file.read_text().splitlines()
    hits = [i for i, line in enumerate(lines, 1) if symbol and symbol in line]
    if symbol and not hits:
        raise ValueError('Missing source symbol: ' + path + ' :: ' + symbol)
    return {'path': path, 'line': hits[0] if hits else 1, 'symbol': symbol}


def node(key, title, description, *, source=None, children=None, facts=None,
         interfaces=None, notes=None, status='Code', technology='', related=None):
    return dict(id=key, title=title, description=description, source=source,
                children=children or [], facts=facts or [], interfaces=interfaces or [],
                notes=notes or [], status=status, technology=technology, related=related or [])


def component(key, title, description, path, units, **kw):
    children = []
    for index, (label, symbol, explanation) in enumerate(units, 1):
        children.append(node(key + '-unit-' + str(index), label, explanation,
                             source=reference(path, symbol), technology='Code / contract',
                             facts=[['Symbol / anchor', symbol or pathlib.Path(path).name]]))
    return node(key, title, description, source=reference(path), children=children, **kw)


def edge(a, b, label, detail, kind='data', duplex=False):
    return dict(source=a, target=b, label=label, detail=detail, kind=kind, duplex=duplex)


def group(key, title, description, children, interfaces=None, **kw):
    return node(key, title, description, children=children, interfaces=interfaces, **kw)


def build_data():
    perception = group('perception', 'Camera & perception', 'One ARCore camera feeds localization, cone detection and frame consumers.', [
        component('camera', 'ARCore camera', 'Captures the image and pose together, renders the local preview and compensates the camera lever arm.', APP+'ArCamera.java', [
            ('Acquire image and pose', 'onDrawFrame', 'A single ARCore session provides timestamp-aligned camera image, tracking state and localization metadata.'),
            ('Copy reusable YUV data', 'private static void copy', 'Copies camera planes into an available frame slot; the camera image is released instead of held by slow consumers.'),
            ('Render camera preview', 'drawPreview', 'OpenGL draws the camera texture without opening a second camera.')], technology='ARCore · OpenGL', facts=[['Capture profile','640 × 480 · 30 fps request'],['Speed','Vision / ARCore estimate, not an encoder']]),
        component('frames', 'Latest-frame pool', 'Three reusable YUV slots bound memory and replace stale pending work with the newest image.', CORE+'LatestFramePool.java', [
            ('Acquire a free slot', 'acquireForCapture', 'The producer gets a reusable slot only when it is not owned by another consumer.'),
            ('Publish / take latest', 'takeLatest', 'Pending frames are replaced; inference consumes the freshest available frame rather than a growing queue.'),
            ('Retain / release ownership', 'public synchronized void release', 'Reference counts coordinate inference, video and recording without reusing a buffer that is still in use.')], technology='Java · bounded buffers'),
        component('detector', 'Orange-cone detector', 'Exact manifests select YOLOv5n or explicitly exported YOLO26n decoders; missing real weights remain a dependency.', APP+'ConeDetector.java', [
            ('Run inference', 'List<Detection> detect', 'Input is letterboxed RGB 320 × 320. Actual tensors, dtype, quantization and decoder must match the supplied manifest.'),
            ('Decode / suppress boxes', 'private static float iou', 'Reusable candidate buffers and intersection-over-union suppression bound postprocessing.'),
            ('Refine orange support', 'static boolean isOrange', 'Checks image color near detections before ground projection; this is not a substitute for a trained detector.')], technology='LiteRT · CPU or GPU', status='Model required'),
        component('geometry', 'Ground geometry', 'Transforms points between vehicle/world coordinates and projects cone observations onto the floor.', CORE+'Geometry.java', [
            ('Ray to ground', 'public static Point groundRay', 'Combines camera ray and measured mount geometry to estimate a cone ground point.'),
            ('Vehicle / world transforms', 'public static Point world', 'Uses a common rear-axle pose convention for mapping and control.'),
            ('Oriented footprint clearance', 'public static double footprintDistance', 'Collision checks consider car width and front/rear overhang, not just a center point.')], technology='Java · geometry')
    ], [edge('camera','frames','YUV + pose','Timestamped image and metadata enter a reusable slot.'), edge('frames','detector','Newest frame','Inference takes and releases the latest available frame.'),edge('detector','geometry','Cone rays','Ground projection uses measured camera geometry.')])

    autonomy = group('autonomy', 'Mapping, planning & control', 'Autonomously scout four cones, verify the repositioned start, then execute the alternating slalom.', [
        component('course-map', 'Four-cone course map', 'Clusters stable cone landmarks and validates the straight, equally spaced course.', CORE+'CourseMap.java', [
            ('Accumulate observations', 'public synchronized void observe', 'Associates observations across frames and rejects an inconsistent landmark count.'),
            ('Validate / freeze the map', 'public synchronized void freeze', 'Requires four stable landmarks and checks course geometry before retaining a timed-run map.'),
            ('Verify observations after reposition', 'observationsAgree', 'New detections must agree with the retained course map before the timed run can start.')]),
        component('trajectory', 'Reference sampling / legacy comparison', 'Interpolates the Mac reference; the old left-first quintic remains only for optional comparison tests.', CORE+'Trajectory.java', [
            ('Plan alternating passes', 'public static Trajectory plan', 'Legacy comparison only. Task 1 uses Mac-planned right/left/right/left references and rejects the nominal legacy path’s approximately 51.2 degree steering requirement.'),
            ('Constrain candidate paths', 'private static Trajectory candidate', 'Checks curvature, steering travel/rate, corridor, cone clearance, acceleration and braking constraints.'),
            ('Track nearest path sample', 'public int nearest', 'Relates the current rear-axle pose to progress along the planned path.')]),
        component('mac-planner','Stopped Mac planner','Multiseed constrained bicycle planning and independently validated retiming.',TOOLS+'planner.py', [('Plan candidates','def plan','Optimizes free heading and longitudinal placement, then ranks feasible retimed candidates by finish time.'),('Validate continuous footprint','def validate_path','Checks full rectangle, cones, floor, sides, steering and braking tail with conservative between-sample bounds.')]),
        component('reference','Phone reference validator','Rejects stale dependencies, invalid interpolation, swept collisions and infeasible braking tails.',CORE+'ReferenceTrajectory.java',[('Independent validation','List<String> validate','Matches map epoch and calibrated dependencies before staging.'),('Course/world transform','Trajectory trajectory(CourseMap','Applies the frozen course-to-localization transform.')]),
        component('policy','Pure pursuit / speed control','Initial cone_slalom_v1 tracker with yaw damping, measured feedforward and PI anti-windup.',CORE+'SlalomPolicy.java',[('Application-time state','followState','Uses bounded VIO/gyro prediction without a second independent covariance.'),('Feedback baseline','private RunSupervisor.Output update','Uses actual elapsed time, preview guards and calibrated angle/rate limits; no duplicate curvature feedforward.')]),
        component('mpc', 'Optional native predictive controller', 'Tracks the planned trajectory using a generated OSQP problem through JNI.', APP+'NativeMpc.java', [
            ('Build the reference horizon', 'public synchronized RunSupervisor.Output follow', 'Samples 20 future reference speeds and curvatures at 50 ms steps.'),
            ('Call native solver', 'native double[] solve', 'JNI solves for steering and acceleration; unavailable, infeasible or failed solves do not produce driving authority.'),
            ('Map speed demand to PWM', 'tractionMinPwm', 'Uses vision-estimated speed, measured traction deadband/scaling and bounded battery-voltage compensation; never treats PWM as measured speed.')], facts=[['Horizon','20 steps · 1 second'],['Native deadline','8 ms checked by JNI']]),
        component('supervisor', 'Run supervisor', 'The sole driving authority separates direct manual commissioning from calibrated autonomous operation.', CORE+'RunSupervisor.java', [
            ('Preflight gates', 'private boolean preflight', 'Manual requires valid actuator calibration and fresh operator/vehicle links; autonomous operation additionally requires complete geometry, model and tracking.'),
            ('Scouting / run state machine', 'public Output tick', 'Handles autonomous scout, reposition verification, ready, run, finish and stopped states.'),
            ('Manual / emergency authority', 'public synchronized boolean manual', 'Direct manual PWM reaches ±255 without a speed model or tracking; signed DRIVE_MANUAL authority, 100 ms deadman expiry and a neutral reversal pause apply. Emergency stop latches.')], facts=[['Control target','50 Hz'],['Operator freshness','500 ms'],['Manual commissioning','Separate preflight; no cone/model/VIO requirement']])
    ], [edge('course-map','mac-planner','Frozen map','Only adequately observed maps with independent scene bounds can freeze.'),edge('mac-planner','reference','Stopped reference','Complete path and braking tail with dependency hashes.'),edge('reference','policy','Validated samples','Only matching stopped artifacts activate.'),edge('policy','supervisor','Candidate command','Phone alone owns motion; stop does not wait for policy work.','control'),edge('trajectory','mpc','Optional comparison','MPC remains opt-in.')], notes=['The user repositions the car after autonomous scouting. Localization is verified before a timed run.'])

    phone_links = group('phone-links', 'Vehicle & operator links', 'The phone mediates commands, telemetry and local video. The browser does not drive the ESP32 directly.', [
        component('vehicle-link', 'ESP32 vehicle link', 'Operational Wi-Fi uses independent TX/RX, absolute expiry and motion epochs; legacy USB is deferred.', APP+'VehicleLink.java', [
            ('USB transport', 'private synchronized void startSerial', 'Opens the USB serial connection using the generated baud rate (default 460800).'),
            ('Handshake / clock alignment', 'private synchronized void hello', 'Checks boot identity, timing and actuator-calibration fingerprint before command admission.'),
            ('Receive telemetry', 'private synchronized void receive', 'Verifies telemetry HMAC/session freshness; reconnecting does not automatically rearm.')], facts=[['Wi-Fi operational','Motion UDP 4242; direct stop 4243'],['Command TTL','100 ms']]),
        component('dashboard-link', 'Dashboard signaling link', 'An authenticated WebSocket carries telemetry, operator commands and WebRTC signaling.', APP+'DashboardLink.java', [
            ('Process challenge / messages', 'public void onMessage', 'Pairs against the local console and forwards commands to phone-side supervision.'),
            ('Report disconnects', 'public void onFailure', 'Connection loss is surfaced to the controller instead of creating synthetic operator heartbeats.')]),
        component('video', 'WebRTC video bridge', 'Hardware-encoded LAN video reuses the camera frames and pooled I420 storage.', APP+'VideoBridge.java', [
            ('Start a peer', 'synchronized void start', 'Creates the peer on the app thread; there are no public STUN/TURN servers.'),
            ('Receive signaling', 'synchronized void receive', 'Coordinates remote descriptions and queued ICE candidates.'),
            ('Publish selected frames', 'synchronized void frame', 'Pooled I420 buffers and a 15 fps limit bound optional streaming work.')], technology='WebRTC · hardware encoder'),
        component('protocol', 'Command authentication', 'Canonical integer messages and HMAC-SHA256 keep signing consistent across phone and firmware.', CORE+'Protocol.java', [
            ('Canonical command', 'public static String motion', 'Motion v2 signs boot/session/epoch/sequence, absolute expiry, sources, calibration, wheel millidegrees and logical PWM.'),
            ('Sign / verify', 'public static boolean verify', 'Uses reusable cryptographic state and constant-time signature comparison.')])
    ], [edge('vehicle-link','protocol','HMAC messages','Both transports use the same canonical command representation.'),edge('dashboard-link','video','SDP / ICE','Video signaling is relayed over the operator socket.')])

    phone_runtime = group('phone-runtime', 'App lifecycle, logging & performance', 'Schedules independent work, records optional training data and stops on unhealthy timing or thermal state.', [
        component('activity', 'MaxBot Android activity', 'Owns the native car-focused UI and workers, without initializing the upstream OpenBot app.', APP+'MaxBotActivity.java', [
            ('Schedule control', 'private void tick', 'Uses explicit monotonic deadlines; a missed deadline during motion stops rather than running catch-up control.'),
            ('Schedule inference', 'private void scheduleInference', 'Dedicated workers process fresh frames and publish detections to mapping/supervision.'),
            ('Import / lifecycle / thermal', 'protected void onPause', 'Pausing disarms. Imports are stopped-only; thermal pressure reduces optional work and severe pressure stops motion.')]),
        component('recorder', 'Run recorder', 'Bounded queues save telemetry/events, selected clean frames and optional hardware video.', APP+'RunRecorder.java', [
            ('Select clean training frames', 'void frame', 'A copied optional buffer allows 2 Hz image collection without holding the control frame.'),
            ('Background writer', 'private void write', 'Drops optional work under pressure and reports recording errors.'),
            ('Export a run', 'void export', 'ZIP bundles clean images, events and any recorded video.')]),
        component('hardware-recorder', 'Local H.264 recorder', 'Lazy hardware AVC encoding records selected 640 × 480 frames.', APP+'HardwareRecorder.java', [
            ('Encode / drain', 'private void encode', 'Uses hardware codec capability checks; no silent software-encoding fallback.'),
            ('Write muxed output', 'private void drain', 'MediaMuxer writes MP4 output in the background.')]),
        component('latency', 'Latency statistics', 'Bounded latency samples expose p50, p95 and p99 measurements.', CORE+'LatencyStats.java', [
            ('Collect samples', 'public synchronized void add', 'A bounded sample history avoids unbounded telemetry memory.'),
            ('Snapshot percentiles', 'public synchronized double[] snapshot', 'Performance targets are acceptance goals until measured on the actual Pixel.')]),
        component('phone-calibration', 'Calibration gates', 'Autonomous missing() and manualMissing() are separate. Unconfirmed physical checks stay false; the explicit manual profile enables direct supervised tests.', CORE+'Calibration.java', [
            ('List missing requirements', 'public List<String> missing', 'Checks dimensions, steering table, power, dynamics, transport and commissioning flags.'),
            ('Validate nonlinear steering map', 'public boolean validSteeringTable', 'Accepts monotonic ascending or descending ADC tables, including zero angle and both endpoints.'),
            ('Stopping-distance admission', 'public double stoppingDistance', 'Uses measured braking, input age and clearance; unknown braking yields no finite stopping guarantee.')])
    ])
    phone = group('phone','Pixel 5 brain','Camera perception, autonomous scouting, slalom control and all driving decisions run on the phone.',[perception,autonomy,phone_links,phone_runtime],technology='Android · Java · C/JNI',facts=[['Responsibility','All online autonomy runs locally'],['Inference','Trained TFLite model required']])

    motors = group('motor-control','Traction & steering control','Runtime low-level actuation in maxbot.ino; firmware uses generated measured calibration.',[
        component('traction','Traction H-bridge','Signed PWM and measured drive polarity drive the single motor coupled to all four wheels.',FW,[('Bridge direction','void bridge','Positive/negative demand selects opposite TA6586 inputs; zero uses the verified configured brake behavior.'),('Apply traction PWM','DRIVE_POLARITY','IN1/IN2 pins are GPIO4/GPIO16. Drive polarity is +1 in the saved measurements.')],facts=[['PWM range','Logical ±255 → physical ±170 (3S)'],['PWM carrier','10 kHz · 8-bit']]),
        component('runtime-steering','Runtime steering PID','Potentiometer angle feedback controls the steering DC motor through the second bridge.',FW,[('Periodic feedback controller','void steeringLoop','Runs at nominal 200 Hz using the runtime firmware’s fixed 5 ms PID calculation.'),('Steering motor polarity','STEER_POLARITY','Generated +1 polarity maps controller-positive output to positive-left wheel angle.'),('Travel and stall protection','STEERING_STALL','Stops outward steering at measured bounds and faults on sustained effort without movement.')],notes=['Runtime PID uses a different implementation from the newer bench PID helper. Exported gains must be revalidated before driving commissioning.'])
    ],[edge('runtime-steering','traction','Shared command','One admitted drive command supplies target angle and traction PWM.','control',True)])
    sensing=group('sensing','Feedback & sensing','Steering feedback comes from the MCU; speed is estimated on the phone. Battery monitoring is disabled in the current configuration.',[
        component('adc-angle','ADC → wheel angle','Piecewise linear interpolation uses every saved angle/ADC pair.',FW,[('Descending ADC interpolation','float angleFromAdc','Finds adjacent table entries and interpolates between them; center is ADC785 at 0°.'),('Read steering feedback','void sensors','Reads GPIO35 ADC1 and detects ADC rails or travel outside allowed limits.')],facts=[['Measured working limits','−26° … +26°'],['Samples','7 measured angle points']]),
        component('pack-sensing','Optional battery monitor','Disabled: the operator checks the pack before each run, aiming for approximately12 V. No battery ADC, arming gate, automatic low-voltage stop or voltage compensation is active.',FW,[('Optional ADC conversion','DIVIDER_RATIO','ADC scale/offset and divider ratio are used only when BATTERY_MONITORING_ENABLED is true. The disabled branch reads no battery GPIO.'),('Conditional low-voltage gate','batteryHealthy','Returns true without a voltage sample when monitoring is disabled; reenabled monitoring restores low-voltage checks.')],status='Disabled',facts=[['Battery input','Not configured; GPIO25 not read'],['Manual target','Approximately12 V before each run; not telemetry'],['Voltage / percentage','Unavailable']])
    ])
    mcu_transport=group('mcu-transport','USB / UDP protocol','The same boot/session command protocol is used over USB and Wi-Fi UDP.',[
        component('mcu-receive','Command receiver','Admits signed, ordered, timely commands only.',FW,[('Handshake','if(type=="hello")','Returns boot identity, MCU clock and actuator-calibration fingerprint for an authenticated challenge.'),('Validate command','void receive','Checks HMAC, boot, session, increasing sequence, 100 ms TTL, angle range and signed PWM.'),('Authority modes','mode=="ARM"','ARM is explicit; STOP, DISARM, ESTOP and RESET have separate authority semantics.')]),
        component('mcu-telemetry','Telemetry publisher','Emits steering angle, pack millivolts, ADCs, applied request state and faults.',FW,[('Publish state','void telemetry','Nominal 50 Hz telemetry includes boot/session/time/sequence and authentication.'),('Choose transport','void reply','Serial JSON lines and UDP replies share the same document format.')])
    ],[edge('mcu-receive','mcu-telemetry','Command / state','Telemetry acknowledges MCU state rather than assuming command delivery.','control',True)])
    mcu_safety=group('mcu-safety','Independent MCU stops','Stops still apply if the phone or local network stops behaving correctly.',[
        component('watchdog','Motor deadline / freshness watchdog', 'A separate nominal 200 Hz task enforces absolute expiry; missing commands also trip a 150 ms watchdog.',FW,[('Independent motor task','void motorTask','The dedicated motor task checks watchdog and absolute command expiry independently of networking.'),('Do not renew stale steering','lastMotion>100','Holding a fresh STOP does not keep an old steering target energized.')],technology='MCU deadline'),
        component('stop-latch','Stop / arming gates','Calibration, initialization, optional physical stop and emergency state gate motor authority.',FW,[('Halt outputs','void halt','Zeros demand, clears control history and removes authority on a fault.'),('Optional active-low stop','physicalStopReleased','A configured physical-stop input latches an emergency stop. Null means no switch installed; GPIO reads/setup are skipped.'),('Preserve setup failure','outputsReady','A failed PWM initialization cannot be cleared into a successful ARM without a successful restart.')])
    ])
    firmware=group('firmware','ESP32 runtime','Authenticated low-level motor control, steering feedback and independent stopping.',[motors,sensing,mcu_transport,mcu_safety],technology='Classic ESP32 · Arduino',source=reference(FW),facts=[['Control loop','200 Hz nominal'],['Watchdog','150 ms'],['Current commissioning','Manual profile ready with explicit ARM_MANUAL; autonomy locked'],['Zero / stop output','Verified electrical brake; floor stopping unmeasured'],['Wi-Fi','MaxBot-Drive ESP32 hotspot; Pixel and Mac join']])

    server=group('console-server','Local HTTP / WebSocket server','A Node service serves the operator page and mediates one phone and one operator.',[
        component('http-server','HTTP service','Serves the dashboard and local fonts using an explicit route allowlist.',WEB+'server.js',[('Start service','function start','Default bind is loopback:8081; an explicit LAN bind lets the phone connect.'),('WebSocket upgrades',"server.on('upgrade'",'Only /robot and /operator roles are accepted; browser origins are checked.'),('Health / shutdown',"route === '/health'",'Health reports role connectivity; shutdown closes sockets and timers.')]),
        component('hub','Authenticated session hub','Pairs roles by HMAC challenge, relays messages and monitors operator freshness.',WEB+'hub.js',[('Pair a role','attach (peer, role)','A nonce challenge establishes one authenticated robot and one operator.'),('Validate / relay','receive (peer, raw)','Rejects duplicate/out-of-order operator sequence numbers and unsupported commands.'),('Expire supervision','tick ()','A 500 ms operator-heartbeat expiry detaches the operator and notifies the robot.')])
    ],[edge('http-server','hub','Role sockets','HTTP upgrade binds authenticated robot/operator sessions.')])
    operator=group('operator-ui','Browser supervision','The browser displays the phone’s video/telemetry and requests control through the phone.',[
        component('browser-video','Video & screenshots','Receives WebRTC video and exports the displayed frame with telemetry.',WEB+'public/app.js',[('Receive peer signaling','async function signal','Negotiates a local peer and queues ICE until the remote description is set.'),('Screenshot download',"$('screenshot').addEventListener",'Exports a displayed-frame image and a telemetry sidecar, separate from clean training images.')]),
        component('browser-controls','Deadman & stop controls','Manual takeover requires a held control; release or focus loss stops the request.',WEB+'public/app.js',[('Manual requests','function manual','Steering and signed PWM are sent while the manual deadman is held.'),('Release / hidden page','function release','Releases manual authority and sends stop behavior on focus/visibility changes.'),('Emergency stop',"$('estop').addEventListener",'Requests a latched emergency stop; the UI tells the operator to verify MCU acknowledgement.')]),
        component('browser-telemetry','Telemetry & map','Shows measured/estimated values, cone map, detections and readiness.',WEB+'public/app.js',[('Update status','function render','Renders phone-provided state without fabricating vehicle telemetry.'),('Draw map / boxes','function drawMap','Displays detected landmarks/path and video-box overlays.')])
    ])
    console=group('console','Operator computer','LAN browser console for video, telemetry, screenshots, emergency stop and manual takeover.',[server,operator],technology='Node.js · WebSocket · browser',facts=[['Dashboard','HTTP 8081'],['Control path','Browser → hub → phone → ESP32']])

    bench=group('bench','Standalone bench tool','A separate flashed sketch hosts the ESP32 calibration AP; it is not the runtime driving service.',[
        component('bench-pwm','Direct PWM calibration','Manual pulses bypass PID and identify per-direction starting PWM.',BENCH,[('Validate / start pulse','Pulse requires PWM','Accepts PWM1–255 and pulse durations20–250 ms on either bridge.'),('Record movement','void finishPulse','Reports PWM, duration and steering ADC change after a completed pulse.'),('Record starting threshold','"recordPwm"','Requires explicit observed movement; steering also needs at least eight ADC counts.')],facts=[['Starting PWM','Steering110/110 · traction120/120'],['Use','Metadata only; no automatic PID floor']]),
        component('bench-pid','Bench PID helper','Accepts finite nonnegative gains without arbitrary upper ceilings; clamps the final PWM.',LOGIC,[('PID calculation / anti-windup','inline PidResult pidStep','Uses measured dt, a20 ms measurement-derivative filter and conditional integration.'),('Physical output cap','inline int clampPwm','Clips logical demand to the selected cap within ±255; the shared bridge mapping then limits physical duty to ±170 on 3S.'),('Angle interpolation','inline float angle','Uses the measured monotonic angle table to convert feedback into wheel angle.')],facts=[['Default cap','255'],['Test window','100–5000 ms'],['Implementation','Separate from runtime steering PID']]),
        component('bench-ui','Calibration website','Dedicated PWM, angle-table and PID sections place feedback next to the initiating control.','firmware/calibration/dashboard.h',[('Issue local actions','function action','Every initiated action is associated with its own button and sequence.'),('Deliver per-button feedback','function deliver','Immediate responses and retained events avoid dropping fast completions; stale acknowledgements cannot reopen terminal results.'),('Freshness / availability','function availability','Disables motion controls with stale telemetry and explains readiness locally.')]),
        component('bench-worker','Independent motor worker','A FreeRTOS task owns output deadlines independently from HTTP processing.',BENCH,[('Supervise at200 Hz','void controlTask','Handles pulse/PID deadlines, heartbeat expiry, feedback limits and stalls.'),('Retain completion events','ActionEvent actionEvents','A16-entry ring delivers asynchronous results to the correct button; overflow is reported.'),('Serve local API','void stateRoute','/api/state, /api/action and /api/export expose calibration state and measured exports.')])
    ])
    saved=group('saved-calibration','Saved measurements & generation','A versioned repository JSON patch supplies measured data; private credentials remain separate.',[
        component('saved-json','Recorded calibration','Photo-derived steering pairs, Tracker geometry and supplied PID/PWM values are saved in the calibration folder.','firmware/calibration/calibration.json',[('Steering lookup','"adc"','ADC1158/975/849/785/688/608/461 maps to −26/−16/−7/0/10/16/26 degrees.'),('PID and mechanics','"travelTimeS"','Wheelbase0.173 m; front overhang0.03894 m; front track0.127 m. Travel0.059999999 s; Kp50 Ki3 Kd1.3; cap255; deadband3.5°.'),('Unconfirmed inputs','"commissioning"','Power-wiring, steering confirmation and brake checks remain false. Battery monitoring is disabled; physical stop is absent and optional.')],status='Recorded',related=['firmware/calibration/geometry-measurements.json']),
        component('config-generator','Configuration generator','Merges public baseline, saved measurements and private overrides, then creates matching outputs.',TOOLS+'configure.py',[('Read / merge sources','def main','Precedence: versioned app defaults → saved calibration → private overrides. README is a legacy bundle fallback.'),('Build actuator identity','def actuator_id','Hashes steering, power, manual profile, actuator pins/polarities/brake state and serial baud.'),('Generate firmware gates','def firmware_header','Emits calibration.generated.h, Android asset calibration.json and maxbot.runtime.json. Actuator-ready manual commissioning and fully commissioned autonomous authority have separate gates.')]),
        component('commissioning','Commissioning checklist','Recorded dimensions and lens offsets are available; 55 mm rear overhang is confirmed; camera rotation, dynamics and floor stopping remain unconfirmed. Battery sensing is not a requirement in manual-check mode.','README.md',[('Required measurements','"widthM"','Full width144 mm and lens offset50.90 mm left are recorded after centering the phone. Rear overhang is 55 mm; cones are 200 mm diameter × 150 mm high with 500 mm edge gaps. Camera pitch/yaw, enclosing flange and operating-space measurements remain required.'),('Power and braking','"powerVerified"','Verify motor-driver power wiring and electrical brake behavior. Battery voltage is checked manually before each run; a physical-stop input is optional.'),('Performance acceptance','Before increasing speed','Validate timing/tracking and correct collision-free baseline runs before increasing operating limits.')],status='Needs measurement')
    ])
    calibration=group('calibration','Calibration & configuration','Bench measurements and saved data configure the car and gate autonomous operation.',[bench,saved],technology='ESP32 AP · JSON · Python',facts=[['Feedback','GPIO35 · ADC1'],['Saved map','7 points · ±26°'],['Bench URL','192.168.4.1']])

    datasets=group('datasets','Run data & dataset preparation','Real car runs supply training images and human-reviewed labels.',[
        component('image-extraction','Clean-frame extraction','Selected run frames or ffmpeg extraction supply images without UI overlays.',TOOLS+'dataset.py',[('Command-line extraction','def main','Extracts frames from run video at a selected sampling rate; training images must remain clean.'),('Validate YOLO labels','def validate_label','Checks class, finite normalized boxes and nonzero dimensions; empty frames need empty label files.')]),
        component('dataset-split','Session-based partitions','Whole sessions form train/validation/test partitions to reduce neighboring-frame leakage.',TOOLS+'dataset.py',[('Split complete sessions','def split','Produces dataset structure and provenance without distributing adjacent frames from one run across partitions.'),('Detect corruption / duplication','def validate_dataset','Validates label coverage and manifest hashes before training.'),('Collision suggestions','def collision_candidates','Persistent cone displacement is labeled only as a collision candidate requiring review.')])
    ])
    models=group('model-training','YOLO training & deployment','A separate workstation fine-tunes and exports the detector; inference stays on Pixel.',[
        component('training-wrapper','YOLOv5n training wrapper','Uses pinned YOLOv5v7.0 and a separate Python3.10/3.11 training environment.',TOOLS+'train.py',[('Train with reviewed data','def main','Validates the dataset, checks the exact YOLO tag/commit and invokes training on CPU or configured CUDA.'),('Export / check tensor contract','for quantized','Exports TFLite artifacts and verifies 320×320 RGB input with decoded [1,6300,6] predictions.'),('Held-out evaluation / provenance','deployment_status','Evaluates exports on the locked test partition and writes provenance; exports remain NOT_VALIDATED_ON_PIXEL until profiled.')],status='Data / model required')
    ])
    numerical=group('numerical-tools','Native solver & verification','Offline generation and tests build the fixed-size controller and check complete software workflows.',[
        component('solver-generation','OSQP code generation','Generates the constrained MPC matrices, embedded C solver and sparse structure header.',TOOLS+'generate_mpc.py',[('Fixed problem matrices','def matrices','Builds the sparse120-variable/200-constraint formulation used by JNI.'),('Generate native source','def main','Emits code into the ignored generated solver directory; fresh builds need this generation step.')]),
        component('jni','Independent native preprocessing','Strided camera packing and normalized model tensors need no generated solver.','robot/android/maxbot/src/main/cpp/image_jni.c',[('Fused preprocessing','Java_org_maxbot_app_ConeDetector_prepare','Converts YUV and letterboxes directly into the detector tensor.'),('Camera plane packing','Java_org_maxbot_app_ArCamera_packPlanes','Bounds-checks row/pixel strides and owned buffers.')],related=['robot/android/maxbot/src/main/cpp/mpc_jni.c']),
        component('validation','Software verification','Control, simulator, firmware, dataset and network checks validate software behavior.','tools/maxbot/NativeControllerTest.java',[('Numerical/controller checks','public static void main','Exercises the actual JNI/generated solver on the host.'),('Optional controller gate','private static void verifyOptionalGate','Native availability cannot bypass Mac reference or launch verification. Shared synthetic fixtures test the initial tracker independently.')],related=[TOOLS+'test-core.sh',TOOLS+'test-calibration.sh',TOOLS+'tests/test_tools.py',WEB+'test/integration.test.js',TOOLS+'firmware_fixture.py']),
        component('pixel-profiling','Pixel performance helper','Inspect CPU/memory/thermal state and optionally stop an explicit allowlist of unused apps.',TOOLS+'pixel-profile.sh',[('Inspect device','inspect','Reports device state for sustained profiling; no latency target is treated as measured automatically.'),('Stop selected apps','stop-unused','Preserves MaxBot, ARCore and essential Google services; never disables thermal protection.')])
    ])
    training=group('training','Offline learning & tooling','Training, dataset preparation, solver generation and verification run on separate compute.',[datasets,models,numerical],technology='Python · C/C++ · test runners',notes=['No trained cone model or reviewed real-car dataset is fabricated by the repository.'])

    physical_drive=group('physical-drive','Mechanical drivetrain','A1:16 car uses a standard traction motor and a five-wire steering actuator.',[
        component('four-wheel-drive','4WD traction assembly','One traction DC motor drives the mechanically coupled four-wheel drivetrain.','firmware/maxbot/hardware doc.md',[('Traction pins','GPIO 4','TA6586 IN1=GPIO4, IN2=GPIO16. Forward polarity is recorded as +1.'),('No wheel encoder','Car specs','There is no added wheel-position/speed encoder; phone localization estimates speed.')],technology='DC motor · TA6586'),
        component('five-wire-servo','Five-wire steering assembly','Two motor wires go to an H-bridge and the three potentiometer wires provide position feedback.','firmware/maxbot/hardware doc.md',[('Steering bridge','GPIO 22','TA6586 IN1=GPIO22, IN2=GPIO23; wheel angle is closed-loop controlled.'),('Potentiometer feedback','GPIO35','Divider output goes to GPIO35 ADC1, moved from original GPIO14 ADC2 for Wi-Fi compatibility.'),('Measured vehicle geometry','173mm','Tracker wheelbase173 mm supersedes168.5 mm; front overhang38.94 mm; front tire-center spacing127 mm. Working wheel angles are ±26°, separate from roughly200° shaft travel.')])
    ])
    physical_power=group('physical-power','Power & optional sensing','Motor power and ESP32 logic power have different supply paths.',[
        component('power-distribution','3S power distribution','A3S LiPo feeds appropriately rated motor drivers; a buck converter supplies the ESP32.','README.md',[('Motor / buck branches','Power: 3S','The 12.6 V maximum supply feeds the drivers; both motor PWM outputs map ±255 to ±170. The buck supplies regulated 5 V. Input/current ratings require commissioning.'),('USB power interaction','VBUS','USB VBUS isolation, logic levels, potentiometer voltage and decoupling need physical verification.')],status='Commissioning'),
        component('unwired-sensors','Manual battery checks / optional stop','Battery monitoring is disabled and the physical-stop switch is absent. Neither is an arming requirement in this configuration.','firmware/calibration/calibration.json',[('Disabled voltage input','"batteryPin"','Null means no runtime ADC input; the old GPIO25 buck-output circuit is not read. Operator checks the pack around12 V before each run.'),('Optional physical stop','"physicalStopPin"','Null does not block driving; if a switch is installed later, its distinct supported active-low input remains enforced.')],status='Optional inputs disabled')
    ])
    environment=group('environment','Phone mount & cone course','The physical scene supplies the visual observations used for speed, localization and mapping.',[
        component('camera-mount','Pixel5 camera mount','The centered phone retains lens height99.24 mm and rear-axle forward offset60.94 mm. Latest Tracker label places the lens50.90 mm left; optical-axis pitch/yaw remain unconfirmed.','README.md',[('Camera extrinsics','"camera"','Rear-axle forward offset =173+38.94−151=60.94 mm. Lateral lens offset is +50.90 mm left, not zero despite the centered phone. Pitch/yaw remain required.'),('Floor tracking','textured floor','Indoor wooden-floor texture supports visual tracking, but actual tracking quality must be tested.')],status='Needs measurement',related=['firmware/calibration/geometry-measurements.json']),
        component('cone-course','Four orange cones','Equal spacing along a straight line defines the slalom task.','README.md',[('Autonomous scout','select **Scout**','A bounded one-sided segment permits an occluded launch while keeping hidden cones as priors and checking the conservative row envelope.'),('Reposition / verify / run','Reposition at the original start','The operator returns the car; retained localization and cone observations must agree before right/left/right/left passes.'),('Collision review','Cone-motion collision','Manual labels or motion-based suggestions evaluate runs; off-camera contacts cannot be ruled out.')])
    ])
    physical=group('physical','Car, power & test floor','The real motors, feedback, camera scene and power system close the autonomy loop.',[physical_drive,physical_power,environment],technology='1:16 vehicle · physical environment',facts=[['Wheelbase','173 mm'],['Full width','144 mm · phone centered'],['Front track','127 mm · not full outside width'],['Encoder','None'],['Cones','4 bright orange · indoor wooden floor']])

    upstream=group('upstream','Preserved OpenBot baseline','Original OpenBot applications and documentation remain as reference; MaxBot uses separate modules.',[
        group('upstream-apps','Original app / controller','The upstream robot and controller are preserved alongside MaxBot.',[
            component('upstream-readme','Upstream documentation','The original README is preserved separately.','README.openbot.md',[('Original project','OpenBot','Reference documentation for the upstream platform and repository structure.')],status='Reference'),
            component('upstream-android','Android project / common libraries','MaxBot joins the Android build as separate :maxbot and :maxbot-core modules.','robot/android/settings.gradle',[('Module inclusion',"maxbot",'Separate modules preserve the original app while sharing compatible build/common dependencies.')],status='Reference',related=['robot/android/comlib/download.gradle'])
        ],status='Reference'),
        group('upstream-platform','Original firmware / policies / body','Preserved upstream material is not automatically the active car implementation.',[
            component('upstream-firmware','Original OpenBot firmware','The car uses firmware/maxbot rather than the upstream differential-drive sketch.','firmware/openbot/openbot.ino',[('Preserved sketch','','Original firmware reference; not the selected TA6586/five-wire steering implementation.')],status='Reference'),
            component('upstream-policy','Original policy tooling','Upstream policy training/frontends are retained as reference.','policy/README.md',[('Policy documentation','','MaxBot’s cone-model pipeline is separately implemented under tools/maxbot.')],status='Reference',related=['policy/frontend/README.md']),
            component('upstream-body','Original body designs','Existing OpenBot chassis/body documentation remains available.','body/README.md',[('Body documentation','','The current 1:16 car geometry is supplied by the hardware notes and calibration JSON.')],status='Reference')
        ],status='Reference')
    ],status='Reference',technology='OpenBot repository')

    root=group('system','MaxBot system','An offline Pixel-powered car scouts four cones, verifies its start pose, then runs an alternating slalom under local operator supervision.',[console,phone,firmware,training,calibration,physical,upstream],[
        edge('console','phone','WebRTC + WS','Duplex LAN link: video/telemetry to the browser; supervision, manual and stop requests to the phone.','data',True),
        edge('phone','firmware','Wi-Fi / UDP4242','Duplex signed commands and steering telemetry; battery voltage is unavailable while monitoring is disabled.100 ms command TTL and150 ms MCU watchdog.','control',True),
        edge('console','firmware','Stop-only UDP4243','Separate authenticated operator role latches zero and receives an applied acknowledgement; it never owns motion.','control',True),
        edge('training','phone','Models / runs','Real run exports go to separate compute; evaluated TFLite models return to the phone.','data',True),
        edge('calibration','phone','JSON config','Measured calibration becomes an Android asset/runtime import plus an actuator fingerprint.'),
        edge('calibration','firmware','Generated header','Actuator measurements generate the runtime firmware header. The bench sketch is a separate flashed mode.'),
        edge('firmware','physical','PWM / ADC','MCU controls H-bridges and reads steering/pack feedback.','control',True),
        edge('physical','phone','Camera + VIO','The mounted camera sees floor texture and cones; ARCore supplies a visual motion estimate.'),
        edge('upstream','phone','Build foundation','Preserved Android build/common dependencies support separate MaxBot modules.')
    ],facts=[['Mission','Autonomous scout → operator reposition → verified timed slalom'],['Driving authority','Phone supervisor + independent MCU stops'],['Diagram','Source-based documentation, not live telemetry']],notes=['This is C4-inspired, adapted for the project’s software, physical hardware and offline tooling. All four zoom levels are locally navigable.','Driving firmware and standalone calibration firmware are separate programs on the same ESP32; they are not concurrent services.','The saved commissioning flags remain false. A diagram or passing software test does not establish physical readiness.'])
    calibration_data=json.loads((ROOT/'firmware/calibration/calibration.json').read_text())
    return dict(root=root,savedCalibration=calibration_data,reference='https://c4model.com/diagrams',schemaVersion=1)


def validate(data):
    ids=set()
    def walk(n,depth=0):
        if n['id'] in ids:raise ValueError('Duplicate node: '+n['id'])
        ids.add(n['id'])
        if depth>4:raise ValueError('Too many architecture levels: '+n['id'])
        if depth<4 and not n['children']:raise ValueError('Missing drill-down: '+n['id'])
        children={c['id'] for c in n['children']}
        for e in n['interfaces']:
            if e['source'] not in children or e['target'] not in children:raise ValueError('Nonlocal interface: '+n['id'])
        for path in n['related']:
            if not (ROOT/path).is_file():raise ValueError('Missing related source: '+path)
        for c in n['children']:walk(c,depth+1)
    walk(data['root'])
    return len(ids)


def main():
    data=build_data();count=validate(data)
    payload=json.dumps(data,ensure_ascii=False,separators=(',',':')).replace('<','\\u003c')
    template=(ROOT/'docs/system-diagram.template.html').read_text()
    # Embed local fonts so a copied HTML remains usable without network access.
    import base64
    for weight,name in [('REGULAR','Regular'),('BOLD','Bold')]:
        font=ROOT/('controller/node-js/client/font/Gilroy/Gilroy-'+name+'.ttf')
        template=template.replace('__FONT_'+weight+'__',base64.b64encode(font.read_bytes()).decode())
    output=template.replace('__ARCHITECTURE_DATA__',payload)
    (ROOT/'docs/system-diagram.html').write_text(output)
    print('Built docs/system-diagram.html:',count,'nodes; all source anchors validated')


if __name__=='__main__':main()
