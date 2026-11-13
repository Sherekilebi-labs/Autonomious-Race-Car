package org.maxbot.core;
import java.util.*;
/** Independent phone validation of Mac artifacts, including conservative between-sample bounds. */
public final class ReferenceTrajectory {
  public int schemaVersion,revision;
  public String referenceId,mapEpoch,frameId,calibrationId,geometryId,vehicleModelId,planningParametersId,cameraId,modelId;
  public int[] sides;
  public double clearanceM,uncertaintyM,finishS;
  public List<Trajectory.Sample> samples;
  public static final class Validator {public String version;public boolean feasible;public List<String> diagnostics;}
  public Validator validator;
  public List<String> validate(Calibration c,VehicleModel model,CourseMap map,String dynamicsId){
    List<String> errors=new ArrayList<>();
    if(schemaVersion!=1||revision<1||referenceId==null||!referenceId.matches("[a-f0-9]{64}")||validator==null||!validator.feasible)errors.add("Unsupported/unvalidated reference");
    if(map==null||!map.frozen()||!Objects.equals(mapEpoch,map.mapEpoch())||!Objects.equals(frameId,map.frameId()))errors.add("Map/frame identity mismatch");
    if(!Objects.equals(calibrationId,c.firmwareCalibrationId)||!Objects.equals(geometryId,c.geometryId)||!Objects.equals(vehicleModelId,dynamicsId))errors.add("Dependency mismatch");
    if(!Arrays.equals(sides,new int[]{-1,1,-1,1})||!Double.isFinite(clearanceM+uncertaintyM+finishS)||clearanceM<.15||uncertaintyM<0)errors.add("Invalid side/margin/finish contract");
    if(model==null||!model.missing().isEmpty())errors.add("Reviewed dynamics required");
    if(samples==null||samples.size()<3||samples.size()>8192)errors.add("Invalid sample count");
    if(!errors.isEmpty())return errors;
    if(!c.missing().isEmpty()){errors.add("Calibration incomplete");return errors;}
    double radius=Math.hypot(c.car.wheelbaseM+c.car.frontOverhangM,c.car.widthM/2);
    double minMargin=Double.POSITIVE_INFINITY;
    for(int i=0;i<samples.size();i++){
      Trajectory.Sample b=samples.get(i);
      if(b==null||!Double.isFinite(b.x+b.y+b.yaw+b.curvature+b.s+b.speed)||b.speed<0||b.speed>model.validSpeedRangeMps[1]||b.speed>c.limits.maxSpeedMps){errors.add("Nonfinite/out-of-range sample");break;}
      double angle=Math.atan(c.car.wheelbaseM*b.curvature);
      if(angle<Math.toRadians(c.steering.rightDeg)-1e-8||angle>Math.toRadians(c.steering.leftDeg)+1e-8)errors.add("Curvature limit");
      if(b.speed*b.speed*Math.abs(b.curvature)>model.lateralMps2+1e-6)errors.add("Lateral limit");
      Trajectory.Sample a=i==0?b:samples.get(i-1);double ds=b.s-a.s,dyaw=Geometry.angle(b.yaw-a.yaw);
      if(i>0&&(ds<=0||ds>.051||Math.abs(Math.hypot(b.x-a.x,b.y-a.y)-ds)>.002||Math.abs(dyaw-(a.curvature+b.curvature)*.5*ds)>.003)){errors.add("Invalid interpolation/bicycle progression");break;}
      if(i>0){
        double da=Math.abs(Math.atan(c.car.wheelbaseM*b.curvature)-Math.atan(c.car.wheelbaseM*a.curvature));
        if(da*Math.max(a.speed,b.speed)>model.steeringRateRadS*ds+1e-6)errors.add("Steering rate limit");
        if(b.speed*b.speed-a.speed*a.speed>2*model.accelerationMps2*ds+1e-6||a.speed*a.speed-b.speed*b.speed>2*model.brakingMps2*ds+1e-6)errors.add("Acceleration/braking limit");
      }
      // Lipschitz distance bound: translation + corner radius * rotation. Subdivide to 1 mm.
      int n=Math.max(1,(int)Math.ceil((Math.hypot(b.x-a.x,b.y-a.y)+radius*Math.abs(dyaw))/.002));
      for(int j=0;j<n;j++){
        double t=(j+.5)/n;Geometry.Point world=Geometry.world(map.start(),a.x+t*(b.x-a.x),a.y+t*(b.y-a.y));Geometry.Pose pose=new Geometry.Pose(world.x,world.y,map.start().yaw+a.yaw+t*dyaw,0,0);
        double bound=(Math.hypot(b.x-a.x,b.y-a.y)+radius*Math.abs(dyaw))/(2*n);
        for(CourseMap.Cone cone:map.cones())minMargin=Math.min(minMargin,Geometry.footprintDistance(pose,cone.point(),c)-c.cones.baseDiameterM/2-clearanceM-uncertaintyM-bound);
        for(double x:new double[]{-c.car.rearOverhangM,c.car.wheelbaseM+c.car.frontOverhangM})for(double y:new double[]{-c.car.widthM/2,c.car.widthM/2}){
          Geometry.Point q=Geometry.local(map.start(),Geometry.world(pose,x,y));
          if(c.limits.floorBoundsM!=null){double[] floor=c.limits.floorBoundsM;if(q.x-bound-uncertaintyM<floor[0]||q.x+bound+uncertaintyM>floor[1]||q.y-bound-uncertaintyM<floor[2]||q.y+bound+uncertaintyM>floor[3])errors.add("Floor limits");}
          else {if(Math.abs(q.y)+bound+uncertaintyM>c.limits.lateralSpaceM/2)errors.add("Floor lateral limit");if(q.x+bound+uncertaintyM>Geometry.local(map.start(),map.cones().get(3).point()).x+c.limits.finishClearM)errors.add("Finish floor limit");}
        }
      }
      if(errors.size()>32)break;
    }
    if(minMargin<0)errors.add("Continuous footprint clearance");
    if(samples.get(0).s!=0||Math.abs(samples.get(0).speed)>.001||samples.get(samples.size()-1).speed!=0||finishS<=0||finishS>=samples.get(samples.size()-1).s)errors.add("Launch/braking tail missing");
    int passed=0;
    for(Trajectory.Sample p:samples){
      if(passed>=4)break;
      Geometry.Point q=new Geometry.Point(p.x,p.y);
      Geometry.Point cone=Geometry.local(map.start(),map.cones().get(passed).point());
      if(q.x>=cone.x){if(sides[passed]*(q.y-cone.y)<c.car.widthM/2+c.cones.baseDiameterM/2+clearanceM+uncertaintyM)errors.add("Wrong passage side");passed++;}
    }
    if(passed!=4)errors.add("Incomplete passage");
    Trajectory.Sample finish=trajectory().at(finishS);
    Geometry.Point fw=Geometry.world(map.start(),finish.x,finish.y);
    if(minimumProjection(new Geometry.Pose(fw.x,fw.y,map.start().yaw+finish.yaw,finish.speed,0),map.start(),c)<=Geometry.local(map.start(),map.cones().get(3).point()).x+c.cones.baseDiameterM/2+uncertaintyM)errors.add("Finish before rear clearance");
    return new ArrayList<>(new LinkedHashSet<>(errors));
  }
  public Trajectory trajectory(CourseMap map){List<Trajectory.Sample> world=new ArrayList<>();for(Trajectory.Sample p:samples){Geometry.Point q=Geometry.world(map.start(),p.x,p.y);Trajectory.Sample sample=new Trajectory.Sample(q.x,q.y,map.start().yaw+p.yaw,p.curvature,p.s);sample.speed=p.speed;world.add(sample);}return new Trajectory(List.copyOf(world),Double.NaN);}
  public Trajectory trajectory(){return new Trajectory(List.copyOf(samples),Double.NaN);}
  public static double minimumProjection(Geometry.Pose p,Geometry.Pose axis,Calibration c){
    double min=Double.POSITIVE_INFINITY;
    for(double x:new double[]{-c.car.rearOverhangM,c.car.wheelbaseM+c.car.frontOverhangM})for(double y:new double[]{-c.car.widthM/2,c.car.widthM/2})min=Math.min(min,Geometry.local(axis,Geometry.world(p,x,y)).x);
    return min;
  }
}
