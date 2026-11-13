package org.maxbot.app;

import android.content.ContextWrapper;
import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class RunRecorderTest {
  @Test public void slowRecordingCannotBlockControlAndQueuedIdentityIsPreserved()throws Exception{
    Path root=Files.createTempDirectory("maxbot-recorder-test-");
    RunRecorder recorder=new RunRecorder(new ContextWrapper(null){@Override public File getExternalFilesDir(String type){return root.toFile();}});
    CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
    ExecutorService producer=Executors.newSingleThreadExecutor();
    try {
      recorder.snapshot("manifest.json",new JSONObject().put("revision",1));
      JSONObject slow=new JSONObject(){@Override public String toString(){entered.countDown();try{release.await(3,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}return "{\"type\":\"control\",\"pwm\":100}";}};
      Future<?> queued=producer.submit(()->recorder.event(slow));queued.get(250,TimeUnit.MILLISECONDS);
      assertTrue(entered.await(1,TimeUnit.SECONDS));
      recorder.snapshot("manifest.json",new JSONObject().put("revision",2));
      Future<?> queuedSecond=producer.submit(()->{recorder.actuator(new JSONObject().put("type","telemetry").put("pwm",90),1234);return null;});queuedSecond.get(250,TimeUnit.MILLISECONDS);
      release.countDown();recorder.close();
      List<String> lines=Files.readAllLines(recorder.directory.toPath().resolve("events.jsonl"));assertEquals(2,lines.size());
      JSONObject first=new JSONObject(lines.get(0)),second=new JSONObject(lines.get(1));
      assertEquals(1,first.getJSONObject("identity").getInt("revision"));assertEquals(2,second.getJSONObject("identity").getInt("revision"));
      assertEquals("actuator_telemetry",second.getString("type"));assertEquals("1234",second.getString("phone_received_ns"));assertEquals(90,second.getInt("pwm"));
    }finally{release.countDown();producer.shutdownNow();recorder.close();try(var paths=Files.walk(root)){paths.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.delete(p);}catch(Exception ignored){}});}}
  }
}
