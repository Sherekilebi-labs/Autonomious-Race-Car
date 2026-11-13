package org.maxbot.app;

import org.maxbot.core.Calibration;
import org.maxbot.core.Geometry;
import org.maxbot.core.RunSupervisor;
import org.maxbot.core.Trajectory;

public final class NativeMpc implements RunSupervisor.Controller {
  private static final boolean LOADED;

  static {
    boolean ready;
    try {
      System.loadLibrary("maxbot_mpc");
      ready = true;
    } catch (UnsatisfiedLinkError e) {
      ready = false;
    }
    LOADED = ready;
  }

  private final double[] speeds = new double[20], curves = new double[20];
  private double speedIntegral;
  private volatile double batteryVoltage;
  private long previousNs;

  void battery(double voltage) {
    batteryVoltage = voltage;
  }

  public boolean available() {
    return LOADED;
  }

  private static synchronized native double[] solve(
      double ey,
      double epsi,
      double speed,
      double steering,
      double wheelbase,
      double rate,
      double accel,
      double brake,
      double left,
      double right,
      double[] speeds,
      double[] curves);

  @Override
  public synchronized RunSupervisor.Output follow(
      Geometry.Pose pose, double steeringDeg, Trajectory path, int index, Calibration c) {
    if (!LOADED) return null;
    Trajectory.Sample current = path.samples.get(index);
    double dx = pose.x - current.x, dy = pose.y - current.y;
    double ey = -Math.sin(current.yaw) * dx + Math.cos(current.yaw) * dy;
    if (Math.abs(ey) > .15 || Math.abs(Geometry.angle(pose.yaw - current.yaw)) > .6) return null;
    double distance = current.s;
    int j = index;
    for (int k = 0; k < 20; k++) {
      distance += Math.max(.05, Math.max(pose.speed, path.samples.get(j).speed)) * .05;
      while (j < path.samples.size() - 1 && path.samples.get(j).s < distance) j++;
      speeds[k] = path.samples.get(j).speed;
      curves[k] = path.samples.get(j).curvature;
    }
    double[] out =
        solve(
            ey,
            Geometry.angle(pose.yaw - current.yaw),
            pose.speed,
            Math.toRadians(steeringDeg),
            c.car.wheelbaseM,
            Math.toRadians(c.steering.leftDeg - c.steering.rightDeg) / c.steering.travelTimeS,
            c.limits.accelerationMps2,
            c.limits.brakingMps2,
            Math.toRadians(c.steering.leftDeg),
            Math.toRadians(c.steering.rightDeg),
            speeds,
            curves);
    if (out == null || out[2] != 1) return null;
    double target = Math.max(0, pose.speed + out[1] * .02);
    double dt = previousNs == 0 ? 0 : (pose.timeNs - previousNs) * 1e-9;
    previousNs = pose.timeNs;
    if (dt < 0 || dt > .2) speedIntegral = 0;
    else speedIntegral = Geometry.clamp(speedIntegral + (speeds[0] - pose.speed) * dt, -.1, .1);
    double pwm =
        target > 0.001
            ? c.limits.tractionMinPwm
                + c.limits.pwmPerMps * (target + .5 * (speeds[0] - pose.speed) + speedIntegral)
            : 0;
    if (c.power.batteryMonitoring && c.power.fullV != null && Double.isFinite(batteryVoltage) && batteryVoltage > 0)
      pwm *= Geometry.clamp(c.power.fullV / batteryVoltage, 1, 1.3);
    // Zero means verified brake; never reverse the traction motor to simulate braking.
    if (out[1] < 0 && pose.speed > speeds[0] + .03) pwm = 0;
    return new RunSupervisor.Output(
        "DRIVE", Math.toDegrees(out[0]), (int) Geometry.clamp(pwm, 0, 255));
  }
}
