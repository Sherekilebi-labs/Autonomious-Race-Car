package org.maxbot.app;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;
import org.junit.Test;
import org.maxbot.core.Protocol;
import static org.junit.Assert.*;

public final class RealtimeControlTest {
  private static final String KEY="0123456789abcdef0123456789abcdef", SESSION="a".repeat(32);
  @Test public void challengeBindsKeySessionAndNonceAndNeverTransmitsKey() throws Exception {
    List<JSONObject> sent=new ArrayList<>(),input=new ArrayList<>();AtomicLong now=new AtomicLong(1000);
    RealtimeControl lane=new RealtimeControl(KEY,SESSION,sent::add,input::add,now::get);
    lane.hello();String nonce=sent.get(0).getString("nonce");
    JSONObject command=new JSONObject().put("type","command").put("action","manual").put("session_id",SESSION).put("event_id",1);
    lane.receive(command);assertTrue(input.isEmpty());
    lane.receive(new JSONObject().put("type","control_auth").put("session_id",SESSION).put("mac","0".repeat(64)));assertFalse(lane.authenticated());
    lane.receive(new JSONObject().put("type","control_auth").put("session_id",SESSION).put("mac",Protocol.sign(KEY,"RTC|"+SESSION+"|"+nonce)));assertTrue(lane.authenticated());
    lane.receive(command);assertEquals(1,input.size());assertEquals("control_ack",sent.get(sent.size()-1).getString("type"));
    lane.receive(new JSONObject(command.toString()).put("session_id","b".repeat(32)));assertEquals(1,input.size());
    lane.receive(new JSONObject().put("type","control_ping").put("session_id",SESSION).put("id",1000.125));assertEquals(1000.125,sent.get(sent.size()-1).getDouble("id"),0);
    lane.probe();lane.receive(new JSONObject().put("type","clock_sample").put("session_id",SESSION).put("t1",999));assertEquals(1,input.size());
    lane.receive(new JSONObject().put("type","clock_sample").put("session_id",SESSION).put("t1",1000));assertEquals(2,input.size());
    lane.close();lane.receive(command);assertEquals(2,input.size());assertFalse(lane.authenticated());
    assertFalse(sent.toString().contains(KEY));
    List<JSONObject> nextSent=new ArrayList<>();RealtimeControl next=new RealtimeControl(KEY,SESSION,nextSent::add,input::add,now::get);next.hello();
    next.receive(new JSONObject().put("type","control_auth").put("session_id",SESSION).put("mac",Protocol.sign(KEY,"RTC|"+SESSION+"|"+nonce)));assertFalse(next.authenticated());
  }
}
