package org.maxbot.core;
/** VIO owns metric pose/speed; raw gyro supplies bounded propagation, never a second pose covariance. */
public final class StateEstimator {
  public static final class State {
    public final Geometry.Pose pose;public final long vioNs,imuNs;public final double yawRate,predictionS;public final Double positionErrorM,headingErrorRad;
    State(Geometry.Pose p,long vio,long imu,double rate,double horizon,Double pe,Double he){pose=p;vioNs=vio;imuNs=imu;yawRate=rate;predictionS=horizon;positionErrorM=pe;headingErrorRad=he;}
  }
  private Geometry.Pose vio;private long imuNs;private double yawRate;private boolean aligned;
  public synchronized void vio(Geometry.Pose next){if(next!=null&&next.finite()&&(vio==null||next.timeNs>vio.timeNs))vio=next;}
  public synchronized void gyro(long timestamp,double rate,boolean calibrated){if(timestamp>imuNs&&Double.isFinite(rate)){imuNs=timestamp;yawRate=rate;aligned=calibrated;}}
  public synchronized State predict(long now,long applicationNs,Double positionError,Double headingError){
    if(vio==null||!aligned||now<vio.timeNs||now-vio.timeNs>100_000_000L||now<imuNs||now-imuNs>50_000_000L||applicationNs<now||applicationNs-vio.timeNs>150_000_000L)return null;
    double dt=(applicationNs-vio.timeNs)*1e-9;
    Geometry.Pose p=new Geometry.Pose(vio.x+vio.speed*Math.cos(vio.yaw+yawRate*dt/2)*dt,vio.y+vio.speed*Math.sin(vio.yaw+yawRate*dt/2)*dt,vio.yaw+yawRate*dt,vio.speed,applicationNs);
    return new State(p,vio.timeNs,imuNs,yawRate,dt,positionError,headingError);
  }
}
