package org.maxbot.app;

import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Process;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.maxbot.core.LatestFramePool;

/** Optional hardware AVC recording. Two owned buffers; a slow encoder drops frames. */
final class HardwareRecorder implements AutoCloseable {
  private final MediaCodec codec;
  private final MediaMuxer muxer;
  private final ScheduledExecutorService worker =
      Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "maxbot-avc"));
  private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
  private final byte[][] storage = {new byte[640 * 480 * 3 / 2], new byte[640 * 480 * 3 / 2]};
  private final boolean[] busy = {false, false};
  private final long[] timestamps = new long[2];
  private volatile boolean closed, failed;
  private long lastFrameNs, firstFrameNs;
  private int pending = -1, track = -1;
  private boolean muxing;

  HardwareRecorder(File file) throws Exception {
    String name = null;
    for (MediaCodecInfo candidate :
        new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
      if (!candidate.isEncoder() || !candidate.isHardwareAccelerated()) continue;
      for (String type : candidate.getSupportedTypes())
        if (type.equalsIgnoreCase("video/avc")) {
          MediaCodecInfo.CodecCapabilities cap = candidate.getCapabilitiesForType(type);
          if (cap.getVideoCapabilities().areSizeAndRateSupported(640, 480, 15))
            name = candidate.getName();
        }
      if (name != null) break;
    }
    if (name == null) throw new IllegalStateException("Hardware AVC encoder unavailable");
    codec = MediaCodec.createByCodecName(name);
    MediaFormat format = MediaFormat.createVideoFormat("video/avc", 640, 480);
    format.setInteger(
        MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
    format.setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000);
    format.setInteger(MediaFormat.KEY_FRAME_RATE, 15);
    format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
    codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
    codec.start();
    muxer = new MediaMuxer(file.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
    worker.scheduleWithFixedDelay(
        () -> {
          Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
          encode();
        },
        0,
        15,
        TimeUnit.MILLISECONDS);
  }

  synchronized void frame(LatestFramePool.Slot frame) {
    if (closed || failed || frame.timestampNs - lastFrameNs < 66_000_000L) return;
    for (int i = 0; i < 2; i++)
      if (!busy[i]) {
        busy[i] = true;
        System.arraycopy(frame.yuv, 0, storage[i], 0, storage[i].length);
        timestamps[i] = frame.timestampNs;
        if (pending >= 0) busy[pending] = false;
        pending = i;
        lastFrameNs = frame.timestampNs;
        return;
      }
  }

  private void encode() {
    int slot;
    synchronized (this) {
      slot = pending;
      pending = -1;
    }
    try {
      if (slot >= 0) {
        int input = codec.dequeueInputBuffer(0);
        if (input >= 0) {
          Image image = codec.getInputImage(input);
          if (image == null)
            throw new IllegalStateException("Encoder does not expose YUV input image");
          int offset = 0;
          for (int plane = 0; plane < 3; plane++) {
            int width = plane == 0 ? 640 : 320, height = plane == 0 ? 480 : 240;
            Image.Plane p = image.getPlanes()[plane];
            ByteBuffer dst = p.getBuffer();
            int base = dst.position();
            for (int y = 0; y < height; y++)
              for (int x = 0; x < width; x++)
                dst.put(
                    base + y * p.getRowStride() + x * p.getPixelStride(), storage[slot][offset++]);
          }
          if (firstFrameNs == 0) firstFrameNs = timestamps[slot];
          codec.queueInputBuffer(
              input, 0, 640 * 480 * 3 / 2, (timestamps[slot] - firstFrameNs) / 1000, 0);
        }
      }
      drain();
    } catch (Exception e) {
      failed = true;
    } finally {
      if (slot >= 0)
        synchronized (this) {
          busy[slot] = false;
        }
    }
  }

  private void drain() {
    for (int n = 0; n < 10; n++) {
      int output = codec.dequeueOutputBuffer(info, 0);
      if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
        track = muxer.addTrack(codec.getOutputFormat());
        muxer.start();
        muxing = true;
      } else if (output >= 0) {
        ByteBuffer bytes = codec.getOutputBuffer(output);
        if (muxing
            && bytes != null
            && info.size > 0
            && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
          bytes.position(info.offset);
          bytes.limit(info.offset + info.size);
          muxer.writeSampleData(track, bytes, info);
        }
        codec.releaseOutputBuffer(output, false);
      } else break;
    }
  }

  @Override
  public void close() {
    synchronized (this) {
      if (closed) return;
      closed = true;
    }
    worker.shutdown();
    try {
      if (!worker.awaitTermination(2, TimeUnit.SECONDS)) worker.shutdownNow();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    try {
      int i = codec.dequeueInputBuffer(10000);
      if (i >= 0)
        codec.queueInputBuffer(
            i,
            0,
            0,
            Math.max(0, lastFrameNs - firstFrameNs) / 1000,
            MediaCodec.BUFFER_FLAG_END_OF_STREAM);
      for (int n = 0; n < 10; n++) drain();
    } catch (Exception ignored) {
    }
    try {
      codec.stop();
    } catch (Exception ignored) {
    }
    codec.release();
    try {
      if (muxing) muxer.stop();
    } catch (Exception ignored) {
    }
    muxer.release();
  }
}
