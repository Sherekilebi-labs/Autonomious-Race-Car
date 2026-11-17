package org.maxbot.app;

import android.os.SystemClock;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import org.json.*;

/** Bounded optional trace lane. Producers never wait for disk or network. */
final class SignalMonitor implements AutoCloseable {
  private final ArrayBlockingQueue<JSONObject> queue=new ArrayBlockingQueue<>(2048);
  private final ScheduledExecutorService writer=Executors.newSingleThreadScheduledExecutor(r->new Thread(r,"maxbot-signal-trace"));
  private final AtomicLong dropped=new AtomicLong();
  private final Predicate<JSONObject> send;
  private volatile String id="";private boolean recording,ending;private long ordinal,batch;
  SignalMonitor(Predicate<JSONObject> send){this.send=send;writer.scheduleWithFixedDelay(this::flush,50,50,TimeUnit.MILLISECONDS);}
  synchronized void start(String id,JSONObject metadata){
    if(!this.id.isEmpty())throw new IllegalStateException("Signal monitoring already active");
    if(!id.matches("[a-f0-9]{32}"))throw new IllegalArgumentException("Invalid monitor ID");
    this.id=id;recording=true;ending=false;ordinal=batch=0;dropped.set(0);queue.clear();
    try{record(new JSONObject().put("type","monitor_started").put("phone_ns",stamp()).put("metadata",metadata));}catch(JSONException ignored){}
  }
  synchronized void record(JSONObject event){
    if(!recording)return;
    try{JSONObject wrapped=new JSONObject().put("ordinal",++ordinal).put("event",event);if(!queue.offer(wrapped))dropped.incrementAndGet();}catch(JSONException ignored){dropped.incrementAndGet();}
  }
  synchronized void stop(){if(!recording)return;try{record(new JSONObject().put("type","monitor_stopped").put("phone_ns",stamp()));}catch(JSONException ignored){}recording=false;ending=true;}
  synchronized void stop(String expectedId){if(id.equals(expectedId))stop();}
  synchronized void abort(){id="";recording=ending=false;queue.clear();}
  private synchronized void flush(){
    if(id.isEmpty())return;
    try{
      JSONArray events=new JSONArray();int bytes=0;
      for(JSONObject e:queue){int size=e.toString().length();if(events.length()>=64||bytes+size>46000)break;events.put(e);bytes+=size;}
      boolean done=ending&&events.length()==queue.size();
      JSONObject packet=new JSONObject().put("type","signal_trace").put("monitor_id",id).put("batch",batch+1).put("dropped",dropped.get()).put("phase",done?"stopped":"recording").put("events",events);
      if(send.test(packet)){batch++;for(int i=0;i<events.length();i++)queue.poll();if(done){id="";ending=false;}}
    }catch(Exception ignored){/* Retain queued traces for retry; queue loss is explicit. */}
  }
  static String stamp(){return Long.toString(SystemClock.elapsedRealtimeNanos());}
  synchronized boolean active(){return recording;}
  public void close(){stop();flush();writer.shutdown();}
}
