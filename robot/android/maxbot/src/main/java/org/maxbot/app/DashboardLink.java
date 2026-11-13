package org.maxbot.app;
import android.content.Context;
import android.os.SystemClock;
import okhttp3.*;
import org.json.JSONObject;
import org.maxbot.core.*;
import org.maxbot.core.Protocol;
import java.net.*;
import java.util.UUID;
import java.util.concurrent.*;
/** Wi-Fi-bound, authenticated AP discovery and reconnect. No reconnect grants motion authority. */
final class DashboardLink extends WebSocketListener implements AutoCloseable {
  interface Listener {void command(JSONObject message);void disconnected();}
  private final String key,configuredUrl;private final Listener listener;private final Context context;
  private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor();
  private volatile OkHttpClient client;private volatile WebSocket socket;private volatile boolean authenticated,closed,connecting;
  private final java.util.concurrent.ConcurrentHashMap<String,CompletableFuture<JSONObject>> transfers=new java.util.concurrent.ConcurrentHashMap<>();
  private final ClockSync hostClock=new ClockSync();private long lastProbe;
  DashboardLink(Context c,String url,String key,Listener listener){this.context=c;this.key=key;this.configuredUrl=url;this.listener=listener;worker.scheduleWithFixedDelay(this::connect,0,2,TimeUnit.SECONDS);}
  private void connect(){
    if(closed||authenticated||connecting)return;connecting=true;
    try{
      android.net.Network network=WifiBinding.network(context);if(network==null)throw new java.io.IOException("Wi-Fi unavailable");
      String url=configuredUrl.isEmpty()?discover():configuredUrl;
      if(client!=null){client.dispatcher().executorService().shutdown();client.connectionPool().evictAll();}
      client=new OkHttpClient.Builder().socketFactory(network.getSocketFactory()).pingInterval(2,TimeUnit.SECONDS).build();
      socket=client.newWebSocket(new Request.Builder().url(url).build(),this);
    }catch(Exception e){connecting=false;listener.disconnected();}
  }
  private String discover()throws Exception {
    try(DatagramSocket udp=new DatagramSocket()){
      WifiBinding.bind(context,udp);udp.setSoTimeout(500);udp.setBroadcast(true);
      String nonce=UUID.randomUUID().toString().replace("-","");long start=SystemClock.elapsedRealtime();
      byte[] request=new JSONObject().put("type","discover").put("nonce",nonce).put("mac",Protocol.sign(key,"D|"+nonce)).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
      udp.send(new DatagramPacket(request,request.length,InetAddress.getByName("192.168.4.255"),4244));
      byte[] bytes=new byte[1024];DatagramPacket reply=new DatagramPacket(bytes,bytes.length);udp.receive(reply);
      JSONObject m=new JSONObject(new String(bytes,0,reply.getLength(),java.nio.charset.StandardCharsets.UTF_8));
      int port=m.getInt("port");long expires=m.getLong("expires_ms");
      if(SystemClock.elapsedRealtime()-start>1000||!reply.getAddress().getHostAddress().startsWith("192.168.4.")||!nonce.equals(m.getString("nonce"))||port<1024||port>65535||!Protocol.verify(key,"R|"+nonce+"|"+port+"|"+expires,m.getString("mac")))throw new SecurityException("Discovery mismatch");
      // Nonce-bound short local request lifetime prevents replay without assuming wall-clock alignment.
      return "ws://"+reply.getAddress().getHostAddress()+":"+port+"/robot";
    }
  }
  public void onMessage(WebSocket ws,String text){
    if(ws!=socket||text.length()>65536)return;
    try{
      JSONObject m=new JSONObject(text);String type=m.optString("type");
      if("challenge".equals(type))ws.send(new JSONObject().put("type","auth").put("role","robot").put("mac",Protocol.sign(key,"AUTH|robot|"+m.getString("nonce"))).toString());
      else if("authenticated".equals(type)){authenticated=true;connecting=false;probe();}
      else if(authenticated&&"clock_sample".equals(type)){hostClock.sample("dashboard",m.getLong("t1"),m.getLong("t2"),m.getLong("t3"),SystemClock.elapsedRealtime());}
      else if(authenticated&&"export_ack".equals(type)){CompletableFuture<JSONObject> waiter=transfers.remove(m.optString("request_id"));if(waiter!=null)waiter.complete(m);}
      else if(authenticated){
        if("manual".equals(m.optString("action"))){
          long now=SystemClock.elapsedRealtime();double bound=hostClock.uncertaintyMs(now);
          long deadline=Double.isFinite(bound)?hostClock.toLocal(m.optLong("deadline_ms",0))-(long)Math.ceil(bound):now;
          m.put("mapped_deadline_ns",Long.toString(Math.min(deadline,now+100)*1_000_000L));
        }
        listener.command(m);
      }
    }catch(Exception ignored){}
  }
  JSONObject transfer(JSONObject message)throws Exception{
    if(!authenticated)throw new java.io.IOException("Dashboard disconnected");String id=UUID.randomUUID().toString();CompletableFuture<JSONObject> result=new CompletableFuture<>();transfers.put(id,result);
    try{send(message.put("request_id",id));JSONObject ack=result.get(10,TimeUnit.SECONDS);if("failed".equals(ack.optString("status")))throw new java.io.IOException(ack.optString("message"));return ack;}finally{transfers.remove(id);}
  }
  private void probe(){try{lastProbe=SystemClock.elapsedRealtime();if(socket!=null)socket.send(new JSONObject().put("type","clock_probe").put("t1",lastProbe).toString());}catch(Exception ignored){}}
  void send(JSONObject message){if(authenticated&&socket!=null){if(SystemClock.elapsedRealtime()-lastProbe>2000)probe();if(!socket.send(message.toString()))failed(socket);}}
  private void failed(WebSocket ws){if(ws!=socket)return;authenticated=false;connecting=false;hostClock.invalidate();listener.disconnected();}
  public void onFailure(WebSocket ws,Throwable error,Response response){failed(ws);}
  public void onClosed(WebSocket ws,int code,String reason){failed(ws);}
  public void close(){closed=true;authenticated=false;worker.shutdownNow();if(socket!=null)socket.close(1000,"Stopped");if(client!=null){client.dispatcher().executorService().shutdown();client.connectionPool().evictAll();}}
}
