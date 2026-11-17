package org.maxbot.app;

import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.json.JSONObject;
import org.maxbot.core.Protocol;

/** Session-bound authentication for the lossy, unordered RTC control lane. */
final class RealtimeControl {
  private final String key,session,nonce=UUID.randomUUID().toString().replace("-","");
  private final Consumer<JSONObject> send,input;
  private final LongSupplier clock;
  private boolean authenticated,closed;private long lastProbe=-1;
  RealtimeControl(String key,String session,Consumer<JSONObject> send,Consumer<JSONObject> input,LongSupplier clock){this.key=key;this.session=session;this.send=send;this.input=input;this.clock=clock;}
  synchronized void hello(){if(closed||session.isEmpty())return;try{send.accept(new JSONObject().put("type",authenticated?"control_ready":"control_challenge").put("session_id",session).put("nonce",nonce));}catch(Exception ignored){}}
  synchronized void receive(JSONObject m){
    if(closed)return;
    try{
      String type=m.optString("type");
      if("control_auth".equals(type)){
        if(!session.equals(m.optString("session_id"))||!Protocol.verify(key,"RTC|"+session+"|"+nonce,m.optString("mac")))return;
        authenticated=true;hello();return;
      }
      if(!authenticated||!session.equals(m.optString("session_id")))return;
      if("control_ping".equals(type)){send.accept(new JSONObject().put("type","control_pong").put("id",m.getDouble("id")));return;}
      if("clock_sample".equals(type)){if(m.getLong("t1")==lastProbe){lastProbe=-1;input.accept(m);}return;}
      if("command".equals(type)&&"manual".equals(m.optString("action"))){
        input.accept(m);send.accept(new JSONObject().put("type","control_ack").put("event_id",m.getLong("event_id")));
      }
    }catch(Exception ignored){}
  }
  synchronized void probe(){if(!authenticated||closed)return;try{lastProbe=clock.getAsLong();send.accept(new JSONObject().put("type","control_clock").put("t1",lastProbe).put("session_id",session));}catch(Exception ignored){}}
  synchronized boolean authenticated(){return authenticated&&!closed;}
  synchronized void close(){closed=true;authenticated=false;}
}
