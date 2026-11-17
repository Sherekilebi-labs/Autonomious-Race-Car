package org.maxbot.app;

import android.content.Context;
import android.media.Image;
import android.os.SystemClock;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.json.JSONObject;
import org.maxbot.core.LatestDispatcher;
import org.webrtc.*;

/** One ARCore camera, hardware encoding, and bounded direct I420 storage. */
final class VideoBridge implements AutoCloseable {
  private static final int STREAM_FPS = 30, MAX_BITRATE_BPS = 3_000_000;
  static { System.loadLibrary("maxbot_image"); }
  static native boolean packPlanesDirect(ByteBuffer dy, ByteBuffer du, ByteBuffer dv,
      ByteBuffer y, ByteBuffer u, ByteBuffer v, int width, int height,
      int yr, int yp, int ur, int up, int vr, int vp);
  private final EglBase egl = EglBase.create();
  private final PeerConnectionFactory factory;
  private final VideoSource source;
  private final VideoTrack track;
  private final Consumer<JSONObject> signal, input;
  private final String key;
  private final Object captureLock = new Object();
  private final ExecutorService frames = Executors.newSingleThreadExecutor();
  private final LatestDispatcher<VideoFrame> latestFrames;
  private PeerConnection peer;
  private DataChannel controls;
  private RealtimeControl control;
  private final List<IceCandidate> pending = new ArrayList<>();
  private boolean remoteSet;
  private volatile boolean closed, connected;
  private volatile double packMs, queueMs;
  private volatile long packed, noBuffer;
  private volatile JSONObject encoderStats = new JSONObject();
  private long lastStatsNs, lastProbeNs;
  private final Storage[] buffers = {new Storage(), new Storage(), new Storage()};

  private static final class Storage {
    final ByteBuffer y = ByteBuffer.allocateDirect(640 * 480),
        u = ByteBuffer.allocateDirect(320 * 240), v = ByteBuffer.allocateDirect(320 * 240);
    final AtomicBoolean busy = new AtomicBoolean();
  }

  VideoBridge(Context context, String key, Consumer<JSONObject> signal, Consumer<JSONObject> input) {
    this.signal = signal; this.input = input; this.key = key;
    PeerConnectionFactory.initialize(
        PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions());
    HardwareVideoEncoderFactory encoders = new HardwareVideoEncoderFactory(egl.getEglBaseContext(), true, true);
    if (encoders.getSupportedCodecs().length == 0) throw new IllegalStateException("No supported hardware video encoder");
    factory = PeerConnectionFactory.builder().setVideoEncoderFactory(encoders)
        .setVideoDecoderFactory(new DefaultVideoDecoderFactory(egl.getEglBaseContext())).createPeerConnectionFactory();
    source = factory.createVideoSource(false);
    // ARCore already captures 640x480 at 30 fps; pass that cadence through to the encoder.
    source.adaptOutputFormat(640, 480, STREAM_FPS);
    source.getCapturerObserver().onCapturerStarted(true);
    track = factory.createVideoTrack("maxbot-camera", source);
    latestFrames = new LatestDispatcher<>(frames, frame -> {
      synchronized (captureLock) {
        if (!closed && connected) {
          queueMs = Math.max(0, (SystemClock.elapsedRealtimeNanos() - frame.getTimestampNs()) / 1e6);
          source.getCapturerObserver().onFrameCaptured(frame);
        }
      }
    }, VideoFrame::release);
  }

  synchronized void start(String session) {
    if (closed) return;
    stopPeer(); remoteSet = false; pending.clear();
    PeerConnection.RTCConfiguration rtc = new PeerConnection.RTCConfiguration(Collections.emptyList());
    rtc.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
    rtc.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE;
    rtc.networkPreference = PeerConnection.AdapterType.WIFI;
    peer = factory.createPeerConnection(rtc, new PeerConnection.Observer() {
      public void onSignalingChange(PeerConnection.SignalingState state) {}
      public void onIceConnectionChange(PeerConnection.IceConnectionState state) {}
      public void onIceConnectionReceivingChange(boolean receiving) {}
      public void onIceGatheringChange(PeerConnection.IceGatheringState state) {}
      public void onIceCandidate(IceCandidate candidate) {
        try { signal.accept(new JSONObject().put("type", "rtc").put("kind", "candidate")
            .put("candidate", candidate.sdp).put("sdpMid", candidate.sdpMid).put("sdpMLineIndex", candidate.sdpMLineIndex)); }
        catch (Exception ignored) {}
      }
      public void onIceCandidatesRemoved(IceCandidate[] candidates) {}
      public void onAddStream(MediaStream stream) {}
      public void onRemoveStream(MediaStream stream) {}
      public void onDataChannel(DataChannel channel) { channel.close(); }
      public void onRenegotiationNeeded() {}
      public void onAddTrack(RtpReceiver receiver, MediaStream[] streams) {}
    });
    if (peer == null) return;
    connected = true;
    DataChannel.Init init = new DataChannel.Init(); init.ordered = false; init.maxRetransmits = 0;
    final DataChannel channel = controls = peer.createDataChannel("maxbot-controls", init);
    final RealtimeControl lane = control = new RealtimeControl(key, session,
        m -> { if (channel.state() == DataChannel.State.OPEN && channel.bufferedAmount() < 8192)
          channel.send(new DataChannel.Buffer(ByteBuffer.wrap(m.toString().getBytes(StandardCharsets.UTF_8)), false)); },
        input, SystemClock::elapsedRealtime);
    channel.registerObserver(new DataChannel.Observer() {
      public void onBufferedAmountChange(long previous) {}
      public void onStateChange() { if (channel.state() == DataChannel.State.OPEN) lane.hello(); }
      public void onMessage(DataChannel.Buffer buffer) {
        if (buffer.binary || buffer.data.remaining() > 4096) return;
        byte[] bytes = new byte[buffer.data.remaining()]; buffer.data.get(bytes);
        try { lane.receive(new JSONObject(new String(bytes, StandardCharsets.UTF_8))); } catch (Exception ignored) {}
      }
    });
    RtpSender sender = peer.addTrack(track, Collections.singletonList("maxbot"));
    RtpParameters parameters = sender.getParameters();
    // Under load, prefer fewer frames over reducing the requested image dimensions.
    parameters.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION;
    for (RtpParameters.Encoding encoding : parameters.encodings) {
      encoding.maxBitrateBps = MAX_BITRATE_BPS; encoding.maxFramerate = STREAM_FPS;
      encoding.scaleResolutionDownBy = 1.0;
    }
    sender.setParameters(parameters);
    peer.setBitrate(null, 1_500_000, MAX_BITRATE_BPS);
    final PeerConnection call = peer;
    peer.createOffer(new Sdp() {
      public void onCreateSuccess(SessionDescription sdp) {
        synchronized (VideoBridge.this) {
          if (peer != call) return;
          call.setLocalDescription(new Sdp() {}, sdp);
          try { signal.accept(new JSONObject().put("type", "rtc").put("kind", "offer").put("sdp", sdp.description)); }
          catch (Exception ignored) {}
        }
      }
    }, new MediaConstraints());
  }

  synchronized void receive(JSONObject m) {
    if (peer == null) return;
    final PeerConnection call = peer;
    try {
      if ("answer".equals(m.optString("kind"))) peer.setRemoteDescription(new Sdp() {
        public void onSetSuccess() {
          synchronized (VideoBridge.this) {
            if (peer != call) return;
            remoteSet = true;
            for (IceCandidate candidate : pending) peer.addIceCandidate(candidate);
            pending.clear();
          }
        }
      }, new SessionDescription(SessionDescription.Type.ANSWER, m.getString("sdp")));
      else if ("candidate".equals(m.optString("kind"))) {
        IceCandidate ice = new IceCandidate(m.optString("sdpMid", "0"), m.optInt("sdpMLineIndex"), m.getString("candidate"));
        if (remoteSet) peer.addIceCandidate(ice); else if (pending.size() < 64) pending.add(ice);
      }
    } catch (Exception ignored) {}
  }

  void image(Image image, long timestampNs) {
    if (closed || !connected || image.getWidth() != 640 || image.getHeight() != 480) return;
    Storage storage = null;
    for (Storage b : buffers) if (b.busy.compareAndSet(false, true)) { storage = b; break; }
    if (storage == null) { noBuffer++; return; }
    final Storage slot = storage;
    boolean handedOff = false;
    try {
      long start = SystemClock.elapsedRealtimeNanos();
      Image.Plane[] p = image.getPlanes();
      if (!packPlanesDirect(slot.y, slot.u, slot.v, p[0].getBuffer().slice(), p[1].getBuffer().slice(), p[2].getBuffer().slice(),
          640, 480, p[0].getRowStride(), p[0].getPixelStride(), p[1].getRowStride(), p[1].getPixelStride(), p[2].getRowStride(), p[2].getPixelStride())) return;
      packMs = (SystemClock.elapsedRealtimeNanos() - start) / 1e6; packed++;
      slot.y.clear(); slot.u.clear(); slot.v.clear();
      JavaI420Buffer buffer = JavaI420Buffer.wrap(640, 480, slot.y, 640, slot.u, 320, slot.v, 320, () -> slot.busy.set(false));
      VideoFrame frame = new VideoFrame(buffer, 0, timestampNs);
      handedOff = true; latestFrames.offer(frame);
    } finally { if (!handedOff) slot.busy.set(false); }
  }

  synchronized JSONObject stats(long nowNs) {
    if (control != null && nowNs - lastProbeNs > 500_000_000L) {
      lastProbeNs = nowNs; control.hello(); control.probe();
    }
    if (peer != null && nowNs - lastStatsNs > 1_000_000_000L) {
      lastStatsNs = nowNs;
      peer.getStats(report -> {
        JSONObject out = new JSONObject();
        for (RTCStats stat : report.getStatsMap().values()) {
          java.util.Map<String, Object> m = stat.getMembers();
          if ("outbound-rtp".equals(stat.getType()) && ("video".equals(m.get("kind")) || "video".equals(m.get("mediaType")))) {
            for (String k : new String[]{"framesEncoded", "totalEncodeTime", "framesSent", "bytesSent", "qualityLimitationReason", "frameWidth", "frameHeight", "framesPerSecond"})
              try { if (m.containsKey(k)) out.put(k, m.get(k)); } catch (Exception ignored) {}
          }
        }
        encoderStats = out;
      });
    }
    try { return new JSONObject(encoderStats.toString()).put("pack_ms", packMs).put("capture_to_encoder_ms", queueMs)
        .put("frames_packed", packed).put("pending_replaced", latestFrames.replaced()).put("pool_drops", noBuffer)
        .put("target_fps", STREAM_FPS).put("max_bitrate_bps", MAX_BITRATE_BPS)
        .put("direct_control", control != null && control.authenticated()); }
    catch (Exception ignored) { return new JSONObject(); }
  }

  synchronized void operatorLeft() { connected=false; if (control != null) control.close(); }
  private void stopPeer() {
    connected = false;
    if (control != null) { control.close(); control = null; }
    if (controls != null) { controls.unregisterObserver(); controls.close(); controls.dispose(); controls = null; }
    if (peer != null) { peer.dispose(); peer = null; }
  }
  private static class Sdp implements SdpObserver {
    public void onCreateSuccess(SessionDescription sdp) {} public void onSetSuccess() {}
    public void onCreateFailure(String reason) {} public void onSetFailure(String reason) {}
  }
  @Override public synchronized void close() {
    if (closed) return;
    closed = true; stopPeer(); latestFrames.close(); frames.shutdownNow();
    synchronized (captureLock) { track.dispose(); source.dispose(); }
    factory.dispose(); egl.release();
  }
}
