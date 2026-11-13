package org.maxbot.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.maxbot.core.Geometry.Point;
import org.maxbot.core.Geometry.Pose;

/** Small static landmark map, in the original start-anchor coordinate system. */
public final class CourseMap {
  public static final class Cone {
    public double x, y, variance = .04;
    public int observations, independentViews;
    public Double empiricalErrorM;
    public boolean observed=true;
    private long lastFrame;

    Cone(Point p) {
      x = p.x;
      y = p.y;
    }

    public Point point() {
      return new Point(x, y);
    }

    public boolean confirmed() {
      return observations >= 5 && variance < .0064;
    }
  }

  private final List<Cone> candidates = new ArrayList<>();
  private final Pose start;
  private boolean frozen;
  private String mapEpoch=java.util.UUID.randomUUID().toString(), frameId="launch-rear-axle-v1";
  public String mapEpoch(){return mapEpoch;}
  public String frameId(){return frameId;}
  public boolean frozen(){return frozen;}
  public synchronized boolean measured(){return complete()&&cones().stream().allMatch(c->c.empiricalErrorM!=null&&Double.isFinite(c.empiricalErrorM)&&c.empiricalErrorM>=0&&c.independentViews>=2);}
  public synchronized void approveGeometry(double bound,int views){if(!Double.isFinite(bound)||bound<=0||views<2)throw new IllegalArgumentException("Independent scene validation required");for(Cone c:cones()){c.empiricalErrorM=bound;c.independentViews=views;}}

  public CourseMap(Pose start) {
    this.start = start;
  }

  public synchronized void observe(List<Point> points, long frameId) {
    if (frozen) return;
    for (Point p : points) {
      if (!Double.isFinite(p.x + p.y)) continue;
      Cone best = null;
      double distance = .18;
      for (Cone c : candidates) {
        double d = c.point().distance(p);
        if (d < distance) {
          best = c;
          distance = d;
        }
      }
      if (best == null) {
        if (candidates.size() >= 12) continue;
        best = new Cone(p);
        candidates.add(best);
      }
      if (best.lastFrame == frameId) continue;
      best.lastFrame = frameId;
      double residual = Math.pow(best.x - p.x, 2) + Math.pow(best.y - p.y, 2);
      double gain = 1.0 / Math.min(20, ++best.observations);
      best.x += gain * (p.x - best.x);
      best.y += gain * (p.y - best.y);
      best.variance = .75 * best.variance + .25 * residual;
    }
  }

  public synchronized List<Cone> cones() {
    List<Cone> out = new ArrayList<>();
    for (Cone c : candidates) if (c.confirmed()) out.add(c);
    out.sort(Comparator.comparingDouble(c -> Geometry.local(start, c.point()).x));
    return out;
  }

  public synchronized boolean complete() {
    return cones().size() == 4 && consistent();
  }

  public synchronized boolean consistent() {
    List<Cone> c = cones();
    if (c.size() < 2) return false;
    Point a = c.get(0).point(), b = c.get(c.size() - 1).point();
    double length = a.distance(b);
    if (length < .3) return false;
    double dx = (b.x - a.x) / length, dy = (b.y - a.y) / length;
    double spacing = length / (c.size() - 1);
    for (int i = 0; i < c.size(); i++) {
      Cone k = c.get(i);
      double cross = Math.abs((k.x - a.x) * dy - (k.y - a.y) * dx);
      if (cross > .06) return false;
      if (i > 0
          && Math.abs(k.point().distance(c.get(i - 1).point()) - spacing)
              > Math.max(.04, spacing * .15)) return false;
    }
    return true;
  }

  public synchronized void freeze() {
    if (!measured()) throw new IllegalStateException("Four cones plus independent measured scene error bounds required");
    frozen = true;
  }

  public synchronized boolean observationsAgree(List<Point> observations) {
    if (observations.isEmpty()) return false;
    for (Point p : observations) {
      boolean found = false;
      for (Cone c : cones())
        if (c.point().distance(p) < .1) {
          found = true;
          break;
        }
      if (!found) return false;
    }
    return true;
  }

  public Pose start() {
    return start;
  }
}
