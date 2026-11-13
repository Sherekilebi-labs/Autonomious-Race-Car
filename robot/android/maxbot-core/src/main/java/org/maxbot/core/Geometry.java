package org.maxbot.core;

public final class Geometry {
  private Geometry() {}

  public static final class Point {
    public final double x, y;

    public Point(double x, double y) {
      this.x = x;
      this.y = y;
    }

    public double distance(Point p) {
      return Math.hypot(x - p.x, y - p.y);
    }
  }

  public static final class Pose {
    public final double x, y, yaw, speed;
    public final long timeNs;

    public Pose(double x, double y, double yaw, double speed, long timeNs) {
      this.x = x;
      this.y = y;
      this.yaw = yaw;
      this.speed = speed;
      this.timeNs = timeNs;
    }

    public Point position() {
      return new Point(x, y);
    }

    public boolean finite() {
      return Double.isFinite(x + y + yaw + speed);
    }
  }

  public static double clamp(double v, double lo, double hi) {
    return Math.max(lo, Math.min(hi, v));
  }

  public static double angle(double a) {
    return Math.atan2(Math.sin(a), Math.cos(a));
  }

  public static Point local(Pose p, Point q) {
    double dx = q.x - p.x, dy = q.y - p.y, c = Math.cos(p.yaw), s = Math.sin(p.yaw);
    return new Point(c * dx + s * dy, -s * dx + c * dy);
  }

  public static Point world(Pose p, double x, double y) {
    double c = Math.cos(p.yaw), s = Math.sin(p.yaw);
    return new Point(p.x + c * x - s * y, p.y + s * x + c * y);
  }
  /** Distance of a cone center from the complete oriented vehicle rectangle. */
  public static double footprintDistance(Pose p, Point cone, Calibration c) {
    Point q = local(p, cone);
    double dx =
        Math.max(
            Math.max(-c.car.rearOverhangM - q.x, 0), q.x - c.car.wheelbaseM - c.car.frontOverhangM);
    double dy = Math.max(Math.abs(q.y) - c.car.widthM / 2, 0);
    return Math.hypot(dx, dy);
  }
  public static Point coneCenterFromRim(Point rim,Point camera,double radius){
    double d=rim.distance(camera);if(!Double.isFinite(d)||d<radius||radius<=0)return null;
    return new Point(rim.x+radius*(rim.x-camera.x)/d,rim.y+radius*(rim.y-camera.y)/d);
  }
  /** A camera ray (right, down, forward) rotated into world coordinates hits z=floor. */
  public static Point groundRay(
      double u,
      double v,
      double fx,
      double fy,
      double cx,
      double cy,
      double[] origin,
      double[] right,
      double[] down,
      double[] forward,
      double floorY) {
    double rx = (u - cx) / fx, ry = (v - cy) / fy;
    double dx = right[0] * rx + down[0] * ry + forward[0];
    double dy = right[1] * rx + down[1] * ry + forward[1];
    double dz = right[2] * rx + down[2] * ry + forward[2];
    if (!Double.isFinite(dx + dy + dz) || Math.abs(dy)/Math.sqrt(dx*dx+dy*dy+dz*dz) < .04) return null;
    double t = (floorY - origin[1]) / dy;
    return t > 0 && t < 5 ? new Point(origin[0] + t * dx, -(origin[2] + t * dz)) : null;
  }
}
