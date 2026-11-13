package org.maxbot.core;
import java.util.*;
/** One-sided, bounded scouting; hidden cones remain priors and never enter the observed map. */
public final class ScoutPlanner {
  private ScoutPlanner(){}
  public static Trajectory segment(Calibration c,Geometry.Pose start,List<CourseMap.Cone> observed,VehicleModel model){
    if(model==null||!model.missing().isEmpty())throw new IllegalStateException("Reviewed low-speed scout dynamics required");
    double end=observed.isEmpty()?.6:Geometry.local(start,observed.get(observed.size()-1).point()).x+.25;
    end=Math.min(end,3.7);List<Geometry.Point> row=new ArrayList<>();for(int i=0;i<4;i++)row.add(Geometry.world(start,1.31194+.7*i,0));
    for(CourseMap.Cone cone:observed)row.add(cone.point());
    List<Trajectory.Sample> samples=new ArrayList<>();double x=start.x,y=start.y,yaw=start.yaw,s=0;double maxK=Math.tan(Math.toRadians(Math.min(-c.steering.rightDeg,c.steering.leftDeg)))/c.car.wheelbaseM;
    double previousAngle=0;int limit=800;
    while(limit-->0){
      double k=s<1.25?-maxK*Math.sin(2*Math.PI*s/1.25):0;
      if(Geometry.local(start,new Geometry.Point(x,y)).x>=end&&s>1.25)break;
      Trajectory.Sample p=new Trajectory.Sample(x,y,yaw,k,s);double angle=Math.atan(c.car.wheelbaseM*k);
      p.speed=Math.min(.1,Math.min(c.limits.scoutSpeedMps,model.validSpeedRangeMps[1]));
      if(!samples.isEmpty())p.speed=Math.min(p.speed,model.steeringRateRadS*.01/Math.max(1e-9,Math.abs(angle-previousAngle)));
      previousAngle=angle;
      for(Geometry.Point cone:row)if(Geometry.footprintDistance(new Geometry.Pose(x,y,yaw,0,0),cone,c)<c.cones.baseDiameterM/2+c.limits.clearanceM+.02)throw new IllegalStateException("Next scout segment lacks row-envelope clearance");
      for(double a:new double[]{-c.car.rearOverhangM,c.car.wheelbaseM+c.car.frontOverhangM})for(double b:new double[]{-c.car.widthM/2,c.car.widthM/2})if(Math.abs(Geometry.local(start,Geometry.world(new Geometry.Pose(x,y,yaw,0,0),a,b)).y)>.5*c.limits.lateralSpaceM-.02)throw new IllegalStateException("Scout floor limit");
      samples.add(p);double mid=yaw+k*.005;x+=.01*Math.cos(mid);y+=.01*Math.sin(mid);yaw+=.01*k;s+=.01;
    }
    if(samples.size()<3||limit<=0)throw new IllegalStateException("Unjustified scout horizon");
    samples.get(0).speed=0;samples.get(samples.size()-1).speed=0;
    for(int i=1;i<samples.size();i++)samples.get(i).speed=Math.min(samples.get(i).speed,Math.sqrt(samples.get(i-1).speed*samples.get(i-1).speed+2*model.accelerationMps2*.01));
    for(int i=samples.size()-2;i>=0;i--)samples.get(i).speed=Math.min(samples.get(i).speed,Math.sqrt(samples.get(i+1).speed*samples.get(i+1).speed+2*model.brakingMps2*.01));
    return new Trajectory(samples,Double.NaN);
  }
}
