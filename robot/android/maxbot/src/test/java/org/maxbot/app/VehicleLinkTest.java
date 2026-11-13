package org.maxbot.app;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
import org.maxbot.core.Calibration;
import org.maxbot.core.Protocol;

/** Exercises the real Android receive path using firmware-shaped, signed replies. */
public final class VehicleLinkTest {
  private static final String KEY = "0123456789abcdef0123456789abcdef";
  private static final String BOOT = "0123456789abcdef";

  private static Field field(String name) throws Exception {
    Field f = VehicleLink.class.getDeclaredField(name); f.setAccessible(true); return f;
  }
  private static void receive(VehicleLink link, JSONObject message) throws Exception {
    Method m = VehicleLink.class.getDeclaredMethod("receive", String.class);
    m.setAccessible(true); m.invoke(link, message.toString());
  }
  private static JSONObject ack(String session, boolean local) throws Exception {
    JSONObject m = new JSONObject().put("v",1).put("type",local?"local_applied":"applied")
        .put("boot",BOOT).put("session",session).put("seq",42).put("epoch",3)
        .put("rx_ms",10).put("validated_ms",11).put("applied_ms",12)
        .put("expires_ms",80).put("pwm",0);
    String canonical = "A|"+BOOT+"|"+session+"|42|3|10|11|12|80|0";
    if(local){m.put("reason","WATCHDOG");canonical+="|WATCHDOG";}
    m.put("mac",Protocol.sign(KEY,canonical));
    return m;
  }
  private static VehicleLink link(List<JSONObject> events) throws Exception {
    Calibration c = new Calibration(); c.network.pairingKey=KEY;
    VehicleLink link = new VehicleLink(null,c,new VehicleLink.Listener(){
      public void status(boolean connected,String reason){}
      public void telemetry(JSONObject message,long now){events.add(message);}
    });
    field("boot").set(link,BOOT); return link;
  }
  private static void close(VehicleLink link) throws Exception {
    field("boot").set(link,""); link.close();
  }

  @Test public void commandAppliedWithoutTimeMsAdvancesTakeoverAcknowledgement() throws Exception {
    List<JSONObject> events=new ArrayList<>(); VehicleLink link=link(events);
    try {
      JSONObject m=ack((String)field("session").get(link),false);
      assertFalse(m.has("time_ms")); receive(link,m);
      assertEquals(42,field("appliedSequence").getLong(link));
      assertEquals(3,field("appliedEpoch").getLong(link));
      assertEquals(42,link.controlStatus().getLong("ack_sequence"));
      assertEquals(0,link.controlStatus().getLong("ack_age_ms"));
      assertEquals(0,link.controlStatus().getInt("ack_pwm"));
      assertEquals(1,events.size()); assertEquals("applied",events.get(0).getString("type"));
    } finally {close(link);}
  }
  @Test public void localAppliedWithoutTimeMsReachesRecorder() throws Exception {
    List<JSONObject> events=new ArrayList<>(); VehicleLink link=link(events);
    try {
      receive(link,ack((String)field("session").get(link),true));
      assertEquals(1,events.size()); assertEquals("WATCHDOG",events.get(0).getString("reason"));
    } finally {close(link);}
  }
  @Test public void invalidSignatureSessionOrBootNeverAdvanceAcknowledgement() throws Exception {
    List<JSONObject> events=new ArrayList<>(); VehicleLink link=link(events);
    try {
      String session=(String)field("session").get(link);
      JSONObject badSignature=ack(session,false).put("mac","0".repeat(64));receive(link,badSignature);
      receive(link,ack("f".repeat(32),false));
      receive(link,ack(session,false).put("boot","f".repeat(16)));
      assertEquals(-1,field("appliedSequence").getLong(link)); assertTrue(events.isEmpty());
      receive(link,ack(session,false)); assertEquals(42,field("appliedSequence").getLong(link));
      receive(link,ack(session,false).put("seq",41)); assertEquals(42,field("appliedSequence").getLong(link));
    } finally {close(link);}
  }
  @Test public void helloAndTelemetryStillRequireTheirOwnTimeMs() throws Exception {
    List<JSONObject> events=new ArrayList<>(); VehicleLink link=link(events);
    try {
      String session=(String)field("session").get(link);
      receive(link,new JSONObject().put("v",1).put("type","hello"));
      receive(link,new JSONObject().put("v",1).put("type","telemetry").put("boot",BOOT).put("session",session));
      assertEquals(-1,field("appliedSequence").getLong(link));assertTrue(events.isEmpty());assertFalse(link.connected());
    } finally {close(link);}
  }
}
