package org.maxbot.app;

import android.content.Context;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.json.JSONObject;
import org.maxbot.core.LatestFramePool;
import org.webrtc.*;

/** WebRTC uses only hardware encoders and pooled I420 storage; no second camera capturer. */
final class VideoBridge implements AutoCloseable {
  private final EglBase egl = EglBase.create();
  private final PeerConnectionFactory factory;
  private final VideoSource source;
  private final VideoTrack track;
  private final Consumer<JSONObject> signal;
  private PeerConnection peer;
  private final List<IceCandidate> pending = new ArrayList<>();
  private boolean remoteSet, closed;
  private long lastFrameNs;
  private final Storage[] buffers = {new Storage(), new Storage(), new Storage()};

  private static final class Storage {
    final ByteBuffer y = ByteBuffer.allocateDirect(640 * 480),
        u = ByteBuffer.allocateDirect(320 * 240),
        v = ByteBuffer.allocateDirect(320 * 240);
    final AtomicBoolean busy = new AtomicBoolean();
  }

  VideoBridge(Context context, Consumer<JSONObject> signal) {
    this.signal = signal;
    PeerConnectionFactory.initialize(
        PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions());
    HardwareVideoEncoderFactory encoders =
        new HardwareVideoEncoderFactory(egl.getEglBaseContext(), true, true);
    if (encoders.getSupportedCodecs().length == 0)
      throw new IllegalStateException("No supported hardware video encoder");
    factory =
        PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoders)
            .setVideoDecoderFactory(new DefaultVideoDecoderFactory(egl.getEglBaseContext()))
            .createPeerConnectionFactory();
    source = factory.createVideoSource(false);
    source.adaptOutputFormat(640, 480, 15);
    source.getCapturerObserver().onCapturerStarted(true);
    track = factory.createVideoTrack("maxbot-camera", source);
  }

  synchronized void start() {
    if (closed) return;
    if (peer != null) peer.dispose();
    remoteSet = false;
    pending.clear();
    PeerConnection.RTCConfiguration rtc =
        new PeerConnection.RTCConfiguration(Collections.emptyList());
    rtc.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
    peer =
        factory.createPeerConnection(
            rtc,
            new PeerConnection.Observer() {
              public void onSignalingChange(PeerConnection.SignalingState state) {}

              public void onIceConnectionChange(PeerConnection.IceConnectionState state) {}

              public void onIceConnectionReceivingChange(boolean receiving) {}

              public void onIceGatheringChange(PeerConnection.IceGatheringState state) {}

              public void onIceCandidate(IceCandidate candidate) {
                try {
                  signal.accept(
                      new JSONObject()
                          .put("type", "rtc")
                          .put("kind", "candidate")
                          .put("candidate", candidate.sdp)
                          .put("sdpMid", candidate.sdpMid)
                          .put("sdpMLineIndex", candidate.sdpMLineIndex));
                } catch (Exception ignored) {
                }
              }

              public void onIceCandidatesRemoved(IceCandidate[] candidates) {}

              public void onAddStream(MediaStream stream) {}

              public void onRemoveStream(MediaStream stream) {}

              public void onDataChannel(DataChannel channel) {
                channel.close();
              }

              public void onRenegotiationNeeded() {}

              public void onAddTrack(RtpReceiver receiver, MediaStream[] streams) {}
            });
    if (peer == null) return;
    RtpSender sender = peer.addTrack(track, Collections.singletonList("maxbot"));
    RtpParameters parameters = sender.getParameters();
    for (RtpParameters.Encoding encoding : parameters.encodings) {
      encoding.maxBitrateBps = 1_500_000;
      encoding.maxFramerate = 15;
    }
    sender.setParameters(parameters);
    final PeerConnection call = peer;
    peer.createOffer(
        new Sdp() {
          public void onCreateSuccess(SessionDescription sdp) {
            synchronized (VideoBridge.this) {
              if (peer != call) return;
              call.setLocalDescription(new Sdp() {}, sdp);
              try {
                signal.accept(
                    new JSONObject()
                        .put("type", "rtc")
                        .put("kind", "offer")
                        .put("sdp", sdp.description));
              } catch (Exception ignored) {
              }
            }
          }
        },
        new MediaConstraints());
  }

  synchronized void receive(JSONObject m) {
    if (peer == null) return;
    final PeerConnection call = peer;
    try {
      if ("answer".equals(m.optString("kind")))
        peer.setRemoteDescription(
            new Sdp() {
              public void onSetSuccess() {
                synchronized (VideoBridge.this) {
                  if (peer != call) return;
                  remoteSet = true;
                  for (IceCandidate candidate : pending) peer.addIceCandidate(candidate);
                  pending.clear();
                }
              }
            },
            new SessionDescription(SessionDescription.Type.ANSWER, m.getString("sdp")));
      else if ("candidate".equals(m.optString("kind"))) {
        IceCandidate ice =
            new IceCandidate(
                m.optString("sdpMid", "0"), m.optInt("sdpMLineIndex"), m.getString("candidate"));
        if (remoteSet) peer.addIceCandidate(ice);
        else if (pending.size() < 64) pending.add(ice);
      }
    } catch (Exception ignored) {
    }
  }

  synchronized void frame(LatestFramePool.Slot f) {
    if (closed || peer == null || f.timestampNs - lastFrameNs < 66_000_000L) return;
    Storage storage = null;
    for (Storage b : buffers)
      if (b.busy.compareAndSet(false, true)) {
        storage = b;
        break;
      }
    if (storage == null) return;
    lastFrameNs = f.timestampNs;
    final Storage slot = storage;
    slot.y.clear();
    slot.u.clear();
    slot.v.clear();
    slot.y.put(f.yuv, 0, 640 * 480).rewind();
    slot.u.put(f.yuv, 640 * 480, 320 * 240).rewind();
    slot.v.put(f.yuv, 640 * 480 * 5 / 4, 320 * 240).rewind();
    JavaI420Buffer buffer =
        JavaI420Buffer.wrap(
            640, 480, slot.y, 640, slot.u, 320, slot.v, 320, () -> slot.busy.set(false));
    VideoFrame frame = new VideoFrame(buffer, 0, f.timestampNs);
    source.getCapturerObserver().onFrameCaptured(frame);
    frame.release();
  }

  private static class Sdp implements SdpObserver {
    public void onCreateSuccess(SessionDescription sdp) {}

    public void onSetSuccess() {}

    public void onCreateFailure(String reason) {}

    public void onSetFailure(String reason) {}
  }

  @Override
  public synchronized void close() {
    closed = true;
    if (peer != null) {
      peer.dispose();
      peer = null;
    }
    track.dispose();
    source.dispose();
    factory.dispose();
    egl.release();
  }
}
