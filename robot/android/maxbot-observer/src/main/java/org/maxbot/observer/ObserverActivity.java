package org.maxbot.observer;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.*;
import android.os.*;
import android.util.Range;
import android.util.Size;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.net.URI;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import okhttp3.*;
import org.json.*;
import org.maxbot.core.SessionMessages;

/** Foreground observing camera. Original files stay on this phone until acknowledged uploads finish. */
public final class ObserverActivity extends Activity implements TextureView.SurfaceTextureListener {
  private final HandlerThread cameraThread=new HandlerThread("observer-camera");
  private Handler cameraHandler;
  private final ExecutorService commands=Executors.newSingleThreadExecutor();
  private final ScheduledExecutorService statusWorker=Executors.newSingleThreadScheduledExecutor();
  private final ConcurrentHashMap<String,CompletableFuture<JSONObject>> uploads=new ConcurrentHashMap<>();
  private OkHttpClient client;private volatile WebSocket socket;private volatile boolean paired,recording,closing;
  private CameraDevice camera;private CameraCaptureSession session;private MediaRecorder recorder;private Surface previewSurface,recordSurface;
  private PreviewTexture preview;private LinearLayout connectionForm;private TextView status;private EditText address,key;
  private CameraCharacteristics characteristics;private String cameraId,secret,signingSession="";private long incomingSequence=-1,outgoingSequence;private JSONObject capabilities=new JSONObject();
  private volatile int previewBufferWidth=1280,previewBufferHeight=720,previewViewWidth,previewViewHeight,displayRotationDegrees;private boolean previewCopyPending;private volatile String previewCopyStatus="waiting";
  private volatile long lastFrameNs;private long lastPreviewNs;private volatile int selectedFps=30;private Size selectedSize=new Size(1280,720);
  private File runDirectory;private BufferedWriter frameLog;private String experimentId="";private long frames,recordingStartNs,lastClockProbeNs;
  private ScheduledFuture<?> durationStop;
  @Override public void onCreate(Bundle state){
    super.onCreate(state);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);layout.setPadding(20,12,20,12);
    LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);
    TextView title=new TextView(this);title.setText("MaxBot Observer");title.setTextSize(20);header.addView(title,new LinearLayout.LayoutParams(0,-2,1));
    Button settings=new Button(this);settings.setText("Connection settings");settings.setOnClickListener(v->{if(!recording)connectionForm.setVisibility(connectionForm.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE);});header.addView(settings);layout.addView(header);
    connectionForm=new LinearLayout(this);connectionForm.setOrientation(LinearLayout.VERTICAL);layout.addView(connectionForm);
    address=new EditText(this);address.setSingleLine(true);address.setHint("ws://Mac-LAN-address:8081/observer");address.setText(getPreferences(MODE_PRIVATE).getString("address",""));connectionForm.addView(address);
    key=new EditText(this);key.setSingleLine(true);key.setInputType(129);key.setHint("Private MaxBot pairing key");key.setText(getPreferences(MODE_PRIVATE).getString("key",""));connectionForm.addView(key);
    Button connect=new Button(this);connect.setText("Connect observer");connect.setOnClickListener(v->connect());connectionForm.addView(connect);
    status=new TextView(this);status.setMaxLines(2);status.setText("Camera permission and Mac connection required.");layout.addView(status);
    FrameLayout viewport=new FrameLayout(this);viewport.setBackgroundColor(android.graphics.Color.BLACK);
    preview=new PreviewTexture(this);preview.setSurfaceTextureListener(this);
    preview.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{previewViewWidth=r-l;previewViewHeight=b-t;displayRotationDegrees=preview.displayDegrees();});
    viewport.addView(preview,new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER));layout.addView(viewport,new LinearLayout.LayoutParams(-1,0,1));setContentView(layout);
    cameraThread.start();cameraHandler=new Handler(cameraThread.getLooper());
    client=new OkHttpClient.Builder().pingInterval(2,TimeUnit.SECONDS).build();
    statusWorker.scheduleWithFixedDelay(this::publish,0,500,TimeUnit.MILLISECONDS);
    if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.CAMERA},1);
  }
  private void connect(){
    try{
      URI uri=new URI(address.getText().toString().trim());if(!List.of("ws","wss").contains(uri.getScheme())||uri.getHost()==null||!"/observer".equals(uri.getPath())||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null)throw new IllegalArgumentException("Use ws://MAC_LAN_ADDRESS:8081/observer");
      secret=key.getText().toString();if(secret.length()<32)throw new IllegalArgumentException("Use the existing MaxBot pairing key");
      if(recording)throw new IllegalStateException("Stop recording before reconnecting");
      if(socket!=null)socket.close(1000,"Reconnect");paired=false;
      getPreferences(MODE_PRIVATE).edit().putString("address",uri.toString()).putString("key",secret).apply();
      socket=client.newWebSocket(new Request.Builder().url(uri.toString()).build(),new WebSocketListener(){
        public void onMessage(WebSocket ws,String text){if(ws!=socket||text.length()>65536)return;try{
          JSONObject m=new JSONObject(text);
          if(paired){String payload=m.optString("payload");long sequence=m.optLong("sequence",-1);if(!"signed_message".equals(m.optString("type"))||!signingSession.equals(m.optString("session_id"))||sequence<=incomingSequence||!SessionMessages.verify(secret,"S",signingSession,sequence,payload,m.optString("mac")))return;incomingSequence=sequence;m=new JSONObject(payload);}
          String type=m.optString("type");
          if(type.equals("challenge"))ws.send(new JSONObject().put("type","auth").put("role","observer").put("control_signing",1).put("mac",hmac("AUTH|observer|"+m.getString("nonce")+"|signed-v1")).toString());
          else if(type.equals("authenticated")){if(m.optInt("control_signing")!=1)throw new SecurityException("Updated signed dashboard required");signingSession=m.getString("session_id");incomingSequence=-1;outgoingSequence=0;paired=true;runOnUiThread(()->{connectionForm.setVisibility(View.GONE);((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(key.getWindowToken(),0);});show("Paired. Camera stays local; recordings upload after each trial.");}
          else if(paired&&type.equals("observer_command"))dispatchCommand(m);
          else if(paired&&type.equals("observer_upload_ack")){CompletableFuture<JSONObject> p=uploads.remove(m.optString("request_id"));if(p!=null)p.complete(m);}
          else if(paired&&type.equals("observer_clock")){long t4=SystemClock.elapsedRealtimeNanos();if(m.optString("t1").equals(Long.toString(lastClockProbeNs)))dispatchClock(m,t4);}
        }catch(Exception e){show(e.getMessage());}}
        public void onFailure(WebSocket ws,Throwable error,Response response){lost(ws,error.getMessage());}
        public void onClosed(WebSocket ws,int code,String reason){lost(ws,reason);}
        public void onClosing(WebSocket ws,int code,String reason){ws.close(code,reason);lost(ws,reason);}
      });
    }catch(Exception e){show(e.getMessage());}
  }
  private void lost(WebSocket ws,String reason){if(ws!=socket)return;paired=false;show("Mac disconnected: "+reason);for(CompletableFuture<JSONObject> p:uploads.values())p.completeExceptionally(new IOException("Observer disconnected"));uploads.clear();cameraHandler.post(()->{try{stopRecording();}catch(Exception ignored){}});}
  private String hmac(String value)throws Exception{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8),"HmacSHA256"));return hex(mac.doFinal(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
  private static String hex(byte[] bytes){StringBuilder b=new StringBuilder();for(byte value:bytes)b.append(String.format(Locale.ROOT,"%02x",value));return b.toString();}
  private void show(String text){runOnUiThread(()->status.setText(text));}
  private synchronized void send(JSONObject m){WebSocket ws=socket;if(paired&&ws!=null&&ws.queueSize()<128*1024)try{String payload=m.toString();long sequence=++outgoingSequence;ws.send(new JSONObject().put("type","signed_message").put("session_id",signingSession).put("sequence",sequence).put("payload",payload).put("mac",SessionMessages.sign(secret,"C",signingSession,sequence,payload)).toString());}catch(Exception ignored){}}
  private void dispatchCommand(JSONObject m){commands.execute(()->command(m));}
  private void dispatchClock(JSONObject m,long t4){cameraHandler.post(()->log(clockEvent(m,t4)));}
  private void publish(){if(closing)return;try{
    long now=SystemClock.elapsedRealtimeNanos();send(new JSONObject().put("type","observer_status").put("device",Build.MODEL).put("camera_ready",camera!=null&&session!=null).put("recording",recording).put("capture_age_ms",lastFrameNs==0?JSONObject.NULL:(now-lastFrameNs)/1_000_000.).put("fps",selectedFps).put("app_version","0.7.2-rotation-fix").put("preview_buffer_width",previewBufferWidth).put("preview_buffer_height",previewBufferHeight).put("preview_view_width",previewViewWidth).put("preview_view_height",previewViewHeight).put("preview_fit","fit_center_sensor_display_rotation").put("display_rotation_deg",displayRotationDegrees).put("snapshot_source","window_pixel_copy").put("snapshot_status",previewCopyStatus).put("capabilities",capabilities));
    if(paired&&now-lastClockProbeNs>1_000_000_000L){lastClockProbeNs=now;send(new JSONObject().put("type","observer_clock_probe").put("t1",Long.toString(now)));}
    if(paired&&now-lastPreviewNs>1_000_000_000L){lastPreviewNs=now;runOnUiThread(this::copyRenderedPreview);}

  }catch(Exception ignored){}}
  private void copyRenderedPreview(){
    if(!paired||closing||previewCopyPending||!preview.isAvailable())return;
    int[] size=PreviewGeometry.fit(preview.getWidth(),preview.getHeight(),640,480);if(size[0]<2||size[1]<2)return;
    int[] location=new int[2];preview.getLocationInWindow(location);
    android.graphics.Rect area=new android.graphics.Rect(location[0],location[1],location[0]+preview.getWidth(),location[1]+preview.getHeight());
    Bitmap image=Bitmap.createBitmap(size[0],size[1],Bitmap.Config.ARGB_8888);previewCopyPending=true;
    try{
      PixelCopy.request(getWindow(),area,image,result->{
        previewCopyPending=false;
        try{
          if(result!=PixelCopy.SUCCESS){previewCopyStatus="pixel_copy_error_"+result;return;}
          previewCopyStatus="ok";displayRotationDegrees=preview.displayDegrees();
          ByteArrayOutputStream bytes=new ByteArrayOutputStream();image.compress(Bitmap.CompressFormat.JPEG,65,bytes);
          if(bytes.size()>=40000){bytes.reset();image.compress(Bitmap.CompressFormat.JPEG,45,bytes);}
          if(bytes.size()<40000)send(new JSONObject().put("type","observer_frame").put("phone_ns",Long.toString(SystemClock.elapsedRealtimeNanos()))
            .put("timestamp_definition","rendered preview window copy; not sensor exposure").put("snapshot_source","window_pixel_copy")
            .put("width",size[0]).put("height",size[1]).put("source_buffer_width",previewBufferWidth).put("source_buffer_height",previewBufferHeight)
            .put("display_rotation_deg",displayRotationDegrees).put("sensor_orientation_deg",capabilities.optInt("sensor_orientation_deg"))
            .put("preview_fit","fit_center_sensor_display_rotation").put("jpeg",android.util.Base64.encodeToString(bytes.toByteArray(),android.util.Base64.NO_WRAP)));
        }catch(Exception e){previewCopyStatus="snapshot_error";}finally{image.recycle();}
      },new Handler(Looper.getMainLooper()));
    }catch(Exception e){previewCopyPending=false;previewCopyStatus="pixel_copy_unavailable";image.recycle();}
  }
  private JSONObject clockEvent(JSONObject m,long t4){try{return new JSONObject(m.toString()).put("type","clock_sync").put("t4",Long.toString(t4)).put("clock_domain","android_elapsed_realtime");}catch(Exception e){return new JSONObject();}}
  private void command(JSONObject m){String operation=m.optString("operation");try{
    JSONObject result=new JSONObject().put("type","observer_result").put("request_id",m.getString("request_id"));
    if(operation.equals("start")){
      String id=m.getString("experiment_id");if(!id.matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("Invalid experiment ID");
      runDirectory=new File(getFilesDir(),"runs/"+id);if(runDirectory.exists()||!runDirectory.mkdirs())throw new IOException("Recording directory unavailable or already exists");
      experimentId=id;int fps=m.optInt("requested_fps",60),duration=m.optInt("duration_ms",10000);if(duration<1000||duration>70000)throw new IllegalArgumentException("Recording duration outside bounds");
      CompletableFuture<Void> ready=new CompletableFuture<>();cameraHandler.post(()->{try{startRecording(fps,ready);}catch(Exception e){ready.completeExceptionally(e);}});ready.get(12,TimeUnit.SECONDS);
      durationStop=statusWorker.schedule(()->cameraHandler.post(()->{try{stopRecording();}catch(Exception e){show(e.getMessage());}}),duration,TimeUnit.MILLISECONDS);
      result.put("fps",selectedFps).put("size",selectedSize.toString());
    }else if(operation.equals("stop")){
      CompletableFuture<Void> stopped=new CompletableFuture<>();cameraHandler.post(()->{try{stopRecording();stopped.complete(null);}catch(Exception e){stopped.completeExceptionally(e);}});stopped.get(10,TimeUnit.SECONDS);
    }else if(operation.equals("upload")){
      if(recording||runDirectory==null||!experimentId.equals(m.optString("experiment_id")))throw new IllegalStateException("Stop the matching recording first");
      for(String name:new String[]{"video.mp4","frames.jsonl","samples.jsonl","observer.json"}){File file=new File(runDirectory,name);if(file.isFile()&&file.length()>0)upload(file);}
    }else throw new IllegalArgumentException("Unknown observer command");
    send(result);
  }catch(Exception e){try{send(new JSONObject().put("type","observer_result").put("request_id",m.optString("request_id")).put("error",e.getMessage()==null?e.toString():e.getMessage()));}catch(Exception ignored){}show("Observer: "+e.getMessage());}}
  private void upload(File file)throws Exception{
    MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[] b=new byte[32768];for(int n;(n=in.read(b))!=-1;)digest.update(b,0,n);}
    ack(new JSONObject().put("operation","begin").put("name",file.getName()).put("bytes",file.length()).put("sha256",hex(digest.digest())));
    try(InputStream in=new FileInputStream(file)){byte[] b=new byte[32768];long offset=0;for(int n;(n=in.read(b))!=-1;){ack(new JSONObject().put("operation","chunk").put("offset",offset).put("data",android.util.Base64.encodeToString(b,0,n,android.util.Base64.NO_WRAP)));offset+=n;}}
    ack(new JSONObject().put("operation","end"));
  }
  private void ack(JSONObject m)throws Exception{
    String id=UUID.randomUUID().toString();CompletableFuture<JSONObject> response=new CompletableFuture<>();uploads.put(id,response);
    try{send(m.put("type","observer_upload").put("experiment_id",experimentId).put("request_id",id));JSONObject r=response.get(10,TimeUnit.SECONDS);if(r.has("error"))throw new IOException(r.getString("error"));}finally{uploads.remove(id);}
  }
  @Override public void onSurfaceTextureAvailable(SurfaceTexture texture,int w,int h){openCamera();}
  public void onSurfaceTextureSizeChanged(SurfaceTexture texture,int w,int h){preview.refreshOrientation();}
  public boolean onSurfaceTextureDestroyed(SurfaceTexture texture){cameraHandler.post(this::closeCamera);return true;}
  public void onSurfaceTextureUpdated(SurfaceTexture texture){}
  @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results){super.onRequestPermissionsResult(code,permissions,results);if(code==1&&results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED&&preview.isAvailable())openCamera();}
  private void openCamera(){if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)return;cameraHandler.post(()->{try{
    if(camera!=null||closing)return;CameraManager manager=(CameraManager)getSystemService(CAMERA_SERVICE);
    for(String id:manager.getCameraIdList()){CameraCharacteristics c=manager.getCameraCharacteristics(id);if(Objects.equals(c.get(CameraCharacteristics.LENS_FACING),CameraCharacteristics.LENS_FACING_BACK)){cameraId=id;characteristics=c;break;}}
    if(cameraId==null)throw new IllegalStateException("Rear camera unavailable");
    StreamConfigurationMap map=characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);JSONArray high=new JSONArray();
    if(map!=null)for(Size size:map.getHighSpeedVideoSizes())for(Range<Integer> range:map.getHighSpeedVideoFpsRangesFor(size))high.put(new JSONObject().put("size",size.toString()).put("min_fps",range.getLower()).put("max_fps",range.getUpper()));
    capabilities=new JSONObject().put("camera_id",cameraId).put("sensor_orientation_deg",characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)).put("timestamp_source",Objects.equals(characteristics.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE),CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME)?"REALTIME":"UNKNOWN").put("high_speed",high).put("device",Build.MODEL).put("android",Build.VERSION.RELEASE);
    manager.openCamera(cameraId,new CameraDevice.StateCallback(){public void onOpened(CameraDevice c){camera=c;previewSession();}public void onDisconnected(CameraDevice c){c.close();camera=null;session=null;show("Observer camera disconnected");}public void onError(CameraDevice c,int error){c.close();camera=null;session=null;show("Observer camera error "+error);}},cameraHandler);
  }catch(Exception e){show("Camera: "+e.getMessage());}});}
  private Surface previewSurface(Size size){previewBufferWidth=size.getWidth();previewBufferHeight=size.getHeight();int sensor=characteristics==null?0:java.util.Objects.requireNonNullElse(characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION),0);runOnUiThread(()->preview.imageSize(size.getWidth(),size.getHeight(),sensor));SurfaceTexture texture=preview.getSurfaceTexture();if(texture==null)throw new IllegalStateException("Camera preview unavailable");texture.setDefaultBufferSize(size.getWidth(),size.getHeight());if(previewSurface!=null)previewSurface.release();return previewSurface=new Surface(texture);}
  private void previewSession(){if(camera==null||closing)return;try{
    Surface surface=previewSurface(new Size(1280,720));camera.createCaptureSession(List.of(surface),new CameraCaptureSession.StateCallback(){public void onConfigured(CameraCaptureSession configured){session=configured;try{CaptureRequest.Builder request=camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);request.addTarget(surface);configured.setRepeatingRequest(request.build(),capture,cameraHandler);}catch(Exception e){show(e.getMessage());}}public void onConfigureFailed(CameraCaptureSession s){show("Preview configuration failed");}},cameraHandler);
  }catch(Exception e){show(e.getMessage());}}
  private final CameraCaptureSession.CaptureCallback capture=new CameraCaptureSession.CaptureCallback(){
    public void onCaptureCompleted(CameraCaptureSession source,CaptureRequest request,TotalCaptureResult result){
      lastFrameNs=SystemClock.elapsedRealtimeNanos();if(!recording)return;
      try{Long timestamp=result.get(CaptureResult.SENSOR_TIMESTAMP),exposure=result.get(CaptureResult.SENSOR_EXPOSURE_TIME),skew=result.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW);
        log(new JSONObject().put("type","frame").put("frame",frames++).put("camera_frame",result.getFrameNumber()).put("sensor_ns",timestamp==null?JSONObject.NULL:Long.toString(timestamp)).put("callback_ns",Long.toString(lastFrameNs)).put("exposure_ns",exposure==null?JSONObject.NULL:Long.toString(exposure)).put("rolling_shutter_ns",skew==null?JSONObject.NULL:Long.toString(skew)));
      }catch(Exception ignored){}
    }
    public void onCaptureFailed(CameraCaptureSession source,CaptureRequest request,CaptureFailure failure){try{log(new JSONObject().put("type","capture_failure").put("frame",failure.getFrameNumber()).put("reason",failure.getReason()));}catch(Exception ignored){}}
  };
  private void log(JSONObject m){if(frameLog!=null)try{frameLog.write(m.toString());frameLog.newLine();}catch(IOException e){show("Frame log write failed: "+e.getMessage());cameraHandler.post(()->{try{stopRecording();}catch(Exception ignored){}});}}
  private void startRecording(int requested,CompletableFuture<Void> ready)throws Exception{
    if(camera==null||recording)throw new IllegalStateException("Observer camera not ready");
    StreamConfigurationMap map=characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);if(map==null)throw new IllegalStateException("No camera stream configuration");
    selectedFps=30;selectedSize=new Size(1280,720);boolean highSpeed=false;
    if(requested>=120)outer:for(Size size:map.getHighSpeedVideoSizes())if(size.getWidth()<=1280&&size.getHeight()<=720)for(Range<Integer> range:map.getHighSpeedVideoFpsRangesFor(size))if(range.getLower()==120&&range.getUpper()==120){selectedFps=120;selectedSize=size;highSpeed=true;break outer;}
    if(!highSpeed){Size[] sizes=map.getOutputSizes(MediaRecorder.class);selectedSize=Arrays.stream(sizes).filter(s->s.getWidth()<=1920&&s.getHeight()<=1080).max(Comparator.comparingInt(s->s.getWidth()*s.getHeight())).orElseThrow(()->new IllegalStateException("No supported recording size"));
      if(requested>=60)for(Range<Integer> range:characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES))if(range.getLower()==60&&range.getUpper()==60)selectedFps=60;
    }
    if(session!=null){session.stopRepeating();session.close();session=null;}
    frameLog=new BufferedWriter(new FileWriter(new File(runDirectory,"frames.jsonl")));frames=0;
    recorder=new MediaRecorder();recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);recorder.setOutputFile(new File(runDirectory,"video.mp4").getAbsolutePath());recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);recorder.setVideoEncodingBitRate(selectedFps>=120?12000000:8000000);recorder.setVideoFrameRate(selectedFps);recorder.setVideoSize(selectedSize.getWidth(),selectedSize.getHeight());recorder.prepare();recordSurface=recorder.getSurface();Surface surface=previewSurface(selectedSize);final boolean constrained=highSpeed;
    CameraCaptureSession.StateCallback callback=new CameraCaptureSession.StateCallback(){
      public void onConfigured(CameraCaptureSession configured){session=configured;try{
        CaptureRequest.Builder request=camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);request.addTarget(surface);request.addTarget(recordSurface);request.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,new Range<>(selectedFps,selectedFps));
        if(constrained)((CameraConstrainedHighSpeedCaptureSession)configured).setRepeatingBurst(((CameraConstrainedHighSpeedCaptureSession)configured).createHighSpeedRequestList(request.build()),capture,cameraHandler);
        else configured.setRepeatingRequest(request.build(),capture,cameraHandler);
        recordingStartNs=SystemClock.elapsedRealtimeNanos();recorder.start();recording=true;
        log(new JSONObject().put("type","recording_started").put("phone_ns",Long.toString(recordingStartNs)).put("fps",selectedFps).put("timestamp_source",capabilities.optString("timestamp_source")));
        ready.complete(null);show("Recording "+selectedSize+" @ "+selectedFps+" fps");
      }catch(Exception e){ready.completeExceptionally(e);cleanupRecorder();previewSession();}}
      public void onConfigureFailed(CameraCaptureSession s){cleanupRecorder();previewSession();ready.completeExceptionally(new IOException("Recording mode unsupported; select another observer device/mode"));}
    };
    if(highSpeed)camera.createConstrainedHighSpeedCaptureSession(List.of(surface,recordSurface),callback,cameraHandler);else camera.createCaptureSession(List.of(surface,recordSurface),callback,cameraHandler);
  }
  private void cleanupRecorder(){recording=false;if(recorder!=null){try{recorder.reset();}catch(Exception ignored){}recorder.release();recorder=null;}if(recordSurface!=null){recordSurface.release();recordSurface=null;}if(frameLog!=null){try{frameLog.close();}catch(Exception ignored){}frameLog=null;}}
  private void stopRecording()throws Exception{
    if(durationStop!=null){durationStop.cancel(false);durationStop=null;}
    if(!recording){cleanupRecorder();return;}
    if(session!=null){session.stopRepeating();session.abortCaptures();session.close();session=null;}
    log(new JSONObject().put("type","recording_stopped").put("phone_ns",Long.toString(SystemClock.elapsedRealtimeNanos())));recording=false;
    Exception failure=null;try{recorder.stop();}catch(Exception e){failure=e;}finally{cleanupRecorder();}
    JSONObject metadata=new JSONObject().put("schemaVersion",1).put("app_version","0.7.2-rotation-fix").put("display_rotation_deg",displayRotationDegrees).put("snapshot_source","window_pixel_copy").put("experiment_id",experimentId).put("capabilities",capabilities).put("fps",selectedFps).put("size",selectedSize.toString()).put("recording_start_ns",Long.toString(recordingStartNs)).put("capture_callbacks",frames).put("frame_clock_alignment","Sensor and encoded presentation timestamps retained separately; do not assume ordinal equality").put("recording_error",failure==null?JSONObject.NULL:failure.toString());
    try(Writer out=new FileWriter(new File(runDirectory,"observer.json"))){out.write(metadata.toString(2));}
    if(failure==null)extractSamples();previewSession();if(failure!=null)throw failure;show("Recording saved locally. Uploading original evidence when requested.");
  }
  private void extractSamples()throws Exception{
    MediaExtractor extractor=new MediaExtractor();try{extractor.setDataSource(new File(runDirectory,"video.mp4").getAbsolutePath());
      for(int i=0;i<extractor.getTrackCount();i++)if(extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).startsWith("video/")){extractor.selectTrack(i);break;}
      try(BufferedWriter out=new BufferedWriter(new FileWriter(new File(runDirectory,"samples.jsonl")))){int index=0;while(extractor.getSampleTime()>=0){out.write(new JSONObject().put("sample",index++).put("pts_us",Long.toString(extractor.getSampleTime())).put("flags",extractor.getSampleFlags()).toString());out.newLine();if(!extractor.advance())break;}}
    }finally{extractor.release();}
  }
  private void closeCamera(){try{stopRecording();}catch(Exception ignored){}if(session!=null){session.close();session=null;}if(camera!=null){camera.close();camera=null;}if(previewSurface!=null){previewSurface.release();previewSurface=null;}}
  @Override protected void onPause(){super.onPause();paired=false;if(socket!=null)socket.close(1000,"Observer paused");cameraHandler.post(this::closeCamera);}
  @Override protected void onResume(){super.onResume();if(preview!=null&&preview.isAvailable())openCamera();}
  @Override protected void onDestroy(){closing=true;paired=false;statusWorker.shutdownNow();commands.shutdownNow();if(socket!=null)socket.close(1000,"Observer stopped");cameraHandler.post(()->{closeCamera();cameraThread.quitSafely();});client.dispatcher().executorService().shutdown();client.connectionPool().evictAll();super.onDestroy();}
}
