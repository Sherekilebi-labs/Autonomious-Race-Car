package org.maxbot.core;

import org.maxbot.app.NativeMpc;

/** Runs the actual generated C solver/JNI on the host, not a mocked controller. */
public final class NativeControllerTest {
  public static void main(String[] args) throws Exception{
    Calibration c=CoreTest.calibrated();
    CourseMap map=new CourseMap(new Geometry.Pose(0,0,0,0,1));
    for(int i=1;i<=25;i++)map.observe(java.util.List.of(new Geometry.Point(2,0),new Geometry.Point(4,0),new Geometry.Point(6,0),new Geometry.Point(8,0)),i);
    Trajectory path=Trajectory.plan(c,map.start(),map.cones(),false);
    NativeMpc controller=new NativeMpc();if(!controller.available())throw new AssertionError("Native library unavailable");
    int solved=0;long worst=0;
    for(int j=0;j<path.samples.size()-10;j+=10){
      Trajectory.Sample p=path.samples.get(j);double steering=Math.toDegrees(Math.atan(c.car.wheelbaseM*p.curvature));
      long start=System.nanoTime();RunSupervisor.Output out=controller.follow(new Geometry.Pose(p.x,p.y,p.yaw,p.speed,1_000_000_000L+j*20_000_000L),steering,path,j,c);
      worst=Math.max(worst,System.nanoTime()-start);
      if(out==null)throw new AssertionError("MPC failed at path sample "+j);
      if(Math.abs(out.steeringDeg)>35.01||out.pwm<0||out.pwm>255)throw new AssertionError("MPC output outside measured limits");solved++;
    }
    RunSupervisor.Output start=controller.follow(new Geometry.Pose(0,0,0,0,2_000_000_000L),0,path,0,c);
    if(start==null||start.pwm<c.limits.tractionMinPwm)throw new AssertionError("Motor cannot overcome measured deadband at launch");
    System.out.println("PASS "+solved+" native MPC path samples; worst host solve "+worst/1e6+" ms (not a Pixel benchmark)");
    verifyOptionalGate(c);
    Calibration manualPower=CoreTest.calibrated();
    manualPower.power.batteryMonitoring=false;manualPower.power.senseSource="disabled";
    manualPower.power.fullV=manualPower.power.warningV=manualPower.power.stopV=null;
    manualPower.power.dividerRatio=manualPower.power.adcReferenceMv=manualPower.power.adcOffsetMv=null;
    NativeMpc staleVoltage=new NativeMpc();
    java.lang.reflect.Method batterySetter=NativeMpc.class.getDeclaredMethod("battery",double.class);
    batterySetter.setAccessible(true);batterySetter.invoke(staleVoltage,7.0);
    // An old measured voltage must not trigger compensation or dereference a
    // missing fullV after switching to manual battery checks.
    RunSupervisor.Output uncompensated=staleVoltage.follow(new Geometry.Pose(0,0,0,0,3_000_000_000L),0,path,0,manualPower);
    if(uncompensated==null)throw new AssertionError("Disabled monitoring rejected a stale voltage sample");
    verifyOptionalGate(manualPower);
  }
  private static void verifyOptionalGate(Calibration c){
    NativeMpc controller=new NativeMpc();RunSupervisor run=new RunSupervisor(c,controller);
    long now=1_000_000_000L;run.modelReady(true);run.link(true);run.localization(new Geometry.Pose(0,0,0,0,now),true);run.imu(now,0,true);run.heartbeat(now);run.telemetry(0,c.power.batteryMonitoring?8:0,now,true,"");
    if(run.startRun(now)||run.tick(now).pwm!=0)throw new AssertionError("Optional native controller bypassed Mac reference/launch gates");
    System.out.println("PASS optional MPC never bypasses staged reference/launch gates; battery monitoring="+c.power.batteryMonitoring);
  }
}
