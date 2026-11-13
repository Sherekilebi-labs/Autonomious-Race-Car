package org.maxbot.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Process;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.json.JSONObject;
import org.maxbot.core.LatestFramePool;

/** Optional recording never retains a control-camera slot or blocks the control thread. */
final class RunRecorder implements AutoCloseable {
  final File directory;
  private final ArrayBlockingQueue<String> events = new ArrayBlockingQueue<>(256);
  private final byte[] selected = new byte[640 * 480 * 3 / 2];
  private final AtomicBoolean selectedBusy = new AtomicBoolean();
  private final int[] rgb = new int[640 * 480];
  private final Thread writer;
  private volatile JSONObject identity=new JSONObject();
  private volatile HardwareRecorder video;
  private volatile String recordingStatus = "Events recording; optional video disabled";
  private boolean videoTried;
  private int videoIndex;
  private volatile boolean running = true, collect;
  private volatile long selectedTimestamp;
  private volatile boolean selectedReady;
  private long lastSelected;
  private final java.util.concurrent.atomic.AtomicInteger dropped=new java.util.concurrent.atomic.AtomicInteger();

  RunRecorder(Context context) throws IOException {
    directory =
        new File(
            context.getExternalFilesDir(null),
            "runs/" + new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT).format(new Date()));
    if (!new File(directory, "images").mkdirs())
      throw new IOException("Cannot create run directory");
    writer = new Thread(this::write, "maxbot-recording");
    writer.start();
  }

  void collection(boolean enabled) {
    collect = enabled;
  }

  int dropped() {
    return dropped.get();
  }

  String status() {
    return recordingStatus;
  }

  void event(JSONObject e) {
    if(!running)return;
    try{JSONObject record=new JSONObject(e.toString());if(!record.has("_snapshot_file"))record.put("identity",identity);if(!events.offer(record.toString()))dropped.incrementAndGet();}catch(org.json.JSONException invalid){dropped.incrementAndGet();}
  }

  void snapshot(String file,JSONObject data){
    if(file.equals("manifest.json"))try{identity=new JSONObject(data.toString());}catch(org.json.JSONException ignored){}
    if(!java.util.List.of("manifest.json","course.json","reference.json","vehicle-model.json","review.json","summary.json").contains(file))throw new IllegalArgumentException("Unknown recording snapshot");
    try{event(new JSONObject().put("_snapshot_file",file).put("data",data));}catch(org.json.JSONException ignored){}
  }

  void snapshotRaw(String file,String raw){
    if(!java.util.List.of("reference.json","vehicle-model.json","geometry.json").contains(file))throw new IllegalArgumentException("Unknown raw artifact");
    try{event(new JSONObject().put("_snapshot_file",file).put("raw",raw));}catch(org.json.JSONException ignored){}
  }

  void frame(LatestFramePool.Slot f) {
    if (!running) return;
    if (collect && video != null) video.frame(f);
    if (!collect
        || f.timestampNs - lastSelected < 500_000_000L
        || !selectedBusy.compareAndSet(false, true)) return;
    System.arraycopy(f.yuv, 0, selected, 0, selected.length);
    selectedTimestamp = f.timestampNs;
    lastSelected = f.timestampNs;
    selectedReady = true;
  }

  private void write() {
    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
    Bitmap bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888);
    java.util.Map<String,BufferedWriter> streams=new java.util.HashMap<>();
    try (BufferedWriter out =
        new BufferedWriter(new FileWriter(new File(directory, "events.jsonl")))) {
      while (running || !events.isEmpty() || selectedReady) {
        if (collect && video == null && !videoTried && running) {
          videoTried = true;
          try {
            video = new HardwareRecorder(new File(directory, "camera-" + (++videoIndex) + ".mp4"));
            recordingStatus = "Events + selected frames + hardware AVC";
          } catch (Exception e) {
            recordingStatus =
                "Hardware video unavailable; events and selected frames remain enabled";
          }
        } else if (!collect && video != null) {
          HardwareRecorder old = video;
          video = null;
          old.close();
          videoTried = false;
          recordingStatus = "Events recording; optional video disabled";
        }
        String e = events.poll(50, TimeUnit.MILLISECONDS);
        if (e != null) {
          JSONObject record=new JSONObject(e);
          if(record.has("_snapshot_file")){
            try(Writer snapshot=new FileWriter(new File(directory,record.getString("_snapshot_file")))){snapshot.write(record.has("raw")?record.getString("raw"):record.getJSONObject("data").toString());snapshot.write("\n");}
          }else{
            out.write(e);out.newLine();
            String type=record.optString("type");String stream=type.equals("imu")?"imu":type.equals("detections")?"observations":type.equals("command_sent")?"commands":java.util.List.of("applied","local_applied","actuator_telemetry").contains(type)?"actuator":java.util.List.of("state","control").contains(type)?"state":"timing";
            BufferedWriter category=streams.get(stream);if(category==null){category=new BufferedWriter(new FileWriter(new File(directory,stream+".jsonl")));streams.put(stream,category);}category.write(e);category.newLine();
          }
        }
        if (selectedReady) {
          for (int y = 0; y < 480; y++)
            for (int x = 0; x < 640; x++) {
              int yy = selected[y * 640 + x] & 255,
                  u = (selected[640 * 480 + (y / 2) * 320 + x / 2] & 255) - 128,
                  v = (selected[640 * 480 * 5 / 4 + (y / 2) * 320 + x / 2] & 255) - 128;
              int r = clamp((int) (yy + 1.402 * v)),
                  g = clamp((int) (yy - .344 * u - .714 * v)),
                  b = clamp((int) (yy + 1.772 * u));
              rgb[y * 640 + x] = 0xff000000 | (r << 16) | (g << 8) | b;
            }
          bitmap.setPixels(rgb, 0, 640, 0, 0, 640, 480);
          try (FileOutputStream image =
              new FileOutputStream(new File(directory, "images/" + selectedTimestamp + ".png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, image);
          }
          selectedReady = false;
          selectedBusy.set(false);
        }
        if (e == null) out.flush();
      }
      out.flush();
    } catch (Exception ignored) {
      recordingStatus = "Recording failed; check available storage and export the remaining files";
      running = false;
      selectedBusy.set(false);
    } finally {
      for(BufferedWriter stream:streams.values())try{stream.close();}catch(IOException ignored){}
      HardwareRecorder old = video;
      video = null;
      if (old != null) old.close();
      bitmap.recycle();
    }
  }

  private static int clamp(int n) {
    return Math.max(0, Math.min(255, n));
  }

  void export(OutputStream output) throws IOException {
    close();
    try (ZipOutputStream zip = new ZipOutputStream(output)) {
      zip(directory, directory, zip);
    }
  }

  private static void zip(File root, File file, ZipOutputStream output) throws IOException {
    if (file.isDirectory()) {
      File[] children = file.listFiles();
      if (children != null) for (File child : children) zip(root, child, output);
      return;
    }
    output.putNextEntry(new ZipEntry(root.toPath().relativize(file.toPath()).toString()));
    try (InputStream input = new FileInputStream(file)) {
      byte[] bytes = new byte[8192];
      int n;
      while ((n = input.read(bytes)) >= 0) output.write(bytes, 0, n);
    }
    output.closeEntry();
  }

  @Override
  public void close() {
    running = false;
    try {
      writer.join(5000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    if (writer.isAlive())
      throw new IllegalStateException("Recorder is still draining; retry export after it finishes");
  }
}
