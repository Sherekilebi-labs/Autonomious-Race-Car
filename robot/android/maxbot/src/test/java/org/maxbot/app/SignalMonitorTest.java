package org.maxbot.app;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public final class SignalMonitorTest {
  @Test public void congestionIsBoundedCountedAndStopDrainsBeforeEnd()throws Exception {
    List<JSONObject> packets=Collections.synchronizedList(new ArrayList<>());AtomicBoolean accept=new AtomicBoolean();
    SignalMonitor monitor=new SignalMonitor(m->{if(!accept.get())return false;packets.add(m);return true;});
    try{
      monitor.start("a".repeat(32),new JSONObject());
      for(int i=0;i<2200;i++)monitor.record(new JSONObject().put("type","sample").put("i",i));
      monitor.stop();assertFalse(monitor.active());
      accept.set(true);Method flush=SignalMonitor.class.getDeclaredMethod("flush");flush.setAccessible(true);
      for(int i=0;i<40;i++)flush.invoke(monitor);
      assertFalse(packets.isEmpty());JSONObject last=packets.get(packets.size()-1);
      assertEquals("stopped",last.getString("phase"));assertTrue(last.getLong("dropped")>0);
      long previous=0;for(JSONObject packet:packets)for(int i=0;i<packet.getJSONArray("events").length();i++){long ordinal=packet.getJSONArray("events").getJSONObject(i).getLong("ordinal");assertTrue(ordinal>previous);previous=ordinal;}
      monitor.start("b".repeat(32),new JSONObject()); // New sessions can start only after draining.
    }finally{monitor.close();}
  }
}
