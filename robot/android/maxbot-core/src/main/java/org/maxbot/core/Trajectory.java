package org.maxbot.core;

import java.util.ArrayList;
import java.util.List;
import org.maxbot.core.Geometry.Point;
import org.maxbot.core.Geometry.Pose;

/** Curvature-continuous quintic slalom with acceleration/braking and steering-rate envelope. */
public final class Trajectory {
  public static final class Sample {
    public final double x, y, yaw, curvature, s;
    public double speed;

    public Sample(double x, double y, double yaw, double curvature, double s) {
      this.x = x;
      this.y = y;
      this.yaw = yaw;
      this.curvature = curvature;
      this.s = s;
    }
  }

  public final List<Sample> samples;
  public final double estimatedTime;

  public Trajectory(List<Sample> samples, double time) {
    this.samples = samples;
    estimatedTime = time;
  }

  public Sample at(double distance) {
    if(distance<=samples.get(0).s)return samples.get(0);
    for(int i=1;i<samples.size();i++)if(samples.get(i).s>=distance){
      Sample a=samples.get(i-1),b=samples.get(i);double t=(distance-a.s)/(b.s-a.s);
      Sample out=new Sample(a.x+t*(b.x-a.x),a.y+t*(b.y-a.y),a.yaw+t*Geometry.angle(b.yaw-a.yaw),a.curvature+t*(b.curvature-a.curvature),distance);
      out.speed=a.speed+t*(b.speed-a.speed);return out;
    }
    return samples.get(samples.size()-1);
  }

  public static Trajectory plan(
      Calibration c, Pose start, List<CourseMap.Cone> cones, boolean scout) {
    if (cones.isEmpty()) throw new IllegalArgumentException("No confirmed cone");
    Trajectory best = null;
    for (double factor : new double[] {1, 1.1, 1.25, 1.5}) {
      Trajectory t = candidate(c, start, cones, scout, factor);
      if (t != null && (best == null || t.estimatedTime < best.estimatedTime)) best = t;
    }
    if (best == null)
      throw new IllegalStateException("No path fits measured steering, clearance and floor space");
    return best;
  }

  private static Trajectory candidate(
      Calibration c, Pose start, List<CourseMap.Cone> cones, boolean scout, double factor) {
    double heading = start.yaw;
    if (cones.size() >= 2)
      heading =
          Math.atan2(
              cones.get(cones.size() - 1).y - cones.get(0).y,
              cones.get(cones.size() - 1).x - cones.get(0).x);
    Pose axis = new Pose(start.x, start.y, heading, 0, 0);
    List<Point> waypoints = new ArrayList<>();
    waypoints.add(new Point(0, 0));
    // Conservative radius also covers the overhangs while yawed at a cone station.
    double radius = Math.hypot(c.car.wheelbaseM + c.car.frontOverhangM, c.car.widthM / 2);
    double offset = (radius + c.cones.baseDiameterM / 2 + c.limits.clearanceM) * factor;
    for (int i = 0; i < cones.size(); i++) {
      Point p = Geometry.local(axis, cones.get(i).point());
      if (p.x <= waypoints.get(waypoints.size() - 1).x + .05) return null;
      waypoints.add(new Point(p.x, p.y + (i % 2 == 0 ? offset : -offset)));
    }
    Point last = waypoints.get(waypoints.size() - 1);
    double finish =
        last.x
            + c.limits.finishClearM
            - c.car.wheelbaseM
            - c.car.frontOverhangM
            - c.limits.clearanceM;
    if (finish <= last.x + c.car.rearOverhangM + c.cones.baseDiameterM / 2 + c.limits.clearanceM)
      return null;
    waypoints.add(new Point(finish, last.y));
    List<Sample> samples = new ArrayList<>();
    double s = 0;
    double vmax =
        scout || c.commissioning.cleanBaselineRuns < 20
            ? Math.min(c.limits.scoutSpeedMps, c.limits.maxSpeedMps)
            : c.limits.maxSpeedMps;
    double maxRate =
        Math.toRadians(c.steering.leftDeg - c.steering.rightDeg) / c.steering.travelTimeS;
    double prevDelta = 0;
    for (int segment = 0; segment < waypoints.size() - 1; segment++) {
      Point a = waypoints.get(segment), b = waypoints.get(segment + 1);
      double dx = b.x - a.x, dy = b.y - a.y;
      int n = Math.max(5, (int) Math.ceil(Math.hypot(dx, dy) / .02));
      for (int j = segment == 0 ? 0 : 1; j <= n; j++) {
        double u = (double) j / n, blend = u * u * u * (10 + u * (-15 + 6 * u));
        double d = 30 * u * u * Math.pow(1 - u, 2) * dy / dx;
        double dd = 60 * u * (1 - 3 * u + 2 * u * u) * dy / (dx * dx);
        double x = a.x + dx * u, y = a.y + dy * blend, curvature = dd / Math.pow(1 + d * d, 1.5);
        double relativeYaw = Math.atan(d),
            sin = Math.sin(relativeYaw),
            cos = Math.cos(relativeYaw),
            halfWidth = c.car.widthM / 2;
        for (double along :
            new double[] {-c.car.rearOverhangM, c.car.wheelbaseM + c.car.frontOverhangM})
          for (double across : new double[] {-halfWidth, halfWidth})
            if (Math.abs(y + along * sin + across * cos) > c.limits.lateralSpaceM / 2) return null;
        double delta = Math.atan(c.car.wheelbaseM * curvature);
        if (delta < Math.toRadians(c.steering.rightDeg)
            || delta > Math.toRadians(c.steering.leftDeg)) return null;
        Point w = Geometry.world(axis, x, y);
        double yaw = heading + Math.atan(d);
        double ds =
            samples.isEmpty()
                ? 0
                : Math.hypot(
                    w.x - samples.get(samples.size() - 1).x,
                    w.y - samples.get(samples.size() - 1).y);
        s += ds;
        Sample sample = new Sample(w.x, w.y, yaw, curvature, s);
        sample.speed =
            Math.min(vmax, Math.sqrt(c.limits.lateralMps2 / Math.max(.001, Math.abs(curvature))));
        if (ds > 0 && Math.abs(delta - prevDelta) > 1e-5)
          sample.speed = Math.min(sample.speed, maxRate * ds / Math.abs(delta - prevDelta));
        Pose carPose = new Pose(w.x, w.y, yaw, 0, 0);
        for (CourseMap.Cone cone : cones)
          if (Geometry.footprintDistance(carPose, cone.point(), c)
              < c.cones.baseDiameterM / 2 + c.limits.clearanceM) return null;
        prevDelta = delta;
        samples.add(sample);
      }
    }
    samples.get(0).speed = 0;
    samples.get(samples.size() - 1).speed = 0;
    for (int i = 1; i < samples.size(); i++) {
      Sample p = samples.get(i - 1), q = samples.get(i);
      q.speed =
          Math.min(
              q.speed, Math.sqrt(p.speed * p.speed + 2 * c.limits.accelerationMps2 * (q.s - p.s)));
    }
    for (int i = samples.size() - 2; i >= 0; i--) {
      Sample p = samples.get(i), q = samples.get(i + 1);
      p.speed =
          Math.min(p.speed, Math.sqrt(q.speed * q.speed + 2 * c.limits.brakingMps2 * (q.s - p.s)));
    }
    double time = 0;
    for (int i = 1; i < samples.size(); i++)
      time +=
          2
              * (samples.get(i).s - samples.get(i - 1).s)
              / Math.max(.01, samples.get(i).speed + samples.get(i - 1).speed);
    return new Trajectory(samples, time);
  }

  public int nearest(Pose pose, int previous) {
    int best = previous;
    double d = Double.POSITIVE_INFINITY;
    for (int i = Math.max(0, previous - 3); i < samples.size() && samples.get(i).s<=samples.get(previous).s+.15; i++) {
      Sample p = samples.get(i);
      double q = Math.hypot(p.x - pose.x, p.y - pose.y);
      if (q < d) {
        d = q;
        best = i;
      }
    }
    return best;
  }
}
