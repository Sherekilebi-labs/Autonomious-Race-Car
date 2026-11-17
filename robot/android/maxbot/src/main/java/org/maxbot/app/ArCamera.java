package org.maxbot.app;

import android.app.Activity;
import android.media.Image;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.SystemClock;
import com.google.ar.core.Anchor;
import com.google.ar.core.ArCoreApk;
import com.google.ar.core.Camera;
import com.google.ar.core.CameraConfig;
import com.google.ar.core.CameraConfigFilter;
import com.google.ar.core.Config;
import com.google.ar.core.Coordinates2d;
import com.google.ar.core.Frame;
import com.google.ar.core.Pose;
import com.google.ar.core.Session;
import com.google.ar.core.TrackingState;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.EnumSet;
import org.maxbot.core.Calibration;
import org.maxbot.core.Geometry;
import org.maxbot.core.LatestFramePool;

/** ARCore is the only camera owner; physical pose and image metadata come from the same frame. */
final class ArCamera implements GLSurfaceView.Renderer, AutoCloseable {
  static {System.loadLibrary("maxbot_image");}
  volatile String streamId="";
  volatile org.json.JSONObject capabilities=new org.json.JSONObject();
  private static native void packPlanes(byte[] target,ByteBuffer y,ByteBuffer u,ByteBuffer v,int width,int height,int yr,int yp,int ur,int up,int vr,int vp);
  interface Listener {
    void localization(Geometry.Pose pose, boolean tracking, String reason);

    void image(Image image, long timestampNs);

    boolean needsCpuImage();

    void frame(LatestFramePool.Slot frame);
  }

  static final class Metadata {
    final double fx, fy, cx, cy;
    final double[] origin, right, down, forward;
    final Geometry.Pose vehicle;

    Metadata(Camera camera, Pose relative, Geometry.Pose vehicle) {
      float[] f = camera.getImageIntrinsics().getFocalLength(),
          p = camera.getImageIntrinsics().getPrincipalPoint();
      fx = f[0];
      fy = f[1];
      cx = p[0];
      cy = p[1];
      this.vehicle = vehicle;
      float[] o = relative.getTranslation(),
          r = relative.getXAxis(),
          u = relative.getYAxis(),
          z = relative.getZAxis();
      origin = new double[] {o[0], o[1], o[2]};
      right = new double[] {r[0], r[1], r[2]};
      down = new double[] {-u[0], -u[1], -u[2]};
      forward = new double[] {-z[0], -z[1], -z[2]};
    }
  }

  private final Activity activity;
  private final GLSurfaceView view;
  private final Calibration c;
  private final LatestFramePool pool;
  private final Listener listener;
  private Session session;
  private Anchor anchor;
  private int texture, program, width = 640, height = 480;
  private volatile boolean ready;
  private boolean closed;
  private long lastFrame, lastPoseTime;
  private boolean realtimeSensorClock;
  private long lastCpuTimestamp;
  private String streamIdentity="";
  private final Object capabilitiesLock=new Object();
  private double lastX, lastY, filteredSpeed;
  private final FloatBuffer vertices = floats(new float[] {-1, -1, 1, -1, -1, 1, 1, 1});
  private final FloatBuffer uv = floats(new float[8]);

  ArCamera(
      Activity activity,
      GLSurfaceView view,
      Calibration c,
      LatestFramePool pool,
      Listener listener) {
    this.activity = activity;
    this.view = view;
    this.c = c;
    this.pool = pool;
    this.listener = listener;
    view.setEGLContextClientVersion(2);
    view.setPreserveEGLContextOnPause(true);
    view.setRenderer(this);
  }

  boolean resume() {
    if (closed) return false;
    try {
      if (session == null) {
        if (ArCoreApk.getInstance().requestInstall(activity, true)
            == ArCoreApk.InstallStatus.INSTALL_REQUESTED) return false;
        session = new Session(activity);
        CameraConfig selected = null;
        org.json.JSONArray configs=new org.json.JSONArray();
        for(CameraConfig supported:session.getSupportedCameraConfigs(new CameraConfigFilter(session).setFacingDirection(CameraConfig.FacingDirection.BACK))){
          org.json.JSONObject item=new org.json.JSONObject();item.put("camera_id",supported.getCameraId()).put("width",supported.getImageSize().getWidth()).put("height",supported.getImageSize().getHeight()).put("fps",supported.getFpsRange().toString());configs.put(item);
        }
        synchronized(capabilitiesLock){capabilities.put("configs",configs).put("selection","rear normal camera; mounted lens verification pending").put("capture_clock_validated",false);}
        CameraConfigFilter filter =
            new CameraConfigFilter(session)
                .setFacingDirection(CameraConfig.FacingDirection.BACK)
                .setTargetFps(EnumSet.of(CameraConfig.TargetFps.TARGET_FPS_30));
        for (CameraConfig config : session.getSupportedCameraConfigs(filter))
          if (config.getImageSize().getWidth() == 640 && config.getImageSize().getHeight() == 480) {
            selected = config;
            break;
          }
        if (selected == null)
          throw new IllegalStateException("ARCore 640x480/30 camera configuration unavailable");
        session.setCameraConfig(selected);
        synchronized(capabilitiesLock){capabilities.put("selected_camera_id",selected.getCameraId());}
        android.hardware.camera2.CameraManager manager=(android.hardware.camera2.CameraManager)activity.getSystemService(android.content.Context.CAMERA_SERVICE);
        android.hardware.camera2.CameraCharacteristics characteristics=manager.getCameraCharacteristics(selected.getCameraId());
        realtimeSensorClock=java.util.Objects.equals(characteristics.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE),android.hardware.camera2.CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME);
        synchronized(capabilitiesLock){capabilities.put("sensor_timestamp_source",characteristics.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE));}
        Config config =
            new Config(session)
                .setDepthMode(Config.DepthMode.DISABLED)
                .setPlaneFindingMode(Config.PlaneFindingMode.DISABLED)
                .setLightEstimationMode(Config.LightEstimationMode.DISABLED)
                .setFocusMode(Config.FocusMode.AUTO)
                .setUpdateMode(Config.UpdateMode.BLOCKING);
        session.configure(config);
      }
      session.resume();
      ready = true;
      view.onResume();
      return true;
    } catch (Exception e) {
      listener.localization(null, false, "ARCore: " + e.getMessage());
      return false;
    }
  }

  void recalibrate(){view.queueEvent(()->{if(anchor!=null)anchor.detach();anchor=null;lastPoseTime=lastFrame=0;listener.localization(null,false,"Camera calibration reset; scout again");});}

  void pause() {
    ready = false;
    view.onPause();
    if (session != null) session.pause();
    listener.localization(null, false, "Camera paused");
  }

  @Override
  public void onSurfaceCreated(
      javax.microedition.khronos.opengles.GL10 gl,
      javax.microedition.khronos.egl.EGLConfig config) {
    int[] textures = new int[1];
    GLES20.glGenTextures(1, textures, 0);
    texture = textures[0];
    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture);
    GLES20.glTexParameteri(
        GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
    GLES20.glTexParameteri(
        GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
    program = GLES20.glCreateProgram();
    GLES20.glAttachShader(
        program,
        shader(
            GLES20.GL_VERTEX_SHADER,
            "attribute vec2 p;attribute vec2 t;varying vec2 uv;void main(){gl_Position=vec4(p,0.,1.);uv=t;}"));
    GLES20.glAttachShader(
        program,
        shader(
            GLES20.GL_FRAGMENT_SHADER,
            "#extension GL_OES_EGL_image_external : require\nprecision mediump float;uniform samplerExternalOES image;varying vec2 uv;void main(){gl_FragColor=texture2D(image,uv);}"));
    GLES20.glLinkProgram(program);
  }

  @Override
  public void onSurfaceChanged(javax.microedition.khronos.opengles.GL10 gl, int width, int height) {
    this.width = width;
    this.height = height;
    GLES20.glViewport(0, 0, width, height);
  }

  @Override
  public void onDrawFrame(javax.microedition.khronos.opengles.GL10 gl) {
    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
    if (!ready || session == null || texture == 0) return;
    try {
      session.setCameraTextureName(texture);
      session.setDisplayGeometry(
          activity.getWindowManager().getDefaultDisplay().getRotation(), width, height);
      Frame frame = session.update();
      drawPreview(frame);
      long cameraTime = frame.getTimestamp();
      if (cameraTime == 0 || cameraTime == lastFrame) return;
      if(lastFrame!=0&&cameraTime<lastFrame){lastFrame=cameraTime;listener.localization(null,false,"Camera timestamp reset; scout again");return;}
      lastFrame = cameraTime;
      long now = SystemClock.elapsedRealtimeNanos();
      // Callback offset is not an exposure-clock calibration. Use image-available time and label it.
      long captureTime = now;
      Camera camera = frame.getCamera();
      com.google.ar.core.CameraIntrinsics intrinsics=camera.getImageIntrinsics();
      float[] focal=intrinsics.getFocalLength(), principal=intrinsics.getPrincipalPoint();
      int[] dimensions=intrinsics.getImageDimensions();
      String stream=capabilities.optString("selected_camera_id")+"|"+java.util.Arrays.toString(dimensions)+"|"+java.util.Arrays.toString(focal)+"|"+java.util.Arrays.toString(principal)+"|cpu_sensor_unrotated";
      synchronized(capabilitiesLock){
        capabilities.put("camera_timestamp_ns",Long.toString(cameraTime)).put("image_available_ns",Long.toString(now));
        // Intrinsics can change with focus; only redo their report/hash when they actually change.
        if(!stream.equals(streamIdentity)){
          byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(stream.getBytes(java.nio.charset.StandardCharsets.UTF_8));
          char[] hex=new char[digest.length*2];String alphabet="0123456789abcdef";
          for(int i=0;i<digest.length;i++){hex[2*i]=alphabet.charAt((digest[i]>>>4)&15);hex[2*i+1]=alphabet.charAt(digest[i]&15);}
          streamId=new String(hex);streamIdentity=stream;
          capabilities.put("stream_id",streamId).put("intrinsics_focal",new org.json.JSONArray(focal))
              .put("intrinsics_principal",new org.json.JSONArray(principal)).put("image_dimensions",new org.json.JSONArray(dimensions));
        }
      }
      if(!streamId.equals(c.camera.streamId)){listener.localization(null,false,"Camera stream calibration binding pending; see capability report");publishImage(frame,camera,null,null,captureTime);return;}
      if (camera.getTrackingState() != TrackingState.TRACKING) {
        listener.localization(null, false, "Tracking: " + camera.getTrackingFailureReason());
        publishImage(frame, camera, null, null, captureTime);
        return;
      }
      Pose physical = camera.getPose();
      if (anchor == null) {
        if (c.camera.heightM == null
            || c.camera.forwardM == null
            || c.camera.leftM == null
            || c.camera.yawLeftDeg == null) {
          listener.localization(null, false, "Import measured camera geometry");
          publishImage(frame, camera, null, null, captureTime);
          return;
        }
        float[] t = physical.getTranslation(), z = physical.getZAxis();
        double yaw = Math.atan2(z[2], -z[0]) - Math.toRadians(c.camera.yawLeftDeg);
        double rx = t[0] - c.camera.forwardM * Math.cos(yaw) + c.camera.leftM * Math.sin(yaw);
        double ry = -t[2] - c.camera.forwardM * Math.sin(yaw) - c.camera.leftM * Math.cos(yaw);
        anchor =
            session.createAnchor(
                new Pose(
                    new float[] {(float) rx, (float) (t[1] - c.camera.heightM), (float) -ry},
                    new float[] {0,(float)Math.sin(yaw/2),0,(float)Math.cos(yaw/2)}));
      }
      if (anchor.getTrackingState() != TrackingState.TRACKING) {
        listener.localization(null, false, "Start anchor tracking unavailable");
        publishImage(frame, camera, null, null, captureTime);
        return;
      }
      Pose relative = anchor.getPose().inverse().compose(physical);
      float[] t = relative.getTranslation(), z = relative.getZAxis();
      double yaw = Math.atan2(z[2], -z[0]) - Math.toRadians(c.camera.yawLeftDeg);
      double x = t[0] - c.camera.forwardM * Math.cos(yaw) + c.camera.leftM * Math.sin(yaw);
      double y = -t[2] - c.camera.forwardM * Math.sin(yaw) - c.camera.leftM * Math.cos(yaw);
      double dt = (captureTime - lastPoseTime) * 1e-9;
      if (lastPoseTime != 0 && dt > 0 && dt < .2) {
        double velocity = ((x - lastX) * Math.cos(yaw) + (y - lastY) * Math.sin(yaw)) / dt;
        if (Math.abs(velocity) > 15) {
          listener.localization(null, false, "Implausible visual speed");
          return;
        }
        filteredSpeed += Math.min(1, dt / .08) * (velocity - filteredSpeed);
      } else filteredSpeed = 0;
      lastX = x;
      lastY = y;
      lastPoseTime = captureTime;
      Geometry.Pose vehicle = new Geometry.Pose(x, y, yaw, filteredSpeed, captureTime);
      listener.localization(vehicle, true, "");
      publishImage(frame, camera, relative, vehicle, captureTime);
    } catch (Exception e) {
      listener.localization(null, false, "Camera pipeline: " + e.getClass().getSimpleName());
    }
  }

  private void publishImage(
      Frame frame, Camera camera, Pose relative, Geometry.Pose vehicle, long captureTime) {
    LatestFramePool.Slot slot = null;
    boolean published = false;
    try (Image image = frame.acquireCameraImage()) {
      long acquired=SystemClock.elapsedRealtimeNanos();
      if (image.getWidth() != 640 || image.getHeight() != 480) return;
      listener.image(image, captureTime);
      if (!listener.needsCpuImage()) return;
      if(image.getTimestamp()==lastCpuTimestamp)return;
      lastCpuTimestamp=image.getTimestamp();
      slot = pool.acquireForCapture();
      if (slot == null) return;
      slot.sensorTimestampNs=image.getTimestamp();slot.cameraTimestampNs=frame.getTimestamp();slot.imageAcquiredNs=acquired;
      // ARCore's general frame timestamp has an unspecified timebase. Correlate the
      // Android CPU image with the explicit Android Camera timestamp instead.
      slot.androidCameraTimestampNs=frame.getAndroidCameraTimestamp();
      slot.sensorClockComparable=realtimeSensorClock&&image.getTimestamp()==slot.androidCameraTimestampNs&&acquired>=image.getTimestamp()&&acquired-image.getTimestamp()<1_000_000_000L;
      slot.packStartNs=SystemClock.elapsedRealtimeNanos();
      copy(image, slot.yuv);
      slot.width = image.getWidth();
      slot.height = image.getHeight();
      slot.timestampNs = captureTime;
      slot.metadata =
          relative == null || vehicle == null ? null : new Metadata(camera, relative, vehicle);
      pool.retain(slot);
      slot.publishedNs=SystemClock.elapsedRealtimeNanos();
      pool.publish(slot);
      published = true;
      try{listener.frame(slot);}finally{pool.release(slot);}
    } catch (com.google.ar.core.exceptions.NotYetAvailableException ignored) {
    } finally {
      if (!published && slot != null) pool.release(slot);
    }
  }

  private void drawPreview(Frame frame) {
    vertices.rewind();
    uv.rewind();
    frame.transformCoordinates2d(
        Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
        vertices,
        Coordinates2d.TEXTURE_NORMALIZED,
        uv);
    GLES20.glUseProgram(program);
    int p = GLES20.glGetAttribLocation(program, "p"), t = GLES20.glGetAttribLocation(program, "t");
    vertices.rewind();
    uv.rewind();
    GLES20.glEnableVertexAttribArray(p);
    GLES20.glEnableVertexAttribArray(t);
    GLES20.glVertexAttribPointer(p, 2, GLES20.GL_FLOAT, false, 0, vertices);
    GLES20.glVertexAttribPointer(t, 2, GLES20.GL_FLOAT, false, 0, uv);
    GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture);
    GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "image"), 0);
    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    GLES20.glDisableVertexAttribArray(p);
    GLES20.glDisableVertexAttribArray(t);
  }

  private static void copy(Image image, byte[] target) {
    Image.Plane[] p=image.getPlanes();
    packPlanes(target,p[0].getBuffer().slice(),p[1].getBuffer().slice(),p[2].getBuffer().slice(),image.getWidth(),image.getHeight(),p[0].getRowStride(),p[0].getPixelStride(),p[1].getRowStride(),p[1].getPixelStride(),p[2].getRowStride(),p[2].getPixelStride());
  }

  org.json.JSONObject capabilitySnapshot() throws org.json.JSONException {
    synchronized(capabilitiesLock){return new org.json.JSONObject(capabilities.toString());}
  }

  private static FloatBuffer floats(float[] data) {
    FloatBuffer b =
        ByteBuffer.allocateDirect(data.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    b.put(data).rewind();
    return b;
  }

  private static int shader(int type, String text) {
    int s = GLES20.glCreateShader(type);
    GLES20.glShaderSource(s, text);
    GLES20.glCompileShader(s);
    return s;
  }

  @Override
  public void close() {
    if (closed) return;
    pause();
    closed = true;
    if (anchor != null) anchor.detach();
    if (session != null) session.close();
  }
}
