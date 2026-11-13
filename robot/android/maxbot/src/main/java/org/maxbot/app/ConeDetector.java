package org.maxbot.app;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import org.maxbot.core.Geometry;
import org.maxbot.core.LatestFramePool;
import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.Tensor;
import org.tensorflow.lite.gpu.GpuDelegate;

/** One class YOLOv5 at 320. Create/run/close on the dedicated inference thread. */
final class ConeDetector implements AutoCloseable {
  static final class Detection {
    final float left, top, right, bottom, score;
    final Geometry.Point ground;

    Detection(float l, float t, float r, float b, float s, Geometry.Point p) {
      left = l;
      top = t;
      right = r;
      bottom = b;
      score = s;
      ground = p;
    }
  }

  static {System.loadLibrary("maxbot_image");}
  private final org.maxbot.core.ModelManifest manifest;
  private final org.maxbot.core.ModelDecoder decoder;
  private final float[] row=new float[6],decoded=new float[5];
  private final Interpreter interpreter;
  private final GpuDelegate delegate;
  private final ByteBuffer input, output;
  private final int inKind, outKind;
  private final float inScale, outScale;
  private final int inZero, outZero;
  private final float[][] candidates = new float[64][5];
  private final boolean[] suppressed = new boolean[64];

  ConeDetector(File file, boolean gpu, int threads) {
    try(java.io.Reader reader=new java.io.FileReader(file.getPath()+".json")){
      manifest=new com.google.gson.Gson().fromJson(reader,org.maxbot.core.ModelManifest.class);
      if(manifest==null||!manifest.validate().isEmpty())throw new IllegalArgumentException("Unsupported model manifest");
      java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");
      try(java.io.InputStream in=new java.io.FileInputStream(file)){byte[] bytes=new byte[8192];int n;while((n=in.read(bytes))!=-1)digest.update(bytes,0,n);}
      StringBuilder hash=new StringBuilder();for(byte b:digest.digest())hash.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
      if(!hash.toString().equals(manifest.sha256))throw new IllegalArgumentException("Model hash mismatch");
    }catch(Exception e){throw new IllegalArgumentException("Model/manifest: "+e.getMessage(),e);}
    decoder=new org.maxbot.core.ModelDecoder(manifest);
    Interpreter.Options options = new Interpreter.Options().setNumThreads(threads);
    delegate = gpu ? new GpuDelegate() : null;
    if (delegate != null) options.addDelegate(delegate);
    try {
      interpreter = new Interpreter(file, options);
    } catch (RuntimeException error) {
      if (delegate != null) delegate.close();
      throw error;
    }
    try {
      Tensor in = interpreter.getInputTensor(0), out = interpreter.getOutputTensor(0);
      int[] a = in.shape(), b = out.shape();
      if(!java.util.Arrays.equals(a,manifest.inputShape)||!java.util.Arrays.equals(b,manifest.outputShape)||!in.dataType().toString().equals(manifest.inputType)||!out.dataType().toString().equals(manifest.outputType))throw new IllegalArgumentException("Actual tensors differ from manifest");
      inKind = kind(in.dataType());
      outKind = kind(out.dataType());
      inScale = in.quantizationParams().getScale();
      inZero = in.quantizationParams().getZeroPoint();
      outScale = out.quantizationParams().getScale();
      outZero = out.quantizationParams().getZeroPoint();
      if ((inKind != 0 && inScale <= 0) || (outKind != 0 && outScale <= 0)) {
        throw new IllegalArgumentException("Invalid quantization metadata");
      }
      if((inKind!=0&&(Math.abs(inScale-manifest.inputScale)>1e-7||inZero!=manifest.inputZero))||(outKind!=0&&(Math.abs(outScale-manifest.outputScale)>1e-7||outZero!=manifest.outputZero)))throw new IllegalArgumentException("Actual quantization differs from manifest");
      input = ByteBuffer.allocateDirect(in.numBytes()).order(ByteOrder.nativeOrder());
      output = ByteBuffer.allocateDirect(out.numBytes()).order(ByteOrder.nativeOrder());
    } catch (RuntimeException error) {
      close();
      throw error;
    }
  }

  private static int kind(DataType t) {
    if (t == DataType.FLOAT32) return 0;
    if (t == DataType.UINT8) return 1;
    if (t == DataType.INT8) return 2;
    throw new IllegalArgumentException("Unsupported tensor type: " + t);
  }

  boolean liveValidated(){return manifest.liveValidated();}

  void warm(){input.rewind();output.rewind();interpreter.run(input,output);input.rewind();output.rewind();}

  List<Detection> detect(LatestFramePool.Slot frame) {
    prepare(input, frame.yuv, frame.width, frame.height, inKind, inScale, inZero);
    input.rewind();
    output.rewind();
    interpreter.run(input, output);
    output.rewind();
    int count = 0;
    for(int i=0;i<manifest.outputShape[1];i++){
      for(int j=0;j<6;j++)row[j]=value();
      if(!decoder.decode(row,frame.width,frame.height,decoded))continue;
      int slot;if(count<64)slot=count++;else{slot=lowest(count);if(decoded[4]<=candidates[slot][4])continue;}
      System.arraycopy(decoded,0,candidates[slot],0,5);
    }
    java.util.Arrays.fill(suppressed, false);
    List<Detection> detections = new ArrayList<>(8);
    ArCamera.Metadata metadata = (ArCamera.Metadata) frame.metadata;
    for (int n = 0; n < count && detections.size() < 12; n++) {
      int best = -1;
      for (int i = 0; i < count; i++)
        if (!suppressed[i] && (best < 0 || candidates[i][4] > candidates[best][4])) best = i;
      if (best < 0) break;
      suppressed[best] = true;
      float[] box = candidates[best];
      for (int i = 0; i < count; i++)
        if (manifest.nmsRequired && !suppressed[i] && iou(box, candidates[i]) > .4) suppressed[i] = true;
      int orange = 0;
      double meanX = 0;
      for (int yy = Math.max(0, (int) ((box[1] + box[3]) / 2)); yy < (int) box[3]; yy += 3)
        for (int xx = Math.max(0, (int) box[0]); xx < (int) box[2]; xx += 3)
          if (isOrange(frame.yuv, frame.width, frame.height, xx, yy)) {
            orange++;
            meanX += xx;
          }
      if (orange < 3) continue;
      double u = meanX / orange, v = box[3];
      Geometry.Point ground = null;
      if (metadata != null && v < frame.height - 3 && box[0] > 1 && box[2] < frame.width - 2)
        ground =
            Geometry.groundRay(
                u,
                v,
                metadata.fx,
                metadata.fy,
                metadata.cx,
                metadata.cy,
                metadata.origin,
                metadata.right,
                metadata.down,
                metadata.forward,
                0);
      // Box bottom is an inferred visible rim. Center lies one measured radius farther from camera.
      if(ground!=null)ground=Geometry.coneCenterFromRim(ground,new Geometry.Point(metadata.origin[0],-metadata.origin[2]),.1);
      detections.add(new Detection(box[0], box[1], box[2], box[3], box[4], ground));
    }
    return detections;
  }

  private int lowest(int count) {
    int best = 0;
    for (int i = 1; i < count; i++) if (candidates[i][4] < candidates[best][4]) best = i;
    return best;
  }

  private float value() {
    return outKind == 0
        ? output.getFloat()
        : outScale * ((outKind == 1 ? (output.get() & 255) : output.get()) - outZero);
  }

  private static float iou(float[] a, float[] b) {
    float area =
        Math.max(0, Math.min(a[2], b[2]) - Math.max(a[0], b[0]))
            * Math.max(0, Math.min(a[3], b[3]) - Math.max(a[1], b[1]));
    return area
        / Math.max(.001f, (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - area);
  }

  static boolean isOrange(byte[] yuv, int w, int h, int x, int y) {
    int yy = yuv[y * w + x] & 255,
        u = (yuv[w * h + (y / 2) * (w / 2) + x / 2] & 255) - 128,
        v = (yuv[w * h * 5 / 4 + (y / 2) * (w / 2) + x / 2] & 255) - 128;
    double r = yy + 1.402 * v, g = yy - .344 * u - .714 * v, b = yy + 1.772 * u;
    return r > 75 && g > 25 && r > g * 1.15 && g > b * 1.15 && r > b * 1.5;
  }

  static native void prepare(
      ByteBuffer input, byte[] yuv, int width, int height, int kind, float scale, int zero);

  @Override
  public void close() {
    interpreter.close();
    if (delegate != null) delegate.close();
  }
}
