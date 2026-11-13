package org.maxbot.core;
/** cone_slalom_v1: pure pursuit plus yaw damping, with measured feedforward and PI anti-windup. */
public final class SlalomPolicy implements RunSupervisor.Controller {
  public static final class Parameters {
    public double l0=.25,kv=.3,ldMin=.18,ldMax=.6,kr=.05,kp=40,ki=20,maxCrossTrackM=.12;
  }
  private final Parameters p;private VehicleModel model;
  private long previousNs,previousPoseNs;private double previousYaw,yawRate,integral;
  public SlalomPolicy(Parameters parameters){p=parameters;}
  public synchronized void model(VehicleModel m){model=m;reset();}
  public synchronized void reset(){previousNs=previousPoseNs=0;integral=0;}
  public synchronized boolean available(){return model!=null&&model.missing().isEmpty();}
  public synchronized RunSupervisor.Output follow(Geometry.Pose pose,double actualDeg,Trajectory path,int index,Calibration c){return followAt(pose,actualDeg,path,index,c,pose.timeNs);}
  public synchronized RunSupervisor.Output followState(StateEstimator.State state,double actualDeg,Trajectory path,int index,Calibration c,long now){yawRate=state.yawRate;return update(state.pose,actualDeg,path,index,c,now,true);}
  public synchronized RunSupervisor.Output followAt(Geometry.Pose pose,double actualDeg,Trajectory path,int index,Calibration c,long now){return update(pose,actualDeg,path,index,c,now,false);}
  private RunSupervisor.Output update(Geometry.Pose pose,double actualDeg,Trajectory path,int index,Calibration c,long now,boolean predictedState){
    if(!available())return null;
    double dt=previousNs==0?.02:(now-previousNs)*1e-9;
    if(dt<=0||dt>.1)return null;
    if(!predictedState&&previousPoseNs!=0&&pose.timeNs>previousPoseNs)yawRate=Geometry.angle(pose.yaw-previousYaw)/((pose.timeNs-previousPoseNs)*1e-9);
    previousNs=now;previousPoseNs=pose.timeNs;previousYaw=pose.yaw;
    double delay=predictedState?0:model.applicationDelayS;
    Geometry.Pose predicted=new Geometry.Pose(pose.x+pose.speed*Math.cos(pose.yaw)*delay,pose.y+pose.speed*Math.sin(pose.yaw)*delay,pose.yaw+yawRate*delay,pose.speed,pose.timeNs);
    Trajectory.Sample current=path.samples.get(index);
    if(Math.hypot(predicted.x-current.x,predicted.y-current.y)>p.maxCrossTrackM)return null;
    double ld=Geometry.clamp(p.l0+p.kv*Math.abs(pose.speed),p.ldMin,p.ldMax);
    Trajectory.Sample preview=path.at(current.s+ld);
    Geometry.Point q=Geometry.local(predicted,new Geometry.Point(preview.x,preview.y));
    double d2=q.x*q.x+q.y*q.y;
    if(d2<1e-6||q.x<=0)return null;
    double angle=Math.atan(c.car.wheelbaseM*2*q.y/d2)-p.kr*(yawRate-pose.speed*current.curvature);
    angle=Geometry.clamp(angle,Math.toRadians(c.steering.rightDeg),Math.toRadians(c.steering.leftDeg));
    double step=model.steeringRateRadS*dt;
    angle=Geometry.clamp(angle,Math.toRadians(actualDeg)-step,Math.toRadians(actualDeg)+step);
    double target=path.at(current.s+.025).speed,error=target-pose.speed,ff=model.pwm(target);
    double candidate=Geometry.clamp(integral+error*dt,-50,50);
    double raw=ff+p.kp*error+p.ki*candidate;
    if((raw>=0&&raw<=255)||(raw>255&&error<0)||(raw<0&&error>0))integral=candidate;
    double pwm=target<=.001||pose.speed>target+.05?0:Geometry.clamp(ff+p.kp*error+p.ki*integral,0,255);
    return new RunSupervisor.Output("DRIVE",Math.toDegrees(angle),(int)Math.round(pwm));
  }
}
