package org.maxbot.app;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
import org.maxbot.core.ExperimentSequence;
import java.util.*;
public final class ExperimentRunnerTest {
  static final class Host implements ExperimentRunner.Host {
    boolean armed,healthy=true,autoStart=true;String state="RUN";int arms,stops,inputs,thermal;final List<String> events=new ArrayList<>();
    public void arm(boolean autonomous){arms++;}public void stop(String reason){stops++;}public boolean armed(boolean autonomous){return armed;}public boolean healthy(){return healthy;}public int thermalStatus(){return thermal;}
    public boolean startAutonomy(long now){return autoStart;}public String autonomyState(){return state;}
    public boolean input(double a,int p,long n,long d){assertTrue(d>n);assertTrue(d-n<=80_000_000L);inputs++;return true;}
    public void event(JSONObject e){events.add(e.optString("status"));}
  }
  JSONObject request(String operation,long now,long ordinal)throws Exception{return new JSONObject().put("operation",operation).put("experiment_id","test-id").put("ordinal",ordinal).put("mapped_deadline_ns",Long.toString(now+250_000_000L)).put("steps",new JSONArray().put(new JSONObject().put("duration_ms",500).put("pwm",120).put("steering_deg",0)).put(new JSONObject().put("duration_ms",1000).put("pwm",0).put("steering_deg",0)));}
  @Test public void expiredLeaseCannotRestartOrBeRenewed()throws Exception{Host h=new Host();ExperimentRunner r=new ExperimentRunner(h);r.receive(request("start",0,1),0);assertEquals(1,h.arms);r.tick(260_000_000L);assertFalse(r.active());r.receive(request("lease",270_000_000L,2),270_000_000L);assertFalse(r.active());assertEquals(0,h.inputs);}
  @Test public void localTicksRequireRealArmAckAndStopOnTakeover()throws Exception{Host h=new Host();ExperimentRunner r=new ExperimentRunner(h);r.receive(request("start",0,1),0);r.tick(20_000_000L);assertEquals(0,h.inputs);h.armed=true;r.tick(40_000_000L);assertEquals(1,h.inputs);r.cancel("takeover",50_000_000L);r.tick(60_000_000L);assertEquals(1,h.inputs);assertEquals("aborted",h.events.get(h.events.size()-1));}
  @Test public void durationAndNonzeroLastStepRejectedBeforeArm()throws Exception{Host h=new Host();ExperimentRunner r=new ExperimentRunner(h);JSONObject m=request("start",0,1);m.getJSONArray("steps").getJSONObject(0).put("pwm",200);assertThrows(IllegalArgumentException.class,()->r.receive(m,0));assertEquals(0,h.arms);}
  @Test public void clockRegressionOrControlStallCancelsSequence(){ExperimentSequence sequence=new ExperimentSequence(List.of(new ExperimentSequence.Step(500,0,0)),1_000_000L);assertNotNull(sequence.sample(20_000_000L));assertNull(sequence.sample(121_000_000L));assertFalse(sequence.renew(122_000_000L));}
  @Test public void autonomousTrialsUseExistingSupervisorInsteadOfManualInputs()throws Exception{Host h=new Host();h.armed=true;ExperimentRunner r=new ExperimentRunner(h);r.receive(request("start",0,1).put("recipe","slalom"),0);r.tick(20_000_000L);assertEquals(0,h.inputs);h.state="FINISHED";r.tick(40_000_000L);assertFalse(r.active());assertEquals("completed",h.events.get(h.events.size()-1));}
  @Test public void explicitEndpointRejectsCredentialsAndUnexpectedPaths()throws Exception{assertEquals("ws://10.2.3.4:8081/robot",DashboardLink.endpoint("ws://10.2.3.4:8081/robot"));assertThrows(IllegalArgumentException.class,()->DashboardLink.endpoint("ws://user:password@host/robot"));assertThrows(IllegalArgumentException.class,()->DashboardLink.endpoint("https://host/robot"));}
  JSONObject hotRequest(long now)throws Exception{
    JSONObject m=request("start",now,1).put("recipe","forward_onset").put("allow_severe_thermal",true);
    m.getJSONArray("steps").getJSONObject(0).put("duration_ms",100).put("pwm",110);return m;
  }
  @Test public void explicitThermalAllowanceAcceptsOnlySevereAndStillStopsAtCritical()throws Exception{
    Host h=new Host();h.thermal=3;h.armed=true;ExperimentRunner r=new ExperimentRunner(h);
    assertThrows(IllegalStateException.class,()->r.receive(request("start",0,1),0));assertEquals(0,h.arms);
    r.receive(hotRequest(0),0);r.tick(20_000_000L);assertEquals(1,h.inputs);assertTrue(r.allowsSevereThermal());
    h.thermal=4;r.tick(40_000_000L);assertFalse(r.active());assertEquals("aborted",h.events.get(h.events.size()-1));assertFalse(r.allowsSevereThermal());
    assertFalse(r.thermalAllowed(3));assertFalse(r.thermalAllowed(-1));
  }
  @Test public void criticalHeatAndExcessMotionAreRejectedBeforeArming()throws Exception{
    Host h=new Host();ExperimentRunner r=new ExperimentRunner(h);h.thermal=4;
    assertThrows(IllegalStateException.class,()->r.receive(hotRequest(0),0));h.thermal=3;
    JSONObject tooLong=hotRequest(0);tooLong.getJSONArray("steps").getJSONObject(0).put("duration_ms",101);
    assertThrows(IllegalArgumentException.class,()->r.receive(tooLong,0));
    JSONObject tooStrong=hotRequest(0);tooStrong.getJSONArray("steps").getJSONObject(0).put("pwm",111);
    assertThrows(IllegalArgumentException.class,()->r.receive(tooStrong,0));
    JSONObject twoPulses=hotRequest(0);twoPulses.getJSONArray("steps").getJSONObject(1).put("duration_ms",100).put("pwm",110);twoPulses.getJSONArray("steps").put(new JSONObject().put("duration_ms",1000).put("pwm",0).put("steering_deg",0));
    assertThrows(IllegalArgumentException.class,()->r.receive(twoPulses,0));
    assertThrows(IllegalArgumentException.class,()->r.receive(hotRequest(0).put("recipe","slalom"),0));
    assertThrows(IllegalArgumentException.class,()->r.receive(hotRequest(0).put("allow_severe_thermal","true"),0));assertEquals(0,h.arms);
  }
  @Test public void thermalAllowanceDoesNotOverrideDeviceHealthLeaseOrOutputLimits()throws Exception{
    Host h=new Host();h.thermal=3;h.armed=true;ExperimentRunner r=new ExperimentRunner(h);
    r.receive(hotRequest(0),0);h.healthy=false;r.tick(20_000_000L);assertFalse(r.active());assertEquals(0,h.inputs);
    h.healthy=true;r.receive(hotRequest(0),0);r.tick(260_000_000L);assertFalse(r.active());assertEquals(0,h.inputs);
    r.receive(hotRequest(0),0);assertFalse(r.acceptsOutput(111,0,20_000_000L));assertFalse(r.active());
  }
  @Test public void stationarySevereTrialNeverArmsTheMotors()throws Exception{
    Host h=new Host();h.thermal=3;ExperimentRunner r=new ExperimentRunner(h);JSONObject m=hotRequest(0).put("recipe","camera_clock");m.getJSONArray("steps").getJSONObject(0).put("pwm",0);r.receive(m,0);r.tick(20_000_000L);assertTrue(r.active());assertEquals(0,h.arms);assertEquals(0,h.inputs);r.cancel("done",40_000_000L);assertFalse(r.allowsSevereThermal());
  }
}
