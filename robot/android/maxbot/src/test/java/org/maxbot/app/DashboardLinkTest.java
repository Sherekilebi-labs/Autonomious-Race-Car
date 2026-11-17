package org.maxbot.app;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import okhttp3.*;
import okio.ByteString;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class DashboardLinkTest {
  static Field field(String name)throws Exception{Field f=DashboardLink.class.getDeclaredField(name);f.setAccessible(true);return f;}
  static final class Socket implements WebSocket {
    final List<String> sent=new ArrayList<>();boolean closed,cancelled;
    public Request request(){return new Request.Builder().url("ws://localhost/robot").build();}
    public long queueSize(){return 0;}public boolean send(String s){sent.add(s);return true;}public boolean send(ByteString b){return false;}
    public boolean close(int code,String reason){closed=true;return true;}public void cancel(){cancelled=true;}
  }
  static final class Rig implements AutoCloseable {
    final AtomicLong now=new AtomicLong(1000);final List<JSONObject> commands=new ArrayList<>();int disconnected;long eventId;
    final DashboardLink link=new DashboardLink(null,"ws://localhost/robot","0123456789abcdef0123456789abcdef",new DashboardLink.Listener(){public void command(JSONObject m){commands.add(m);}public void disconnected(){disconnected++;}},now::get,false);
    final Socket socket=new Socket();
    Rig()throws Exception{field("socket").set(link,socket);link.onMessage(socket,"{\"type\":\"authenticated\"}");link.onMessage(socket,new JSONObject().put("type","operator_joined").put("session_id","a".repeat(32)).toString());commands.clear();}
    void sample()throws Exception{now.set(1006);link.onMessage(socket,new JSONObject().put("type","clock_sample").put("t1",1000).put("t2",11003).put("t3",11003).toString());}
    JSONObject manual(long event,long deadline)throws Exception{return new JSONObject().put("type","command").put("action","manual").put("session_id","a".repeat(32)).put("event_id",++eventId).put("steering_deg",5).put("pwm",120).put("deadman",true).put("event_ms",event).put("deadline_ms",deadline);}
    public void close(){link.close();}
  }
  @Test public void manualDeadlinesAreMappedWithoutRenewingExpiredIntents()throws Exception{
    try(Rig r=new Rig()){
      r.link.onMessage(r.socket,r.manual(11000,11100).toString());assertEquals(1000_000_000L,r.commands.get(0).getLong("mapped_deadline_ns"));
      r.sample();r.now.set(1010);r.link.onMessage(r.socket,r.manual(11000,11100).toString());long deadline=r.commands.get(1).getLong("mapped_deadline_ns");
      assertTrue(deadline>1010_000_000L);assertTrue(deadline<=1100_000_000L);
      r.now.set(1110);r.link.onMessage(r.socket,r.manual(11000,11100).toString());assertTrue(r.commands.get(2).getLong("mapped_deadline_ns")<1110_000_000L);
    }
  }
  @Test public void remoteCloseImmediatelyRevokesAuthorityAndLateFramesAreIgnored()throws Exception{
    try(Rig r=new Rig()){
      r.sample();r.link.onClosing(r.socket,1000,"server restart");assertEquals(1,r.disconnected);assertTrue(r.socket.closed);
      assertFalse(field("authenticated").getBoolean(r.link));assertNull(field("socket").get(r.link));
      r.link.onMessage(r.socket,r.manual(11000,11100).toString());assertTrue(r.commands.isEmpty());
      r.link.onClosed(r.socket,1000,"server restart");assertEquals(1,r.disconnected);
    }
  }
  @Test public void stalledAuthenticationIsCancelledInsteadOfBlockingReconnectForever()throws Exception{
    try(Rig r=new Rig()){
      field("authenticated").setBoolean(r.link,false);field("connecting").setBoolean(r.link,true);field("attemptStartedMs").setLong(r.link,1000);
      r.now.set(6000);Method connect=DashboardLink.class.getDeclaredMethod("connect");connect.setAccessible(true);connect.invoke(r.link);
      assertTrue(r.socket.cancelled);assertFalse(field("connecting").getBoolean(r.link));assertTrue(r.disconnected>=1);
    }
  }
  @Test public void simpleManualBudgetCanExceedAutonomousHundredMsWithoutRenewal()throws Exception{
    try(Rig r=new Rig()){
      r.sample();r.now.set(1010);r.link.onMessage(r.socket,r.manual(11000,11300).toString());
      long deadline=r.commands.get(0).getLong("mapped_deadline_ns");assertTrue(deadline>1200_000_000L);assertTrue(deadline<=1300_000_000L);
      r.now.set(1310);r.link.onMessage(r.socket,r.manual(11000,11300).toString());assertTrue(r.commands.get(1).getLong("mapped_deadline_ns")<1310_000_000L);
    }
  }

  @Test public void directAndFallbackShareReplayOrderingAndOperatorRevocation()throws Exception{
    try(Rig r=new Rig()){
      r.sample();r.link.onMessage(r.socket,new JSONObject().put("type","operator_joined").put("session_id","a".repeat(32)).toString());r.commands.clear();
      JSONObject first=r.manual(11000,11300),second=r.manual(11001,11301);
      r.link.receiveRealtime(second);assertEquals(1,r.commands.size());assertEquals("webrtc",r.link.controlTiming().getString("transport"));
      r.link.onMessage(r.socket,first.toString());r.link.onMessage(r.socket,second.toString());assertEquals(1,r.commands.size());
      JSONObject wrong=r.manual(11002,11302).put("session_id","b".repeat(32));r.link.receiveRealtime(wrong);assertEquals(1,r.commands.size());
      r.link.onMessage(r.socket,"{\"type\":\"operator_left\"}");r.commands.clear();r.link.receiveRealtime(r.manual(11003,11303));assertTrue(r.commands.isEmpty());
    }
  }

  @Test public void malformedDirectInputsCannotAdvanceReplayWindowOrRenewLease() throws Exception {
    try(Rig r=new Rig()){
      r.sample();JSONObject packet=r.manual(11000,11300);long event=packet.getLong("event_id");
      for(JSONObject bad:List.of(new JSONObject(packet.toString()).put("event_id",event+.5),new JSONObject(packet.toString()).put("deadline_ms",11301),new JSONObject(packet.toString()).put("pwm",2.5),new JSONObject(packet.toString()).put("deadman",1))){r.link.receiveRealtime(bad);assertTrue(r.commands.isEmpty());}
      r.link.receiveRealtime(packet);assertEquals(1,r.commands.size());
      r.link.onMessage(r.socket,"{\"type\":\"operator_left\"}");r.commands.clear();r.link.onMessage(r.socket,r.manual(11001,11301).toString());assertTrue(r.commands.isEmpty());
    }
  }

  @Test public void delayedWebSocketProbeCannotResetNewerDirectClockSample()throws Exception{
    try(Rig r=new Rig()){
      r.now.set(1007);r.link.receiveRealtime(new JSONObject().put("type","clock_sample").put("session_id","a".repeat(32)).put("t1",1002).put("t2",11004).put("t3",11004));
      double bound=r.link.controlTiming().getDouble("clock_uncertainty_ms");
      r.now.set(1015);r.link.onMessage(r.socket,new JSONObject().put("type","clock_sample").put("t1",1000).put("t2",11002).put("t3",11002).toString());
      assertTrue(r.link.controlTiming().getDouble("clock_uncertainty_ms")<bound+.1);
    }
  }

}
