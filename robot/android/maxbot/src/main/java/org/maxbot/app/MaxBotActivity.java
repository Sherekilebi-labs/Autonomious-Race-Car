package org.maxbot.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.WindowManager;
import android.widget.*;
import com.google.gson.Gson;
import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;
import org.maxbot.core.*;

/** Dedicated local-only application. It never initializes upstream differential-drive services. */
public final class MaxBotActivity extends Activity {
  private static final int CALIBRATION = 10, MODEL = 11, EXPORT = 12, CAMERA_PERMISSION = 13;
  private final Gson gson = new Gson();
  private Calibration calibration;
  private RunSupervisor supervisor;
  private SlalomPolicy policy;
  private volatile VehicleLink vehicle;
  private volatile DashboardLink dashboard;
  private volatile VideoBridge video;
  private volatile RunRecorder recorder;
  private FrameFanout fanout;
  private ArCamera camera;
  private ImuRecorder imu;
  private ConeDetector detector;
  private ArtifactManager artifacts;
  private volatile String dynamicsId="",activeConfigId="",activeModelId="",activePolicyId="";
  private volatile boolean activating;
  private final java.util.concurrent.atomic.AtomicLong managementEpoch=new java.util.concurrent.atomic.AtomicLong();
  private final LatestFramePool frames = new LatestFramePool(640 * 480 * 3 / 2);
  private final ExecutorService inference =
      Executors.newSingleThreadExecutor(r -> new Thread(r, "maxbot-inference"));
  private final ScheduledExecutorService control =
      Executors.newSingleThreadScheduledExecutor(r -> new Thread(()->{android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_MORE_FAVORABLE);r.run();}, "maxbot-control"));
  private final ExecutorService files = Executors.newSingleThreadExecutor();
  private final AtomicBoolean inferenceScheduled = new AtomicBoolean();
  private volatile boolean active, closing, gpu = true, collection, thermalLimited,videoEnabled=true;
  private volatile String connection = "Disconnected",
      modelStatus = "Import a trained cone model",
      cameraStatus = "Waiting for tracking";
  private volatile JSONObject telemetry = new JSONObject();
  private volatile List<ConeDetector.Detection> detections = java.util.Collections.emptyList();
  private final ScheduledExecutorService statusPublisher = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r,"maxbot-status"));
  private volatile long lastControlNs,maximumControlGapNs;
  private volatile RunSupervisor.Output lastOutput = new RunSupervisor.Output("STOP",0,0);
  private android.net.wifi.WifiManager.WifiLock wifiLatencyLock;
  private volatile String wifiLatencyStatus="inactive";
  private long nextControlNs,lastRecordedFinish;

  private volatile long latestDetectionNs;


  private volatile double lastInferenceMs=Double.NaN, frameAgeMs=Double.NaN, steeringDeg, voltage;
  private TextView status, parameters;
  private PowerManager power;
  private PowerManager.OnThermalStatusChangedListener thermalListener;
  private String selectedModel = "cone.tflite";
  private int cpuThreads = 2;

  @Override
  public void onCreate(Bundle saved) {
    super.onCreate(saved);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    calibration = loadCalibration();
    policy=new SlalomPolicy(new SlalomPolicy.Parameters());
    supervisor=new RunSupervisor(calibration,policy);
    LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);layout.setPadding(24,24,24,24);layout.setBackgroundColor(Color.rgb(247,249,251));
    layout.addView(text("MaxBot · manual control",26));
    status=text("Starting services · disarmed",18);parameters=text("",14);
    layout.addView(status);layout.addView(parameters);
    // Keep a live GL surface for ARCore while presenting status indicators only.
    GLSurfaceView preview=new GLSurfaceView(this);layout.addView(preview,new LinearLayout.LayoutParams(1,1));
    setContentView(layout);
    power=(PowerManager)getSystemService(POWER_SERVICE);
    fanout=new FrameFanout(frame->{if(video!=null&&videoEnabled&&!thermalLimited)video.frame(frame);if(recorder!=null&&!thermalLimited)recorder.frame(frame);});
    camera =
        new ArCamera(
            this,
            preview,
            calibration,
            frames,
            new ArCamera.Listener() {
              public void localization(Geometry.Pose pose, boolean tracking, String reason) {
                if(reason.toLowerCase(java.util.Locale.ROOT).contains("reset"))supervisor.invalidateMap(reason);
                supervisor.localization(pose, tracking);
                if(recorder!=null&&pose!=null)try{recorder.event(new JSONObject().put("type","state").put("clock_domain","android_elapsed_realtime").put("state_id",Long.toString(pose.timeNs)).put("vio_id",Long.toString(pose.timeNs)).put("imu_id",Long.toString(imu==null?0:imu.latestGyroNs)).put("time_ns",Long.toString(pose.timeNs)).put("pose",new JSONArray(new double[]{pose.x,pose.y,pose.yaw,pose.speed})).put("position_error_m",calibration.camera.positionErrorM==null?JSONObject.NULL:calibration.camera.positionErrorM).put("heading_error_rad",calibration.camera.headingErrorRad==null?JSONObject.NULL:calibration.camera.headingErrorRad));}catch(Exception ignored){}
                cameraStatus = tracking ? "Tracking · speed estimated" : reason;
              }

              public void frame(LatestFramePool.Slot frame) {
                fanout.offer(frame);
                scheduleInference();
              }
            });
    try {
      recorder = new RunRecorder(this);
      recorder.snapshot("manifest.json",sessionManifest());
      imu=new ImuRecorder(this,()->recorder,calibration,(timestamp,rate)->supervisor.imu(timestamp,rate,true));
    } catch (IOException e) {
      message("Recording unavailable: " + e.getMessage());
    }
    thermalListener =
        state -> {
          thermalLimited = state >= PowerManager.THERMAL_STATUS_MODERATE;
          if (state >= PowerManager.THERMAL_STATUS_SEVERE)
            supervisor.stop("Phone thermal limit; cool down before rearming");
        };
    power.addThermalStatusListener(thermalListener);
    nextControlNs = SystemClock.elapsedRealtimeNanos();
    control.schedule(this::tick, 0, TimeUnit.NANOSECONDS);
    statusPublisher.scheduleWithFixedDelay(this::publishStatus,0,100,TimeUnit.MILLISECONDS);
    try{artifacts=new ArtifactManager(getFilesDir(),this::prepareArtifacts);inference.execute(()->{try{artifacts.restore();}catch(Exception e){modelStatus="Artifact restore blocked: "+e.getMessage();}});}catch(Exception e){modelStatus="Artifact storage unavailable";}
    connect();
  }

  private Calibration loadCalibration() {
    File privateFile = new File(getFilesDir(), "calibration.json");
    try (InputStream in =
        privateFile.exists()
            ? new FileInputStream(privateFile)
            : getAssets().open("maxbot/calibration.json")) {
      Calibration c = gson.fromJson(new InputStreamReader(in), Calibration.class);
      checkShape(c);
      return c;
    } catch (Exception e) {
      return new Calibration();
    }
  }

  private static void checkShape(Calibration c) {
    if (c == null
        || c.car == null
        || c.steering == null
        || c.camera == null
        || c.cones == null
        || c.power == null
        || c.limits == null
        || c.network == null
        || c.commissioning == null
        || c.manual == null
        || c.hardware == null) throw new IllegalArgumentException("Missing configuration sections");
  }

  private TextView text(String value, int size) {
    TextView t = new TextView(this);
    t.setText(value);
    t.setTextSize(size);
    t.setTextColor(Color.rgb(24, 37, 48));
    t.setPadding(0, 5, 0, 5);
    return t;
  }

  private boolean moving() {
    RunSupervisor.Mode mode = supervisor.mode();
    return mode == RunSupervisor.Mode.SCOUT
        || mode == RunSupervisor.Mode.RUN
        || mode == RunSupervisor.Mode.MANUAL;
  }

  private void message(String text) {
    runOnUiThread(() -> Toast.makeText(this, text, Toast.LENGTH_LONG).show());
  }

  private void armManual() {
    List<String> missing = calibration.manualMissing();
    if (!missing.isEmpty()) {
      message(String.join("; ", missing));
      return;
    }
    if (vehicle == null || !vehicle.connected()) {
      message("Connect the ESP32 first");
      return;
    }
    if(!telemetry.optString("fault").isEmpty()||telemetry.optBoolean("estop")){
      message("ESP32: "+telemetry.optString("fault","stop latched")+". Use Stop / disarm, then Reset stop before arming.");return;
    }
    supervisor.stop("Manual control: waiting for arm acknowledgement");
    vehicle.special("ARM_MANUAL");
  }

  private void connect() {
    if (moving()) {
      message("Stop before reconnecting");
      return;
    }
    if (vehicle != null) vehicle.close();
    if (dashboard != null) dashboard.close();
    if (video != null) {
      video.close();
      video = null;
    }
    vehicle =
        new VehicleLink(
            this,
            calibration,
            new VehicleLink.Listener() {
              public void status(boolean ok, String reason) {
                connection = reason;
                supervisor.link(ok);
              }

              public void telemetry(JSONObject m, long now) {
                if(!"telemetry".equals(m.optString("type"))){if(recorder!=null)recorder.event(m);return;}
                if(recorder!=null)recorder.actuator(m,now);
                steeringDeg = m.optInt("steering_mdeg") / 1000.;
                voltage = calibration.power.batteryMonitoring ? m.optInt("battery_mv") / 1000. : 0;
                telemetry = m;

                supervisor.telemetry(
                    steeringDeg, voltage, now, m.optBoolean("armed"), m.optString("fault"));
                if (m.optBoolean("estop")) supervisor.estop();
              }
            });
    vehicle.connect();
    if (calibration.network.pairingKey.length()>=32) {
      try {
        dashboard =
            new DashboardLink(
                this,calibration.network.dashboardUrl,
                calibration.network.pairingKey,
                new DashboardLink.Listener() {
                  public void disconnected() {
                    supervisor.stop("Dashboard disconnected");
                    if (vehicle != null) vehicle.special("DISARM");
                  }

                  public void command(JSONObject m) {
                    handleDashboard(m);
                  }
                });
        video =
            new VideoBridge(
                this,
                m -> {
                  DashboardLink d = dashboard;
                  if (d != null) d.send(m);
                });
      } catch (Exception e) {
        message("Dashboard/video: " + e.getMessage());
      }
    }
  }

  private void handleDashboard(JSONObject m) {
    long now = SystemClock.elapsedRealtimeNanos();
    String type = m.optString("type");
    switch (type) {
      case "artifact":
        if(artifacts==null)return;
        long generation=managementEpoch.get();
        Runnable work=()->{try{
          if(generation!=managementEpoch.get())throw new IllegalStateException("Activation interrupted by operator transition");
          String result=artifacts.receive(m,!moving()&&!telemetry.optBoolean("armed"));
          if(dashboard!=null)dashboard.send(new JSONObject().put("type","artifact_result").put("transaction",m.optString("transaction")).put("request_id",m.optString("request_id")).put("status","ok").put("message",result));
        }catch(Exception e){try{if(dashboard!=null)dashboard.send(new JSONObject().put("type","artifact_result").put("request_id",m.optString("request_id")).put("status","failed").put("message",e.getMessage()));}catch(Exception ignored){}}finally{activating=false;}};
        if("activate".equals(m.optString("operation"))){activating=true;inference.execute(work);}else files.execute(work);
        break;
      case "heartbeat":
        supervisor.heartbeat(now);
        break;
      case "operator_joined":
        runOnUiThread(
            () -> {
              if (video != null&&videoEnabled) video.start();
            });
        break;
      case "operator_left":
        supervisor.stop("Operator disconnected");
        if (vehicle != null) vehicle.special("DISARM");
        break;
      case "rtc":
        if (video != null) video.receive(m);
        break;
      case "command":
        String action = m.optString("action");
        if(activating&&!java.util.List.of("stop","estop").contains(action))return;
        if(java.util.List.of("stop","estop","manual","reset").contains(action))managementEpoch.incrementAndGet();
        switch (action) {
          case "arm":
            if (calibration.missing().isEmpty() && vehicle != null) vehicle.special("ARM");
            break;
          case "arm_manual":
            armManual();
            break;
          case "scout":
            supervisor.startScout(now);if(recorder!=null)recorder.snapshot("manifest.json",sessionManifest());
            break;
          case "run":
            boolean accepted=supervisor.startRun(now);lastRecordedFinish=0;
            if(recorder!=null)try{recorder.event(sessionManifest().put("type",accepted?"run_start":"run_rejected").put("phone_accepted_ns",Long.toString(now)).put("reference_id",supervisor.referenceId()).put("seed",Long.toString(m.optLong("seed",0))).put("fault",supervisor.fault()));}catch(Exception ignored){}
            break;
          case "stop":
            supervisor.stop("Dashboard stop");
            if(vehicle!=null)vehicle.special("DISARM");
            break;
          case "estop":
            supervisor.estop();
            if (vehicle != null) vehicle.special("ESTOP");
            break;
          case "reset":
            supervisor.reset();
            if (vehicle != null) vehicle.special("RESET");
            break;
          case "manual":
            supervisor.manual(
                m.optDouble("steering_deg", Double.NaN),
                m.optInt("pwm", 1000),
                m.optBoolean("deadman"),
                now,
                m.optLong("mapped_deadline_ns",now));
            break;
          case "freeze":
            try{supervisor.freezeMap(m.optDouble("error_bound_m",Double.NaN),m.optInt("independent_views",0));if(recorder!=null)recorder.snapshot("course.json",mapJson(supervisor.map()));}catch(Exception e){supervisor.stop(e.getMessage());}
            break;
          case "verify":
            // Verification is driven by fresh measured landmarks and launch pose, never this click alone.
            if(supervisor.mode()!=RunSupervisor.Mode.READY)supervisor.stop("Launch verification pending fresh landmark agreement");
            break;
          case "export":
            files.execute(()->{try{exportRun();}catch(Exception e){message("Export failed: "+e.getMessage());}});
            break;
          case "review":
            if(recorder!=null){recorder.event(m);recorder.snapshot("review.json",m);}
            break;
          case "benchmark":
            if(recorder!=null)recorder.event(m);
            break;
          case "collection":
            collection = m.optBoolean("enabled");
            if (recorder != null) recorder.collection(collection);
            break;
          default:
            break;
        }
        break;
      default:
        break;
    }
  }

  private void scheduleInference() {
    if (closing || !inferenceScheduled.compareAndSet(false, true)) return;
    inference.execute(
        () -> {
          LatestFramePool.Slot frame = frames.takeLatest();
          try {
            if (frame == null) return;
            if (active && detector != null) {
              long start = SystemClock.elapsedRealtimeNanos();
              List<ConeDetector.Detection> result = detector.detect(frame);
              lastInferenceMs = (SystemClock.elapsedRealtimeNanos() - start) / 1e6;
              frameAgeMs = (SystemClock.elapsedRealtimeNanos() - frame.timestampNs) / 1e6;
              detections = result;
              latestDetectionNs = frame.timestampNs;
              List<Geometry.Point> points = new ArrayList<>();
              for (ConeDetector.Detection d : result) if (d.ground != null) points.add(d.ground);
              if (recorder != null) {
                JSONObject event =
                    new JSONObject().put("type", "detections").put("image_available_ns", Long.toString(frame.timestampNs)).put("clock_domain","android_elapsed_realtime");
                JSONArray observed = new JSONArray();
                for (Geometry.Point point : points)
                  observed.put(new JSONArray(new double[] {point.x, point.y}));
                JSONArray images=new JSONArray();for(ConeDetector.Detection d:result)images.put(new JSONObject().put("frameId",Long.toString(frame.timestampNs)).put("box",new JSONArray(new float[]{d.left,d.top,d.right,d.bottom})).put("confidence",d.score).put("metric_center_provenance","inferred_from_box_rim_plus_measured_radius; empirical validation required"));
                recorder.event(event.put("points", observed).put("image_observations",images));
              }
              if (frameAgeMs < 100) supervisor.detections(points, frame.timestampNs);
              else if (supervisor.autonomous())
                supervisor.stop("Capture-to-detection latency exceeded 100 ms");
            }

          } catch (Exception e) {
            supervisor.modelReady(false);
            if (supervisor.autonomous()) supervisor.stop("Inference failed: " + e.getMessage());
          } finally {
            if (frame != null) frames.release(frame);
            inferenceScheduled.set(false);
            if(frames.hasPending()&&!closing)scheduleInference();
          }
        });
  }

  private void tick() {
    if (closing) return;
    try {
      long now = SystemClock.elapsedRealtimeNanos();
      RunSupervisor.Output out =
          active ? supervisor.tick(now) : new RunSupervisor.Output("DISARM", 0, 0);
      if (vehicle != null) vehicle.send(out);
      lastOutput=out;
      if(lastControlNs!=0)maximumControlGapNs=Math.max(maximumControlGapNs,now-lastControlNs);
      lastControlNs=now;

      JSONObject event =
          new JSONObject()
              .put("type", "control")
              .put("time_ns",Long.toString(now))
              .put("mode", supervisor.mode().name())
              .put("steering_target_deg", out.steeringDeg)
              .put("pwm", out.pwm)
              .put("fault", supervisor.fault());
      Geometry.Pose pose = supervisor.pose();
      if (pose != null)
        event.put("pose", new JSONArray(new double[] {pose.x, pose.y, pose.yaw, pose.speed}));
      if (recorder != null) {
        recorder.event(event);
        if(supervisor.finishNs()>0&&supervisor.finishNs()!=lastRecordedFinish){lastRecordedFinish=supervisor.finishNs();recorder.event(new JSONObject().put("type","finish_crossing").put("time_ns",Long.toString(lastRecordedFinish)).put("reference_id",supervisor.referenceId()).put("contact_verdict","requires_operator_review").put("time_method","timestamped-pose interpolation; <=5mm/0.01rad subdivision, plus empirical pose uncertainty"));}
      }
    } catch (Exception e) {
      supervisor.stop("Control pipeline exception");
      if (vehicle != null) vehicle.special("STOP");
    } finally {
      long now = SystemClock.elapsedRealtimeNanos();
      nextControlNs += 20_000_000L;
      if (nextControlNs < now) {
        if (moving()) supervisor.stop("Control deadline missed");
        nextControlNs = now + 20_000_000L;
      }
      if (!closing)
        control.schedule(this::tick, Math.max(0, nextControlNs - now), TimeUnit.NANOSECONDS);
    }
  }

  private void publishStatus() {
    if(closing)return;
    try {
      long now=SystemClock.elapsedRealtimeNanos();
      RunSupervisor.Output out=lastOutput;
      Geometry.Pose pose=supervisor.pose();
      JSONObject status =
          new JSONObject()
              .put("type", "telemetry")
              .put("wifi_latency_mode",wifiLatencyStatus)
              .put("control_age_ms",lastControlNs==0?JSONObject.NULL:Math.max(0,(now-lastControlNs)/1_000_000.0))
              .put("control_max_gap_ms",maximumControlGapNs/1_000_000.0)
              .put("dashboard_control",dashboard==null?JSONObject.NULL:dashboard.controlTiming())
              .put("time_ns",Long.toString(now))
              .put("mode", supervisor.mode().name())
              .put("fault", supervisor.fault())
              .put("calibrated", calibration.missing().isEmpty())
              .put("manual_ready", calibration.manualMissing().isEmpty())
              .put("manual_input_ms",ManualControl.INPUT_MS).put("manual_link_ms",ManualControl.LINK_MS)
              .put("vehicle_connected",vehicle!=null&&vehicle.connected())
              .put("mcu_fault",telemetry.optString("fault"))
              .put("manual_missing", new JSONArray(calibration.manualMissing()))
              .put("manual_armed", telemetry.optBoolean("manual_armed"))
              .put("manual_max_pwm", calibration.manual.maxPwm)
              .put(
                  "stop_behavior",
                  calibration.hardware.driveBrakeVerified
                      ? "Verified electrical brake"
                      : "Coast; braking unverified")
              .put("armed", telemetry.optBoolean("armed"))
              .put(
                  "speed_mps",
                  pose == null || now - pose.timeNs >= 100_000_000L ? JSONObject.NULL : pose.speed)
              .put("steering_deg", steeringDeg)
              .put("target_steering_deg", out.steeringDeg)
              .put("battery_monitoring", calibration.power.batteryMonitoring)
              .put(
                  "battery_v",
                  calibration.power.batteryMonitoring && voltage > 0 ? voltage : JSONObject.NULL)
              .put("battery_percent", JSONObject.NULL)
              .put("pwm", out.pwm)
              .put("mcu_pwm",telemetry.has("pwm")?telemetry.optInt("pwm"):JSONObject.NULL)
              .put("passed", supervisor.passed())
              .put("inference_ms",Double.isFinite(lastInferenceMs)?lastInferenceMs:JSONObject.NULL)
              .put("frame_age_ms",Double.isFinite(frameAgeMs)?frameAgeMs:JSONObject.NULL)
              .put("elapsed_s", supervisor.elapsedS(now))
              .put("split_s", supervisor.splitS(now))
              .put("tracking", cameraStatus)
              .put("link", connection)
              .put("model", modelStatus)
              .put("collection", collection)
              .put("recording_drops", recorder == null ? 0 : recorder.dropped())
              .put(
                  "recording_status",
                  recorder == null ? "Recording unavailable" : recorder.status())
              .put("thermal_limited", thermalLimited)
              .put("missing", new JSONArray(calibration.missing()));
      CourseMap liveMap=supervisor.map();
      status.put("map_epoch",liveMap==null?JSONObject.NULL:liveMap.mapEpoch()).put("config_id",activeConfigId).put("model_id",activeModelId).put("vehicle_model_id",dynamicsId).put("policy_id","cone_slalom_v1").put("calibration_id",calibration.firmwareCalibrationId);
      status.put("capabilities",new JSONArray(new String[]{"cone_slalom_v1","yolov5_xywh_objectness","end_to_end_xyxy_score_class","artifacts_v1","reference_v1"}));
      if(camera!=null)status.put("camera_capabilities",new JSONObject(camera.capabilities.toString()));
      if(liveMap!=null){
        JSONObject course=mapJson(liveMap);status.put("course_map",course);
        JSONObject config=new JSONObject(gson.toJson(calibration));config.remove("network");
        VehicleModel dynamics=supervisor.vehicleModel();
        if(dynamics!=null)status.put("planner_request",new JSONObject().put("schemaVersion",1).put("stopped",!moving()&&!telemetry.optBoolean("armed")).put("calibration",config).put("course",course).put("vehicleModel",new JSONObject(gson.toJson(dynamics))).put("vehicleModelId",dynamicsId).put("planningParametersId",activePolicyId).put("floorBoundsM",calibration.limits.floorBoundsM==null?JSONObject.NULL:new JSONArray(calibration.limits.floorBoundsM)).put("coneRadiusM",calibration.cones.baseDiameterM/2).put("clearanceM",calibration.limits.clearanceM).put("uncertaintyM",calibration.camera.positionErrorM==null?JSONObject.NULL:calibration.camera.positionErrorM).put("taskSpeedMps",Math.min(.2,calibration.limits.maxSpeedMps)));
      }
      status.put("capture_pwm_ms",JSONObject.NULL).put("capture_latency_status","Unavailable: exposure clock mapping not validated");
      if(vehicle!=null){
        status.put("vehicle_control",vehicle.controlStatus());
        status.put("steering_onset_ms",percentiles(vehicle.steeringTiming.onset())).put("steering_settling_ms",percentiles(vehicle.steeringTiming.settling())).put("steering_onset_samples",vehicle.steeringTiming.onsetSamples()).put("steering_settling_samples",vehicle.steeringTiming.settlingSamples()).put("steering_censored",vehicle.steeringTiming.censored()).put("steering_timing_definition","ESP potentiometer: >=7 deg target step; onset >=1 deg; settling within 3.5 deg held 100 ms; gaps >60 ms censor");
        status.put("clock_uncertainty_ms",Double.isFinite(vehicle.clockUncertaintyMs())?vehicle.clockUncertaintyMs():JSONObject.NULL);
        status.put("timing_window_samples",vehicle.timing.windowSamples()).put("image_timing_samples",vehicle.timing.imageSamples()).put("timing_window_ms",finite(vehicle.timing.windowMs())).put("timing_application_age_ms",finite(vehicle.timing.applicationAgeMs(now))).put("image_timing_age_ms",finite(vehicle.timing.imageAgeMs(now)));
        status.put("timing_samples",vehicle.timing.samples()).put("timing_missing",vehicle.timing.missing()).put("timing_pending",vehicle.timing.pending()).put("mcu_deadline_rejected",telemetry.has("deadline_rejected")?telemetry.optInt("deadline_rejected"):JSONObject.NULL).put("mcu_application_loss",telemetry.has("application_loss")?telemetry.optInt("application_loss"):JSONObject.NULL).put("failed_deadlines",vehicle.timing.failed());
        status.put("image_available_pwm_ms",percentiles(vehicle.timing.imageAvailable())).put("policy_pwm_ms",percentiles(vehicle.timing.policyApplied())).put("information_age_ms",percentiles(vehicle.timing.informationAge()));
      }
      if (calibration.steering.leftDeg != null && calibration.steering.rightDeg != null)
        status.put(
            "steering_limits_deg",
            new JSONArray(
                new double[] {calibration.steering.rightDeg, calibration.steering.leftDeg}));
      if (calibration.power.batteryMonitoring
          && calibration.power.fullV != null
          && calibration.power.stopV != null
          && calibration.power.fullV > calibration.power.stopV
          && voltage > 0)
        status.put(
            "battery_percent",
            Math.round(
                Geometry.clamp(
                    (voltage - calibration.power.stopV)
                        / (calibration.power.fullV - calibration.power.stopV)
                        * 100,
                    0,
                    100)));
      if (pose != null) status.put("pose", new JSONArray(new double[] {pose.x, pose.y, pose.yaw}));
      CourseMap map = supervisor.map();
      JSONArray cones = new JSONArray();
      if (map != null)
        for (CourseMap.Cone cone : map.cones())
          cones.put(new JSONArray(new double[] {cone.x, cone.y}));
      status.put("cones", cones);
      Trajectory path = supervisor.path();
      JSONArray trajectory = new JSONArray();
      if (path != null)
        for (int i = 0; i < path.samples.size(); i += 5) {
          Trajectory.Sample p = path.samples.get(i);
          trajectory.put(new JSONArray(new double[] {p.x, p.y, p.speed}));
        }
      status.put("path", trajectory);
      JSONArray boxes = new JSONArray();
      for (ConeDetector.Detection d : detections)
        boxes.put(new JSONArray(new float[] {d.left, d.top, d.right, d.bottom, d.score}));
      status.put("boxes", boxes);
      if (dashboard != null) dashboard.send(status);
      if (recorder != null) recorder.event(status);
      runOnUiThread(
          () -> {
            if (closing) return;
            this.status.setText(
                supervisor.mode()
                    + " · "
                    + connection
                    + "\n"
                    + cameraStatus
                    + "\n"
                    + (supervisor.autonomous()?modelStatus+"\n":"")
                    + supervisor.fault());
            parameters.setText(
                "Speed: "
                    + (pose == null
                        ? "unavailable"
                        : String.format(java.util.Locale.ROOT, "%.2f m/s (vision)", pose.speed))
                    + "\nSteering: "
                    + steeringDeg
                    + "°\nBattery: "
                    + (calibration.power.batteryMonitoring
                        ? voltage + " V"
                        : "Not monitored; check before each run")
                    + "\nInference: "
                    + Math.round(lastInferenceMs)
                    + " ms\nManual control: "
                    + (calibration.manualMissing().isEmpty()
                        ? "Ready; arm on Mac, then use WASD"
                        : String.join(", ", calibration.manualMissing()))
                    + "\nStop behavior: "
                    + (calibration.hardware.driveBrakeVerified
                        ? "Verified brake"
                        : "Coast; braking unverified")
                    + (supervisor.autonomous()?"\nAutonomy calibration: "+String.join(", ", calibration.missing()):""));
          });
    } catch(Exception e) {
      android.util.Log.w("MaxBotStatus","Status publication failed",e);
    }
  }

  private void acquireLowLatencyWifi() {
    if(!"wifi".equals(calibration.network.transport))return;
    try {
      if(wifiLatencyLock==null){
        android.net.wifi.WifiManager manager=(android.net.wifi.WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);
        wifiLatencyLock=manager.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY,"MaxBot:control");
        wifiLatencyLock.setReferenceCounted(false);
      }
      if(!wifiLatencyLock.isHeld())wifiLatencyLock.acquire();
      wifiLatencyStatus=wifiLatencyLock.isHeld()?"foreground low-latency lock held":"unavailable";
    } catch(Exception e){wifiLatencyStatus="unavailable: "+e.getClass().getSimpleName();android.util.Log.w("MaxBotWifi","Low-latency Wi-Fi request failed",e);}
  }
  private void releaseLowLatencyWifi() {
    try{if(wifiLatencyLock!=null&&wifiLatencyLock.isHeld())wifiLatencyLock.release();}catch(Exception e){android.util.Log.w("MaxBotWifi","Wi-Fi lock release failed",e);}
    wifiLatencyStatus="inactive";
  }

  private JSONObject sessionManifest(){
    JSONObject m=new JSONObject();try{m.put("schemaVersion",1).put("artifactType","recording_session").put("appVersion","0.4.0-manual").put("calibrationId",calibration.firmwareCalibrationId).put("geometryId",calibration.geometryId).put("policyId","cone_slalom_v1").put("modelId",activeModelId).put("vehicleModelId",dynamicsId).put("configRevision",activeConfigId).put("physicallyValidated",false).put("contactReview","required").put("mapEpoch",supervisor.map()==null?JSONObject.NULL:supervisor.map().mapEpoch()).put("referenceId",supervisor.referenceId()).put("cameraStreamId",camera==null?JSONObject.NULL:camera.streamId);}catch(Exception ignored){}return m;
  }

  private void exportRun()throws Exception{
    if(moving()||telemetry.optBoolean("armed"))throw new IllegalStateException("Stop/disarm before export");
    DashboardLink link=dashboard;if(link==null||recorder==null)throw new IllegalStateException("Recording/dashboard unavailable");
    File out=new File(getFilesDir(),"latest-run.zip");RunRecorder completed=recorder;
    try(java.io.OutputStream stream=new FileOutputStream(out)){completed.export(stream);}
    recorder=new RunRecorder(this);recorder.snapshot("manifest.json",sessionManifest());
    String id=java.util.UUID.randomUUID().toString().replace("-","");
    link.transfer(new JSONObject().put("type","export_transfer").put("operation","begin").put("id",id).put("size",out.length()).put("sha256",ArtifactManager.hash(out)));
    try(InputStream in=new FileInputStream(out)){byte[] bytes=new byte[24576];int n;long offset=0;while((n=in.read(bytes))!=-1){if(moving()||telemetry.optBoolean("armed"))throw new IllegalStateException("Export paused during motion");link.transfer(new JSONObject().put("type","export_transfer").put("operation","chunk").put("id",id).put("offset",offset).put("data",android.util.Base64.encodeToString(bytes,0,n,android.util.Base64.NO_WRAP)));offset+=n;}}
    link.transfer(new JSONObject().put("type","export_transfer").put("operation","end").put("id",id));
  }

  private JSONObject mapJson(CourseMap map)throws Exception{
    JSONArray cones=new JSONArray();for(CourseMap.Cone cone:map.cones())cones.put(new JSONObject().put("id",Integer.toString(cones.length()+1)).put("x",Geometry.local(map.start(),cone.point()).x).put("y",Geometry.local(map.start(),cone.point()).y).put("radiusM",calibration.cones.baseDiameterM==null?JSONObject.NULL:calibration.cones.baseDiameterM/2).put("observed",true).put("errorBoundM",cone.empiricalErrorM==null?JSONObject.NULL:cone.empiricalErrorM).put("sourceFrames",new JSONArray()).put("independentViews",cone.independentViews));
    return new JSONObject().put("schemaVersion",1).put("revision",1).put("frameId",map.frameId()).put("mapEpoch",map.mapEpoch()).put("geometryId",calibration.geometryId).put("cameraId",camera==null?"":camera.streamId).put("modelId",activeModelId).put("frozen",map.frozen()).put("cones",cones).put("qualityReport",new JSONArray());
  }
  private Runnable prepareArtifacts(File dir,JSONObject manifest)throws Exception{
    if(moving()||telemetry.optBoolean("armed"))throw new IllegalStateException("Stop/disarm before activation");
    long generation=managementEpoch.get();
    JSONObject geometry=null;String geometryHash=null,requestedGeometryId=null,referenceRaw=null,dynamicsRaw=null,geometryRaw=null;
    VehicleModel model=null;ReferenceTrajectory reference=null;SlalomPolicy.Parameters parameters=new SlalomPolicy.Parameters();
    File modelFile=null;String nextDynamicsId="",nextModelId="",nextPolicyId="";boolean nextGpu=gpu,nextVideo=videoEnabled,nextRecording=collection;int nextThreads=cpuThreads;double[] nextFloor=null;
    JSONArray entries=manifest.getJSONArray("files");
    for(int i=0;i<entries.length();i++){
      JSONObject entry=entries.getJSONObject(i);File file=new File(dir,entry.getString("name"));String kind=entry.getString("kind");
      if("model".equals(kind)){modelFile=file;nextModelId=entry.getString("sha256");continue;}
      if(file.length()>2*1024*1024)throw new IllegalArgumentException("JSON artifact too large");
      String raw=new String(java.nio.file.Files.readAllBytes(file.toPath()),java.nio.charset.StandardCharsets.UTF_8);
      String schema=kind.equals("dynamics")?"vehicle-model":kind.equals("reference")?"reference-trajectory":kind.equals("settings")?"app-settings":kind.equals("manifest")?"model-manifest":kind.equals("geometry")?"geometry":"policy";
      JsonContracts.validate(this,schema,raw);
      JSONObject json=new JSONObject(raw);
      if(json.optInt("schemaVersion")!=1)throw new IllegalArgumentException("Unsupported artifact schema");
      switch(kind){
        case "dynamics":dynamicsRaw=raw;model=gson.fromJson(raw,VehicleModel.class);if(!model.missing().isEmpty())throw new IllegalArgumentException(String.join("; ",model.missing()));nextDynamicsId=entry.getString("sha256");break;
        case "reference":referenceRaw=raw;
          reference=gson.fromJson(raw,ReferenceTrajectory.class);
          String field=",\"referenceId\":\""+reference.referenceId+"\"";int at=raw.indexOf(field);
          if(at<0)throw new IllegalArgumentException("Use canonical reference JSON from the planner/package tool");
          String without=raw.substring(0,at)+raw.substring(at+field.length());
          if(!ArtifactManager.hashBytes(without.trim().getBytes(java.nio.charset.StandardCharsets.UTF_8)).equals(reference.referenceId))throw new IllegalArgumentException("Reference content identity mismatch");
          break;
        case "policy":nextPolicyId=entry.getString("sha256");if(!"cone_slalom_v1".equals(json.optString("policyId")))throw new IllegalArgumentException("Unknown policy");parameters=gson.fromJson(json.getJSONObject("parameters").toString(),SlalomPolicy.Parameters.class);validatePolicy(parameters);break;
        case "settings":
          if(!json.optString("calibrationId").equals(calibration.firmwareCalibrationId))throw new IllegalArgumentException("Hardware changes require explicit matching firmware deployment");requestedGeometryId=json.getString("geometryId");nextGpu=json.getBoolean("gpu");nextVideo=json.getBoolean("videoEnabled");nextRecording=json.getBoolean("recordingEnabled");nextThreads=json.getInt("cpuThreads");
          if(!json.isNull("floorBoundsM")){JSONArray floor=json.getJSONArray("floorBoundsM");nextFloor=new double[]{floor.getDouble(0),floor.getDouble(1),floor.getDouble(2),floor.getDouble(3)};if(nextFloor[0]>=nextFloor[1]||nextFloor[2]>=nextFloor[3])throw new IllegalArgumentException("Invalid measured floor bounds");}
          break;
        case "geometry":if(!calibration.firmwareCalibrationId.equals(json.getString("actuatorId")))throw new IllegalArgumentException("Geometry actuator identity mismatch");geometry=json;geometryRaw=raw;geometryHash=entry.getString("sha256");break;
        case "manifest":break;
        default:throw new IllegalArgumentException("Unsupported artifact kind");
      }
    }
    if(manifest.optBoolean("_restoring")&&supervisor.map()==null)reference=null;
    Calibration candidate=gson.fromJson(gson.toJson(calibration),Calibration.class);
    if(geometry!=null){candidate.car=gson.fromJson(geometry.getJSONObject("car").toString(),Calibration.Car.class);candidate.camera=gson.fromJson(geometry.getJSONObject("camera").toString(),Calibration.Camera.class);candidate.cones=gson.fromJson(geometry.getJSONObject("cones").toString(),Calibration.Cones.class);candidate.geometryId=geometryHash;}
    if(requestedGeometryId!=null&&!requestedGeometryId.equals(candidate.geometryId))throw new IllegalArgumentException("Settings/geometry dependency mismatch");
    final boolean geometryChanged=!candidate.geometryId.equals(calibration.geometryId);
    if(reference!=null&&geometryChanged)throw new IllegalArgumentException("Geometry changed; scout a new map before including a reference");
    if(model!=null){candidate.limits.maxSpeedMps=model.validSpeedRangeMps[1];candidate.limits.accelerationMps2=model.accelerationMps2;candidate.limits.brakingMps2=model.brakingMps2;candidate.limits.lateralMps2=model.lateralMps2;}
    if(nextFloor!=null){candidate.limits.floorBoundsM=nextFloor;candidate.limits.lateralSpaceM=nextFloor[3]-nextFloor[2];candidate.limits.finishClearM=nextFloor[1]-3.41194;}
    if(reference!=null&&!reference.planningParametersId.equals(nextPolicyId))throw new IllegalArgumentException("Policy/planning parameter identity mismatch");
    if(reference!=null){List<String> errors=reference.validate(candidate,model,supervisor.map(),nextDynamicsId);if(!errors.isEmpty())throw new IllegalArgumentException(String.join("; ",errors));if(!reference.modelId.equals(nextModelId)||!nextModelId.equals(activeModelId)||camera==null||!reference.cameraId.equals(camera.streamId))throw new IllegalArgumentException("Map/model mismatch");}
    final ConeDetector nextDetector=modelFile==null?null:new ConeDetector(modelFile,nextGpu,nextThreads);
    if(nextDetector!=null)try{nextDetector.warm();}catch(RuntimeException e){nextDetector.close();throw e;}
    final VehicleModel nextModel=model;final ReferenceTrajectory nextReference=reference;final SlalomPolicy nextPolicy=new SlalomPolicy(parameters);nextPolicy.model(model);
    final boolean useGpu=nextGpu,useVideo=nextVideo,useRecording=nextRecording;final int threads=nextThreads;final double[] floor=nextFloor;
    final String dynamics=nextDynamicsId,modelHash=nextModelId,policyHash=nextPolicyId,referenceBytes=referenceRaw,dynamicsBytes=dynamicsRaw,geometryBytes=geometryRaw;final String revision=Long.toString(manifest.getLong("revision"));
    return ()->{
      if(generation!=managementEpoch.get()||moving()||telemetry.optBoolean("armed")){if(nextDetector!=null)nextDetector.close();throw new IllegalStateException("Activation interrupted");}
      List<String> errors=supervisor.activate(nextPolicy,nextModel,dynamics,nextReference,candidate,geometryChanged||!activeModelId.equals(modelHash));
      if(!errors.isEmpty()){if(nextDetector!=null)nextDetector.close();throw new IllegalStateException(String.join("; ",errors));}
      if(geometryChanged&&camera!=null)camera.recalibrate();
      ConeDetector previous=detector;detector=nextDetector;policy=nextPolicy;
      if(previous!=null)previous.close();
      gpu=useGpu;cpuThreads=threads;videoEnabled=useVideo;collection=useRecording;if(recorder!=null)recorder.collection(collection);
      if(!videoEnabled&&video!=null){video.close();video=null;}
      supervisor.modelReady(detector!=null&&detector.liveValidated());dynamicsId=dynamics;activeModelId=modelHash;activeConfigId=revision;activePolicyId=policyHash;
      if(recorder!=null)try{
        recorder.snapshot("manifest.json",sessionManifest());
        if(nextModel!=null)recorder.snapshotRaw("vehicle-model.json",dynamicsBytes);
        if(nextReference!=null)recorder.snapshotRaw("reference.json",referenceBytes);
        if(geometryBytes!=null)recorder.snapshotRaw("geometry.json",geometryBytes);
      }catch(Exception ignored){}
      modelStatus=detector==null?"Real cone weights required":(detector.liveValidated()?"Reviewed model warmed · ":"Warmed; real-cone evaluation pending · ")+modelHash.substring(0,8);
    };
  }
  private static void validatePolicy(SlalomPolicy.Parameters p){
    if(!Double.isFinite(p.l0+p.kv+p.ldMin+p.ldMax+p.kr+p.kp+p.ki+p.maxCrossTrackM)||p.ldMin<=0||p.ldMin>p.ldMax||p.ldMax>2||p.l0<=0||p.kv<0||p.kr<0||p.kr>1||p.kp<0||p.ki<0||p.maxCrossTrackM<=0||p.maxCrossTrackM>.15)throw new IllegalArgumentException("Policy parameter bounds");
  }

  private static Object finite(double value){return Double.isFinite(value)?value:JSONObject.NULL;}

  private static JSONArray percentiles(double[] values){JSONArray a=new JSONArray();for(double v:values)a.put(Double.isFinite(v)?v:JSONObject.NULL);return a;}

  @Override
  protected void onResume() {
    super.onResume();
    active = true;
    acquireLowLatencyWifi();
    if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
      requestPermissions(new String[] {Manifest.permission.CAMERA}, CAMERA_PERMISSION);
    else if (camera != null) camera.resume();
  }

  @Override
  public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
    super.onRequestPermissionsResult(request, permissions, results);
    if (request == CAMERA_PERMISSION
        && results.length > 0
        && results[0] == PackageManager.PERMISSION_GRANTED) camera.resume();
  }

  @Override
  protected void onPause() {
    active = false;
    releaseLowLatencyWifi();
    supervisor.stop("App left foreground");
    if (vehicle != null) vehicle.special("DISARM");
    if (camera != null) camera.pause();
    super.onPause();
  }

  @Override
  protected void onDestroy() {
    closing = true;
    active = false;
    control.shutdownNow();
    statusPublisher.shutdownNow();
    releaseLowLatencyWifi();
    if (vehicle != null) vehicle.close();
    if (dashboard != null) dashboard.close();
    if (video != null) video.close();
    if (camera != null) camera.close();
    if(fanout!=null)fanout.close();
    if(imu!=null)imu.close();
    if (power != null && thermalListener != null)
      power.removeThermalStatusListener(thermalListener);
    inference.execute(
        () -> {
          if (detector != null) detector.close();
        });
    inference.shutdown();
    files.execute(
        () -> {
          if (recorder != null) recorder.close();
        });
    files.shutdown();
    super.onDestroy();
  }
}
