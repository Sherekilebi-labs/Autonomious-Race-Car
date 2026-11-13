package org.maxbot.app;

import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
import org.maxbot.core.*;

/** Real VehicleLink serialization/UDP/acknowledgement path with a deterministic phone clock. */
public final class VehicleLinkTransportTest {
  static final String KEY="0123456789abcdef0123456789abcdef",BOOT="0123456789abcdef";
  static Field field(String name)throws Exception{Field f=VehicleLink.class.getDeclaredField(name);f.setAccessible(true);return f;}
  static void invoke(VehicleLink link,String name,Class<?> type,Object value)throws Exception{Method m=VehicleLink.class.getDeclaredMethod(name,type);m.setAccessible(true);m.invoke(link,value);}
  static final class Rig implements AutoCloseable {
    final AtomicLong now=new AtomicLong(1000);
    final DatagramSocket receiver=new DatagramSocket(0,InetAddress.getLoopbackAddress());
    final List<JSONObject> events=new ArrayList<>();final List<String> statuses=new ArrayList<>();
    final VehicleLink link;final ClockSync clock;
    Rig()throws Exception{
      receiver.setSoTimeout(500);
      Calibration c=new Calibration();c.network.pairingKey=KEY;c.network.espPort=receiver.getLocalPort();c.firmwareCalibrationId="a".repeat(64);
      link=new VehicleLink(null,c,new VehicleLink.Listener(){public void telemetry(JSONObject m,long t){events.add(m);}public void status(boolean ok,String s){statuses.add(s);}},now::get,()->now.get()*1_000_000L);
      field("boot").set(link,BOOT);field("address").set(link,InetAddress.getLoopbackAddress());field("socket").set(link,new DatagramSocket());
      clock=(ClockSync)field("clock").get(link);clock.sample(BOOT,998,1009,1009,1000);
    }
    RunSupervisor.Output motion(long source,long deadline){return new RunSupervisor.Output("DRIVE_MANUAL",8,120,deadline*1_000_000L,source*1_000_000L,0);}
    RunSupervisor.Output autoMotion(long source,long deadline){return new RunSupervisor.Output("DRIVE",8,120,deadline*1_000_000L,source*1_000_000L,0);}
    JSONObject send(RunSupervisor.Output out)throws Exception{
      invoke(link,"sendNow",RunSupervisor.Output.class,out);
      byte[] b=new byte[2048];DatagramPacket p=new DatagramPacket(b,b.length);receiver.receive(p);
      return new JSONObject(new String(b,0,p.getLength(),StandardCharsets.UTF_8));
    }
    void ack(JSONObject sent)throws Exception{
      long t=clock.toRemote(now.get(),now.get());String session=(String)field("session").get(link);
      JSONObject a=new JSONObject().put("v",1).put("type","applied").put("boot",BOOT).put("session",session)
        .put("seq",sent.getLong("seq")).put("epoch",sent.getLong("epoch")).put("rx_ms",t).put("validated_ms",t).put("applied_ms",t)
        .put("expires_ms",sent.getLong("expires_ms")).put("pwm",sent.getInt("pwm"));
      a.put("mac",Protocol.sign(KEY,"A|"+BOOT+"|"+session+"|"+a.getLong("seq")+"|"+a.getLong("epoch")+"|"+t+"|"+t+"|"+t+"|"+a.getLong("expires_ms")+"|"+a.getInt("pwm")));
      invoke(link,"receive",String.class,a.toString());
    }
    @SuppressWarnings("unchecked") RunSupervisor.Output nextUrgent()throws Exception{return ((Queue<RunSupervisor.Output>)field("urgent").get(link)).poll();}
    public void close()throws Exception{field("boot").set(link,"");link.close();receiver.close();}
  }
  @Test public void autonomousZeroAckUnlocksRealMotionAndRepeatedTicksCannotExtendOriginalDeadline()throws Exception{
    try(Rig r=new Rig()){
      RunSupervisor.Output input=r.autoMotion(1000,1100);JSONObject zero=r.send(input);assertEquals("STOP",zero.getString("mode"));r.ack(zero);
      JSONObject drive=r.send(input);assertEquals("DRIVE",drive.getString("mode"));assertEquals(120,drive.getInt("pwm"));r.ack(drive);
      assertEquals(1,r.link.timing.samples());long expiry=drive.getLong("expires_ms");
      r.now.set(1020);JSONObject repeated=r.send(input);assertTrue(repeated.getLong("expires_ms")<=expiry);assertEquals("DRIVE",repeated.getString("mode"));
      assertTrue(repeated.getLong("expires_ms")<=r.clock.toRemote(1100,1020));
      r.now.set(1100);JSONObject expired=r.send(input);assertEquals("STOP",expired.getString("mode"));assertEquals(0,expired.getInt("pwm"));
    }
  }
  @Test public void stopRejectsPreStopOutputAndFreshManualRearmDoesNotWaitForZeroAck()throws Exception{
    try(Rig r=new Rig()){
      RunSupervisor.Output old=r.motion(1000,1300);JSONObject oldDrive=r.send(old);r.ack(oldDrive);
      r.now.set(1010);r.link.special("DISARM");assertEquals("DISARM",r.send(r.nextUrgent()).getString("mode"));
      r.link.send(old);assertNull(((java.util.concurrent.atomic.AtomicReference<?>)field("newest").get(r.link)).get());
      assertEquals("STOP",r.send(old).getString("mode"));
      r.now.set(1030);r.link.special("ARM_MANUAL");assertEquals("ARM_MANUAL",r.send(r.nextUrgent()).getString("mode"));
      r.now.set(1040);JSONObject fresh=r.send(r.motion(1040,1340));
      assertEquals("DRIVE_MANUAL",fresh.getString("mode"));assertTrue(fresh.getLong("seq")>oldDrive.getLong("seq"));
      assertEquals(-1,r.link.controlStatus().getLong("pending_zero_sequence"));
    }
  }
  @Test public void resetDisarmsFirstAndStopDropsPendingArm()throws Exception{
    try(Rig r=new Rig()){
      r.link.special("ARM_MANUAL");r.link.special("RESET");
      assertEquals("DISARM",r.nextUrgent().mode);assertEquals("RESET",r.nextUrgent().mode);assertNull(r.nextUrgent());
      r.link.special("ARM_MANUAL");r.link.special("DISARM");assertEquals("DISARM",r.nextUrgent().mode);assertNull(r.nextUrgent());
      r.link.special("ARM_MANUAL");r.link.special("ESTOP");assertEquals("ESTOP",r.nextUrgent().mode);assertNull(r.nextUrgent());
    }
  }
  @Test public void uncertainClockStillBlocksAutonomousMotion()throws Exception{
    try(Rig r=new Rig()){
      r.clock.invalidate();r.clock.sample(BOOT,920,970,970,1000);
      assertTrue(r.clock.uncertaintyMs(1000)>20);
      assertEquals("STOP",r.send(r.autoMotion(1000,1100)).getString("mode"));
      assertEquals("ESP clock uncertainty exceeds 20 ms",r.link.controlStatus().getString("gate"));
    }
  }
  @Test public void absentTransportIsReportedInsteadOfClaimingSuccessfulDelivery()throws Exception{
    try(Rig r=new Rig()){
      ((DatagramSocket)field("socket").get(r.link)).close();field("socket").set(r.link,null);
      invoke(r.link,"sendNow",RunSupervisor.Output.class,new RunSupervisor.Output("STOP",0,0));
      assertEquals("",r.link.controlStatus().getString("last_sent_mode"));assertTrue(r.statuses.contains("Vehicle send failed"));assertTrue(r.events.isEmpty());
    }
  }
  @Test public void manualProducerStallSendsZeroBeforeOriginalLeaseExpires()throws Exception{
    try(Rig r=new Rig()){
      RunSupervisor.Output input=r.motion(1000,1300);JSONObject drive=r.send(input);r.ack(drive);
      r.now.set(1285);r.link.transmitPending();
      byte[] b=new byte[2048];DatagramPacket p=new DatagramPacket(b,b.length);r.receiver.receive(p);
      JSONObject stop=new JSONObject(new String(b,0,p.getLength(),StandardCharsets.UTF_8));
      assertEquals("STOP",stop.getString("mode"));assertEquals(0,stop.getInt("pwm"));
      assertTrue(r.clock.toRemote(r.now.get(),r.now.get())<drive.getLong("expires_ms"));
      assertEquals(0,field("manualStopDueNs").getLong(r.link));
    }
  }
  @Test public void nearExpiredIntentDoesNotGetAReplacementOneMillisecondMotionLease()throws Exception{
    try(Rig r=new Rig()){
      r.ack(r.send(r.motion(1000,1100)));r.ack(r.send(r.motion(1000,1100)));r.now.set(1020);
      RunSupervisor.Output input=r.motion(1020,1022);JSONObject stop=r.send(input);
      assertEquals("STOP",stop.getString("mode"));assertEquals("Remaining command budget is too short",r.link.controlStatus().getString("gate"));
    }
  }
  @Test public void thirtySecondsOfFreshManualInputDoesNotStallOrRenewOldIntent()throws Exception{
    try(Rig r=new Rig()){
      for(int n=0;n<1500;n++){
        long now=1000+n*20;r.now.set(now);
        if(n%25==0)r.clock.sample(BOOT,now-2,now+9,now+9,now);
        JSONObject sent=r.send(r.motion(now,now+300));
        assertEquals("DRIVE_MANUAL",sent.getString("mode"));assertEquals(120,sent.getInt("pwm"));
        assertTrue(sent.getLong("expires_ms")<=r.clock.toRemote(now+300,now));
        assertTrue(sent.getLong("expires_ms")-sent.getLong("issued_ms")<=300);r.ack(sent);
      }
      assertEquals(1500,r.link.timing.samples());
    }
  }

  @Test public void manualAllowsNormalWifiJitterAndKeepsItsOriginalThreeHundredMsDeadline()throws Exception{
    try(Rig r=new Rig()){
      r.clock.invalidate();r.clock.sample(BOOT,920,970,970,1000);assertTrue(r.clock.uncertaintyMs(1000)>20);
      RunSupervisor.Output input=r.motion(1000,1300);JSONObject sent=r.send(input);
      assertEquals("DRIVE_MANUAL",sent.getString("mode"));assertEquals(120,sent.getInt("pwm"));
      assertTrue(sent.getLong("expires_ms")-sent.getLong("issued_ms")<=300);
      r.now.set(1150);JSONObject repeated=r.send(input);assertEquals("DRIVE_MANUAL",repeated.getString("mode"));
      assertTrue(repeated.getLong("expires_ms")<=sent.getLong("expires_ms"));
      r.now.set(1300);assertEquals("STOP",r.send(input).getString("mode"));
    }
  }

  @Test public void manualTelemetryToleratesTwoHundredMsJitterWhileAutonomousTelemetryStaysStrict()throws Exception{
    for(boolean manual:new boolean[]{false,true})try(Rig r=new Rig()){
      String session=(String)field("session").get(r.link);long t=800;
      JSONObject m=new JSONObject().put("v",1).put("type","telemetry").put("boot",BOOT).put("session",session)
        .put("time_ms",t).put("seq",1).put("armed",true).put("manual_armed",manual).put("estop",false)
        .put("steering_mdeg",0).put("battery_mv",0).put("pot_adc",800).put("pwm",0).put("fault","")
        .put("epoch",1).put("expiry_ms",0).put("application_loss",0).put("motor_overruns",0).put("deadline_rejected",0).put("sensor_ms",t);
      String canonical="T|"+BOOT+"|"+session+"|800|1|1|0|0|0|800|0||"+(manual?1:0)+"|1|0|0|0|0|800";
      m.put("mac",Protocol.sign(KEY,canonical));invoke(r.link,"receive",String.class,m.toString());
      assertEquals(manual,r.link.connected());
    }
  }

}
