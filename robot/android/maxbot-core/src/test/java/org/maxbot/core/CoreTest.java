package org.maxbot.core;

import java.util.List;
import org.maxbot.core.Geometry.Point;
import org.maxbot.core.Geometry.Pose;

public final class CoreTest {
  private static int checks;

  static void check(boolean result, String label) {
    checks++;
    if (!result) throw new AssertionError(label);
  }

  public static Calibration calibrated() {
    Calibration c = new Calibration();
    c.manual.enabled = true;
    c.hardware.driveBrakeVerified = true;
    c.firmwareCalibrationId = "a".repeat(64);
    c.car.wheelbaseM = .17;
    c.car.widthM = .15;
    c.car.frontOverhangM = .04;
    c.car.rearOverhangM = .04;
    c.camera.streamId="d".repeat(64);c.camera.sensorToCarRotation=new double[]{1,0,0,0,1,0,0,0,1};c.camera.gyroBiasRadS=new double[]{0,0,0};c.camera.imuLeverArmM=new double[]{0,0,0};c.camera.positionErrorM=.01;c.camera.headingErrorRad=.01;
    c.camera.heightM = .14;
    c.camera.forwardM = .10;
    c.camera.leftM = 0.;
    c.camera.pitchDownDeg = 12.;
    c.camera.yawLeftDeg = 0.;
    c.cones.heightM = .15;
    c.cones.baseDiameterM = .1;
    c.steering.leftDeg = 35.;
    c.steering.rightDeg = -35.;
    c.steering.travelTimeS = .25;
    c.steering.deadbandDeg = 1.;
    c.steering.adc = new int[] {800, 2000, 3200};
    c.steering.degrees = new double[] {-35, 0, 35};
    c.steering.kp = 3.;
    c.steering.ki = 0.;
    c.steering.kd = .1;
    c.steering.maxPwm = 100;
    c.power.dividerRatio = 4.;
    c.power.adcReferenceMv = 3300.;
    c.power.adcOffsetMv = 0.;
    c.power.fullV = 8.4;
    c.power.warningV = 7.;
    c.power.stopV = 6.8;
    c.limits.maxSpeedMps = .5;
    c.limits.accelerationMps2 = .3;
    c.limits.brakingMps2 = .5;
    c.limits.lateralMps2 = .3;
    c.limits.pwmPerMps = 150.;
    c.limits.tractionMinPwm = 20;
    c.limits.launchClearM = 1.;
    c.limits.finishClearM = 1.;
    c.limits.lateralSpaceM = 4.;
    c.network.pairingKey = "0123456789abcdef0123456789abcdef";
    c.commissioning.steeringMeasured = true;
    c.commissioning.brakingTested = true;
    c.commissioning.powerVerified = true;
    c.commissioning.floorVerified = true;
    return c;
  }

  public static void main(String[] args) {
    Calibration c = calibrated();
    check(c.missing().isEmpty(), "complete calibration");
    c.power.senseSource = "regulated5V";
    check(
        c.missing().stream().anyMatch(s -> s.contains("before the buck converter")),
        "regulated rail is not pack voltage");
    c.power.senseSource = "battery";
    check(c.missing().isEmpty(), "direct calibrated pack sensing accepted");
    check(!new Calibration().missing().isEmpty(), "null placeholders block arming");
    c.steering.adc = new int[] {100, 100, 300};
    check(!c.validSteeringTable(), "flat ADC rejected");
    c = calibrated();
    c.steering.degrees[1] = Double.NaN;
    check(!c.validSteeringTable(), "NaN rejected");
    c = calibrated();
    check(Math.abs(c.stoppingDistance(1, .1) - 1.25) < 1e-9, "latency plus braking distance");
    String msg = Protocol.canonical("boot", "session", 1, 1200, 100, "DRIVE", 1500, 30);
    String sig = Protocol.sign(c.network.pairingKey, msg);
    check(Protocol.verify(c.network.pairingKey, msg, sig), "HMAC roundtrip");
    check(!Protocol.verify(c.network.pairingKey, msg + "x", sig), "tampering rejected");
    System.out.println("HMAC_VECTOR=" + sig);
    LatestFramePool pool = new LatestFramePool(100);
    LatestFramePool.Slot a = pool.acquireForCapture();
    a.yuv[0] = 7;
    pool.publish(a);
    a = pool.takeLatest();
    LatestFramePool.Slot b = pool.acquireForCapture();
    pool.publish(b);
    LatestFramePool.Slot d = pool.acquireForCapture();
    check(a != d && a.yuv[0] == 7, "in-flight image not overwritten");
    pool.publish(d);
    check(pool.takeLatest() == d, "pending work replaced by latest frame");
    pool.release(a);
    pool.release(d);
    Pose start = new Pose(0, 0, 0, 0, 1_000_000_000L);
    CourseMap map = new CourseMap(start);
    List<Point> cones = List.of(new Point(2, 0), new Point(4, 0), new Point(6, 0), new Point(8, 0));
    for (int i = 1; i <= 25; i++) map.observe(cones, i);
    check(map.complete(), "four cone map confirmed");
    map.approveGeometry(.01,2);
    map.freeze();
    check(!map.observationsAgree(List.of(new Point(2.5, 0))), "moved cone rejected");
    Trajectory path = Trajectory.plan(c, start, map.cones(), false);
    check(path.samples.size() > 100, "trajectory generated");
    check(path.samples.get(path.samples.size() - 1).speed == 0, "finish stops");
    for (Trajectory.Sample p : path.samples) {
      check(p.speed <= c.limits.scoutSpeedMps + 1e-8, "baseline speed capped");
      for (Point cone : cones)
        check(
            Geometry.footprintDistance(new Pose(p.x, p.y, p.yaw, 0, 0), cone, c)
                >= c.cones.baseDiameterM / 2 + c.limits.clearanceM,
            "swept footprint clear");
    }
    RunSupervisor.Controller fake =
        new RunSupervisor.Controller() {
          public boolean available() {
            return true;
          }

          public RunSupervisor.Output follow(Pose p, double s, Trajectory t, int n, Calibration k) {
            return new RunSupervisor.Output("DRIVE", 0, 15);
          }
        };
    long now = 1_000_000_000L;
    Calibration manualPower = calibrated();
    manualPower.power.batteryMonitoring = false;
    manualPower.power.senseSource = "disabled";
    manualPower.power.dividerRatio =
        manualPower.power.adcReferenceMv = manualPower.power.adcOffsetMv = null;
    manualPower.power.fullV = manualPower.power.warningV = manualPower.power.stopV = null;
    check(
        manualPower.missing().isEmpty(),
        "disabled monitoring needs no ADC model or voltage thresholds");
    RunSupervisor noBattery = new RunSupervisor(manualPower, fake);
    noBattery.link(true);
    noBattery.localization(start, true);
    noBattery.heartbeat(now);
    noBattery.telemetry(0, Double.NaN, now, true, "");
    check(
        noBattery.manual(2, 20, true, now),
        "manual driving accepts unavailable battery without a cone model");
    check(noBattery.tick(now).pwm == 20, "manual PWM is not blocked by absent voltage");
    noBattery.stop("End manual test before scouting");
    noBattery.modelReady(true);
    noBattery.telemetry(0, 0, now, true, "");
    check(
        !noBattery.startScout(now),
        "autonomous scout requires measured dynamics even without battery monitoring");
    check(
        noBattery.tick(now + 200_000_000L).pwm == 0
            && noBattery.mode() == RunSupervisor.Mode.STOPPED,
        "freshness stop retained without battery sensing");
    noBattery.estop();
    check(!noBattery.manual(0, 20, true, now), "emergency stop retained without battery sensing");
    RunSupervisor monitored = new RunSupervisor(calibrated(), fake);
    monitored.link(true);
    monitored.localization(start, true);
    monitored.heartbeat(now);
    monitored.modelReady(true);
    monitored.telemetry(0, 5, now, true, "");
    check(!monitored.startScout(now), "enabled low-battery check still prevents driving");
    Calibration commissioning = calibrated();
    commissioning.car = new Calibration.Car();
    commissioning.camera = new Calibration.Camera();
    commissioning.cones = new Calibration.Cones();
    commissioning.limits = new Calibration.Limits();
    commissioning.commissioning = new Calibration.Commissioning();
    commissioning.hardware.driveBrakeVerified = false;
    commissioning.power.batteryMonitoring = false;
    check(
        commissioning.manualMissing().isEmpty(),
        "manual commissioning does not invent geometry, speed model or confirmations");
    check(!commissioning.missing().isEmpty(), "incomplete autonomous calibration remains locked");
    RunSupervisor manualOnly = new RunSupervisor(commissioning, fake);
    manualOnly.link(true);
    manualOnly.localization(null, false);
    manualOnly.heartbeat(now);
    manualOnly.telemetry(0, 0, now, true, "");
    check(
        manualOnly.manual(8, 255, true, now),
        "manual accepts full PWM without ARCore or cone model");
    check(
        manualOnly.tick(now).pwm == 255 && manualOnly.tick(now).mode.equals("DRIVE_MANUAL"),
        "manual command has distinct firmware authority and no 100 PWM cap");
    manualOnly.localization(null, false);
    check(
        manualOnly.tick(now).pwm == 255,
        "camera tracking loss does not disable direct manual control");
    check(
        manualOnly.manual(0, -255, true, now + 20_000_000L)
            && manualOnly.tick(now + 20_000_000L).pwm == 0,
        "reversing first requests neutral");
    long reversed = now + 400_000_000L;
    manualOnly.heartbeat(reversed);
    manualOnly.telemetry(0, 0, reversed, true, "");
    check(
        manualOnly.manual(0, -255, true, reversed) && manualOnly.tick(reversed).pwm == -255,
        "reverse allowed after neutral dwell");
    check(
        manualOnly.tick(reversed + 101_000_000L).pwm == 0,
        "manual command expires without held deadman");
    check(!manualOnly.startScout(reversed), "manual-ready configuration never authorizes autonomy");
    manualPower.power.batteryMonitoring = true;
    check(
        !manualPower.missing().isEmpty(),
        "reenabling monitoring restores voltage-calibration requirements");
    RunSupervisor run = new RunSupervisor(c, fake);
    run.modelReady(true);
    run.link(true);
    run.localization(start, true);
    run.heartbeat(now);
    run.telemetry(0, 8, now, true, "");
    check(!run.startScout(now), "fresh inputs do not replace reviewed dynamics");
    check(run.tick(now).pwm == 0, "scout never searches blindly");
    check(
        run.tick(now + 200_000_000L).pwm == 0 && !run.autonomous(),
        "stale input stops");
    run.estop();
    check(!run.startScout(now), "estop latches");
    run.reset();
    check(run.mode() == RunSupervisor.Mode.DISARMED, "reset disarms");
    RunSupervisor blocked = new RunSupervisor(new Calibration(), fake);
    check(!blocked.startScout(now), "missing calibration cannot arm");
    check(
        Geometry.groundRay(
                0,
                0,
                1,
                1,
                0,
                0,
                new double[] {0, 1, 0},
                new double[] {1, 0, 0},
                new double[] {0, 0, 1},
                new double[] {0, -1, 0},
                0)
            != null,
        "ground projection");
    LatencyStats stats = new LatencyStats();
    for (int i = 1; i <= 100; i++) stats.add(i);
    double[] percentiles = stats.snapshot();
    check(
        percentiles[0] == 50 && percentiles[1] == 95 && percentiles[2] == 99,
        "bounded latency percentiles");
    AutonomyTest.run();
    System.out.println("PASS " + checks + " core checks");
  }
}
