package org.maxbot.core;
import java.util.*;
/** Reviewed measurements only. Null is deliberately unsupported for racing. */
public final class VehicleModel {
  public int schemaVersion=1,revision;
  public boolean reviewed;
  public String[] provenance;
  public double[][] speedPwm;
  public Double steeringRateRadS,steeringLagS,accelerationMps2,brakingMps2,lateralMps2,vioLagS,applicationDelayS;
  public double[] validSpeedRangeMps;
  public List<String> missing(){
    List<String> m=new ArrayList<>();
    if(schemaVersion!=1||!reviewed||provenance==null||provenance.length==0)m.add("Reviewed dynamics provenance required");
    for(Double v:new Double[]{steeringRateRadS,accelerationMps2,brakingMps2,lateralMps2})if(v==null||!Double.isFinite(v)||v<=0)m.add("Measured rate/acceleration/braking/grip required");
    for(Double v:new Double[]{steeringLagS,vioLagS,applicationDelayS})if(v==null||!Double.isFinite(v)||v<0)m.add("Measured actuator/filter/application lag required");
    if(validSpeedRangeMps==null||validSpeedRangeMps.length!=2||!Double.isFinite(validSpeedRangeMps[0]+validSpeedRangeMps[1])||validSpeedRangeMps[0]<0||validSpeedRangeMps[1]<=validSpeedRangeMps[0])m.add("Measured speed range required");
    if(speedPwm==null||speedPwm.length<2||speedPwm.length>128)m.add("Measured PWM/speed table required");
    else for(int i=0;i<speedPwm.length;i++){
      double[] p=speedPwm[i];
      if(p==null||p.length!=2||!Double.isFinite(p[0]+p[1])||p[0]<0||p[1]<0||p[1]>255||(i>0&&(p[0]<=speedPwm[i-1][0]||p[1]<speedPwm[i-1][1])))m.add("Invalid speed/PWM table");
    }
    return m;
  }
  public double pwm(double speed){
    if(speed<=.001)return 0;
    if(speedPwm==null||speed>speedPwm[speedPwm.length-1][0])throw new IllegalArgumentException("Speed beyond measured feedforward");
    for(int i=1;i<speedPwm.length;i++)if(speed<=speedPwm[i][0]){
      double[] a=speedPwm[i-1],b=speedPwm[i];return a[1]+(b[1]-a[1])*Geometry.clamp((speed-a[0])/(b[0]-a[0]),0,1);
    }
    return 0;
  }
}
