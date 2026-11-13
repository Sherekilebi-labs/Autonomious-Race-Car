package org.maxbot.core;

import java.util.List;
import org.maxbot.core.Geometry.Point;
import org.maxbot.core.Geometry.Pose;

/** Sole motion authority. Callbacks and control tick serialize on this object. */
public final class RunSupervisor {
  public enum Mode {
    DISARMED,
    SCOUT,
    REPOSITION,
    READY,
    RUN,
    MANUAL,
    FINISHED,
    STOPPED,
    ESTOP
  }

  public static final class Output {
    public final String mode;
    public final double steeringDeg;
    public final int pwm;
    public final long validUntilNs,sourceStateNs,sourceFrameNs,producedNs;

    public Output(String mode, double steeringDeg, int pwm) {
      this(mode,steeringDeg,pwm,Long.MAX_VALUE,0,0);
    }

    public Output(String mode,double steeringDeg,int pwm,long validUntilNs,long sourceStateNs,long sourceFrameNs) {this(mode,steeringDeg,pwm,validUntilNs,sourceStateNs,sourceFrameNs,0);}
    public Output(String mode,double steeringDeg,int pwm,long validUntilNs,long sourceStateNs,long sourceFrameNs,long producedNs) {
      this.producedNs=producedNs;
      this.validUntilNs=validUntilNs;this.sourceStateNs=sourceStateNs;this.sourceFrameNs=sourceFrameNs;
      this.mode = mode;
      this.steeringDeg = steeringDeg;
      this.pwm = pwm;
    }
  }

  public interface Controller {
    Output follow(
        Pose pose, double steeringDeg, Trajectory path, int nearest, Calibration calibration);

    boolean available();
    default void reset(){}
    default Output followState(StateEstimator.State state,double steering,Trajectory path,int nearest,Calibration c,long now){return followAt(state.pose,steering,path,nearest,c,now);}
    default Output followAt(Pose pose,double steering,Trajectory path,int nearest,Calibration c,long now){return follow(pose,steering,path,nearest,c);}
  }

  private long authorityRevision,manualDeadline;private boolean resetPolicy;
  private final StateEstimator estimator=new StateEstimator();
  private long imuNs;
  public synchronized void imu(long timestamp,double yawRate,boolean aligned){estimator.gyro(timestamp,yawRate,aligned);if(aligned)imuNs=timestamp;}
  private final PassageScorer scorer=new PassageScorer();
  private ReferenceTrajectory reference;
  private VehicleModel vehicleModel;
  private String vehicleModelId="";
  public synchronized void vehicleModel(VehicleModel m,String id){stop("Dynamics changed; reference invalidated");vehicleModel=m;vehicleModelId=id;reference=null;path=null;}
  public synchronized VehicleModel vehicleModel(){return vehicleModel;}
  public synchronized List<String> stageReference(ReferenceTrajectory r){
    if(moving())return List.of("Stop/disarm before staging");
    List<String> errors=r.validate(c,vehicleModel,map,vehicleModelId);
    if(errors.isEmpty()){reference=r;path=r.trajectory(map);verified=false;mode=Mode.REPOSITION;}
    return errors;
  }
  public synchronized void freezeMap(double error,int independentViews){
    if(moving()||deviceArmed||map==null)throw new IllegalStateException("Stop before map review");
    map.approveGeometry(error,independentViews);map.freeze();mode=Mode.REPOSITION;
  }
  private static final class Work {
    final Geometry.Pose pose;final StateEstimator.State state;final Trajectory path;final int nearest;final double steering;final long revision;final Controller controller;final boolean reset;
    Work(Geometry.Pose p,StateEstimator.State state,Trajectory t,int n,double s,long r,Controller controller,boolean reset){this.controller=controller;this.reset=reset;pose=p;this.state=state;path=t;nearest=n;steering=s;revision=r;}
  }
  private Work work;
  private final Calibration c;
  private Controller controller;
  public synchronized List<String> activate(Controller next,VehicleModel model,String modelId,ReferenceTrajectory ref,Calibration candidate,boolean invalidate){
    if(moving()||deviceArmed)return List.of("Stop/disarm before activation");
    if(ref!=null){List<String> errors=ref.validate(candidate,model,map,modelId);if(!errors.isEmpty())return errors;}
    authorityRevision++;verified=false;c.limits=candidate.limits;c.car=candidate.car;c.camera=candidate.camera;c.cones=candidate.cones;c.geometryId=candidate.geometryId;controller=next;vehicleModel=model;vehicleModelId=modelId;
    if(invalidate)map=null;
    reference=ref;path=ref==null?null:ref.trajectory(map);mode=mode==Mode.ESTOP?Mode.ESTOP:ref==null?Mode.STOPPED:Mode.REPOSITION;fault="Artifacts applied; disarmed";
    return List.of();
  }
  public synchronized void invalidateMap(String reason){stop(reason);map=null;path=null;reference=null;}
  public synchronized void replaceController(Controller next){stop("Policy changed; disarmed verification required");controller=next;}
  private Mode mode = Mode.DISARMED;
  private Pose pose;
  private CourseMap map;
  private Trajectory path;
  private long supervisorNs,
      telemetryNs,
      manualNs,
      lastVisionNs,
      startedNs,
      firstPassNs,
      finishedNs;
  private long lastManualPoweredNs;
  private int lastManualDirection;
  private double steering, battery, manualSteering;
  private int manualPwm, nearest, passed;
  private boolean tracking, modelReady, linkReady, verified, deviceArmed;
  private String fault = "Disarmed; arm explicitly";
  private int verificationFrames;

  public RunSupervisor(Calibration c, Controller controller) {
    this.c = c;
    this.controller = controller;
  }

  public synchronized Mode mode() {
    return mode;
  }

  public synchronized String fault() {
    return fault;
  }

  public synchronized int passed() {
    return passed;
  }

  public synchronized boolean autonomous() {
    return mode == Mode.SCOUT || mode == Mode.RUN;
  }

  public synchronized CourseMap map() {
    return map;
  }

  public synchronized Trajectory path() {
    return path;
  }

  public synchronized Pose pose() {
    return pose;
  }

  public synchronized void heartbeat(long now) {
    supervisorNs = now;
  }

  public synchronized void modelReady(boolean ready) {
    modelReady = ready;
  }

  public synchronized void link(boolean ready) {
    linkReady = ready;
    if (!ready) stop("Vehicle link unavailable");
  }

  public synchronized void telemetry(
      double angle, double volts, long now, boolean deviceArmed, String deviceFault) {
    steering = angle;
    battery = c.power.batteryMonitoring ? volts : Double.NaN;
    telemetryNs = now;
    this.deviceArmed = deviceArmed;
    if (!Double.isFinite(angle) || (c.power.batteryMonitoring && !Double.isFinite(volts)))
      stop("Invalid vehicle telemetry");
    if (deviceFault != null && !deviceFault.isEmpty()) stop("ESP32: " + deviceFault);
    if (c.power.batteryMonitoring && c.power.stopV != null && volts < c.power.stopV)
      stop("Low battery");
    if (moving() && !deviceArmed) stop("ESP32 is not armed");
  }

  public synchronized void localization(Pose next, boolean valid) {
    if (!valid || next == null || !next.finite()) {
      tracking = false;
      if (autonomous()) stop("Localization lost; re-localize before rearming");
      return;
    }
    if (pose != null) {
      double dt = (next.timeNs - pose.timeNs) * 1e-9;
      if (dt <= 0) return;
      if (next.position().distance(pose.position()) > Math.max(.15, dt * 15)
          || (dt < .2 && Math.abs(Geometry.angle(next.yaw - pose.yaw)) > 1)) {
        tracking = false;
        verified = false;
        map = null;
        path = null;
        if (autonomous()) stop("Pose discontinuity; scout again");
        pose = next;
        return;
      }
    }
    pose = next;
    estimator.vio(next);
    tracking = true;
  }

  public synchronized void detections(List<Point> cones, long now) {
    if (!tracking || pose == null) return;
    if(!cones.isEmpty())lastVisionNs = now;
    if (mode == Mode.SCOUT) {
      map.observe(cones, now);
      if (map.cones().size() > 4) {
        stop("More than four cone landmarks; scout again");
        return;
      }
      if (path == null && !map.cones().isEmpty()) {
        try {
          path = ScoutPlanner.segment(c,map.start(),map.cones(),vehicleModel);
          nearest = 0;
        } catch (IllegalStateException e) {
          stop(e.getMessage());
        }
      }
    } else if (mode == Mode.REPOSITION || mode == Mode.READY) {
      boolean candidate =
          map != null
              && map.complete()
              && map.observationsAgree(cones)
              && pose.position().distance(map.start().position()) < .08
              && Math.abs(Geometry.angle(pose.yaw - map.start().yaw)) < Math.toRadians(8)
              && Math.abs(pose.speed) < .04;
      verificationFrames = candidate ? verificationFrames + 1 : 0;
      verified = verificationFrames >= 5;
      mode = verified ? Mode.READY : Mode.REPOSITION;
    } else if (mode == Mode.RUN && !cones.isEmpty() && !map.observationsAgree(cones))
      stop("Course moved or localization disagrees");
  }

  public synchronized boolean startScout(long now) {
    if (moving()) {
      fault = "Stop before starting another scout";
      return false;
    }
    if (!preflight(now)) return false;
    map = new CourseMap(pose);
    path = null;
    try{path=ScoutPlanner.segment(c,pose,List.of(),vehicleModel);}catch(IllegalStateException e){fault=e.getMessage();return false;}
    nearest = passed = 0;
    resetPolicy=true;
    mode = Mode.SCOUT;
    fault = "";
    return true;
  }

  public synchronized boolean startRun(long now) {
    if (mode != Mode.READY) {
      fault = "Reposition and verify localization before starting";
      return false;
    }
    if (!preflight(now) || !verified || map == null || !map.complete()) {
      fault = "Verify original start pose and four-cone map";
      return false;
    }
    if(reference==null||!reference.validate(c,vehicleModel,map,vehicleModelId).isEmpty()){
      fault="Stage a matching Mac reference with measured dynamics and braking tail";return false;
    }
    path=reference.trajectory(map);
    authorityRevision++;
    nearest = passed = 0;
    resetPolicy=true;
    scorer.reset();
    startedNs = now;
    firstPassNs = finishedNs = 0;
    mode = Mode.RUN;
    fault = "";
    return true;
  }

  public synchronized boolean manual(double angle,int pwm,boolean deadman,long now){return manual(angle,pwm,deadman,now,now+100_000_000L);}
  public synchronized boolean manual(double angle, int pwm, boolean deadman, long now,long deadline) {
    if(deadline<=now||deadline>now+100_000_000L){stop("Original manual intent expired/invalid");return false;}
    if(mode!=Mode.MANUAL)authorityRevision++;
    manualDeadline=deadline;
    if (!deadman) {
      stop("Manual deadman released");
      return false;
    }
    if (!preflight(now, false) || !Double.isFinite(angle) || pwm < -255 || pwm > 255) return false;
    mode = Mode.MANUAL;
    verified = false;
    manualNs = now;
    manualSteering = Geometry.clamp(angle, c.steering.rightDeg, c.steering.leftDeg);
    int cap = c.manual.maxPwm;
    manualPwm = Math.max(-cap, Math.min(cap, pwm));
    fault = "";
    int direction = Integer.signum(manualPwm);
    if (direction != 0
        && lastManualDirection != 0
        && direction != lastManualDirection
        && now - lastManualPoweredNs < c.manual.reversePauseMs * 1_000_000L) {
      manualPwm = 0;
      fault = "Neutral pause before reversing";
    }
    if (tracking
        && pose != null
        && now - pose.timeNs < 100_000_000L
        && ((pose.speed > .05 && manualPwm < 0) || (pose.speed < -.05 && manualPwm > 0))) {
      manualPwm = 0;
      fault = "Brake to a stop before reversing";
    }
    if (manualPwm != 0) {
      lastManualDirection = Integer.signum(manualPwm);
      lastManualPoweredNs = now;
    }
    return true;
  }

  private boolean preflight(long now) {
    return preflight(now, true);
  }

  private boolean preflight(long now, boolean autonomous) {
    if (mode == Mode.ESTOP) {
      fault = "Reset latched emergency stop first";
      return false;
    }
    List<String> missing = autonomous ? c.missing() : c.manualMissing();
    if (!missing.isEmpty()) {
      fault = String.join("; ", missing);
      return false;
    }
    if (autonomous && (!modelReady || !controller.available())) {
      fault = "Validated cone model and installed policy required";
      return false;
    }
    if (!deviceArmed) {
      fault = "Arm the ESP32 explicitly before starting";
      return false;
    }
    if (!healthy(now, autonomous)) {
      fault =
          autonomous
              ? "Fresh tracking, supervisor and vehicle telemetry required"
              : "Fresh operator heartbeat and ESP32 telemetry required";
      return false;
    }
    return true;
  }

  private boolean healthy(long now, boolean autonomous) {
    boolean batteryOk =
        !c.power.batteryMonitoring
            || (Double.isFinite(battery)
                && battery > 0
                && c.power.stopV != null
                && battery >= c.power.stopV);
    return (!autonomous || (tracking && pose != null && now>=pose.timeNs && now - pose.timeNs < 100_000_000L && now>=imuNs && now-imuNs<50_000_000L))
        && linkReady
        && now - supervisorNs < c.limits.supervisorMs * 1_000_000L
        && now - telemetryNs < 150_000_000L
        && batteryOk;
  }

  private boolean moving() {
    return mode == Mode.SCOUT || mode == Mode.RUN || mode == Mode.MANUAL;
  }

  private synchronized Output prepareTick(long now) {
    if (mode == Mode.ESTOP) return new Output("ESTOP", 0, 0);
    if (!moving()) return new Output("STOP", 0, 0);
    if (!healthy(now, mode != Mode.MANUAL)) {
      stop("Stale tracking, supervisor or vehicle telemetry");
      return new Output("STOP", 0, 0);
    }
    if (mode == Mode.MANUAL) {
      if (now >= manualDeadline) {
        stop("Manual command expired");
        return new Output("STOP", 0, 0);
      }
      return new Output("DRIVE_MANUAL", manualSteering, manualPwm,manualDeadline,manualNs,0);
    }
    double speedLimit =
        mode == Mode.RUN && c.commissioning.cleanBaselineRuns >= 20
            ? c.limits.maxSpeedMps
            : Math.min(c.limits.scoutSpeedMps, c.limits.maxSpeedMps);
    if (Math.abs(pose.speed) > speedLimit * 1.3 + .05) {
      stop("Measured visual speed exceeded the commissioned limit");
      return new Output("STOP", 0, 0);
    }
    if (mode==Mode.SCOUT && path!=null && nearest>30 && now - lastVisionNs > 200_000_000L) {
      stop("Cone perception stale");
      return new Output("STOP", 0, 0);
    }
    if (path == null)
      return new Output("DRIVE", 0, 0); // Waiting for a confirmed first cone; no blind search.
    nearest = path.nearest(pose, nearest);
    List<CourseMap.Cone> cones = map.cones();
    if(mode==Mode.RUN){
      if(!scorer.update(pose,map,c,reference.uncertaintyM)){stop(scorer.fault());return new Output("STOP",0,0);}
      passed=scorer.passed();if(scorer.finishNs()>0)finishedNs=scorer.finishNs();
    }
    if (mode == Mode.SCOUT
        && cones.size() > passed
        && path.samples.get(path.samples.size() - 1).s - path.samples.get(nearest).s < .15) {
      try {
        path = ScoutPlanner.segment(c,map.start(),cones,vehicleModel);
      } catch (IllegalStateException e) {
        stop(e.getMessage());
      }
    }
    if (nearest >= path.samples.size() - 3 && Math.abs(pose.speed) < .05) {
      if (mode == Mode.RUN && passed == 4) {
        mode = Mode.FINISHED;
        if(finishedNs==0)finishedNs = now;
        fault = "Run complete; review collision labels";
      } else stop("End of confirmed path reached; next cone not mapped");
      return new Output("STOP", 0, 0);
    }
    StateEstimator.State state=estimator.predict(now,now+(long)(vehicleModel.applicationDelayS*1e9),c.camera.positionErrorM,c.camera.headingErrorRad);
    if(state==null||state.positionErrorM==null||state.headingErrorRad==null){stop("State prediction/empirical quality unavailable");return new Output("STOP",0,0);}
    work=new Work(pose,state,path,nearest,steering,authorityRevision,controller,resetPolicy);resetPolicy=false;
    return null;
  }

  public Output tick(long now){
    long executionStart=System.nanoTime();
    Work current;
    synchronized(this){Output immediate=prepareTick(now);if(immediate!=null)return immediate;current=work;}
    if(current.reset)current.controller.reset();
    Output output=current.controller.followState(current.state,current.steering,current.path,current.nearest,c,now);
    // Policy execution never holds the supervisor monitor. Stop/manual always advance authority.
    synchronized(this){
      if(current.revision!=authorityRevision||!autonomous()||!healthy(now,true))return new Output("STOP",0,0);
    if (output == null
        || !Double.isFinite(output.steeringDeg)
        || output.pwm < -255
        || output.pwm > 255) {
      stop("Policy invalid or deadline exceeded");
      return new Output("STOP", 0, 0);
    }
    // Validate short swept footprint at current speed before admitting the command.
    double delta = Math.toRadians(output.steeringDeg), x = pose.x, y = pose.y, yaw = pose.yaw;
    for (int k = 0; k < 10; k++) {
      x += pose.speed * Math.cos(yaw) * .02;
      y += pose.speed * Math.sin(yaw) * .02;
      yaw += pose.speed * Math.tan(delta) / c.car.wheelbaseM * .02;
      Pose projected = new Pose(x, y, yaw, pose.speed, now);
      for (CourseMap.Cone cone : map.cones())
        if (Geometry.footprintDistance(projected, cone.point(), c)
            < c.cones.baseDiameterM / 2 + c.limits.clearanceM) {
          stop("Predicted clearance violation");
          return new Output("STOP", 0, 0);
        }
    }
    return new Output(output.mode,output.steeringDeg,output.pwm,Math.min(now+100_000_000L,current.pose.timeNs+100_000_000L),current.pose.timeNs,lastVisionNs,now+Math.max(0,System.nanoTime()-executionStart));
    }
  }

  public synchronized void stop(String reason) {
    authorityRevision++;
    if (mode != Mode.ESTOP) mode = Mode.STOPPED;
    fault = reason;
    verified = false;
  }

  public synchronized void estop() {
    authorityRevision++;
    mode = Mode.ESTOP;
    fault = "Emergency stop latched";
    verified = false;
  }

  public synchronized void reset() {
    authorityRevision++;
    mode = Mode.DISARMED;
    fault = "Disarmed; verify calibration and links";
    verified = false;
  }

  public synchronized long finishNs(){return finishedNs;}
  public synchronized String referenceId(){return reference==null?"":reference.referenceId;}

  public synchronized double elapsedS(long now) {
    return startedNs == 0 ? 0 : ((finishedNs == 0 ? now : finishedNs) - startedNs) * 1e-9;
  }

  public synchronized double splitS(long now) {
    return firstPassNs == 0 ? 0 : ((finishedNs == 0 ? now : finishedNs) - firstPassNs) * 1e-9;
  }
}
