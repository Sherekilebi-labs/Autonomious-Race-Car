package org.maxbot.app;

import android.content.Context;
import android.os.SystemClock;
import java.io.*;
import java.nio.*;
import java.util.List;
import org.json.JSONObject;
import org.maxbot.core.CollisionWarning;
import org.maxbot.core.LatestFramePool;
import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;

/** Bundled general-object baseline, kept separate from the trained autonomous cone detector. */
final class BaselineDetector implements AutoCloseable {
  final JSONObject provenance;
  private final Interpreter interpreter;
  private final ByteBuffer input=ByteBuffer.allocateDirect(320*320*3*4).order(ByteOrder.nativeOrder());
  private final ByteBuffer output=ByteBuffer.allocateDirect(6300*85*4).order(ByteOrder.nativeOrder());
  static final class Result {
    final List<CollisionWarning.Box> boxes;final long start,prepared,inferred,decided;
    Result(List<CollisionWarning.Box> boxes,long start,long prepared,long inferred,long decided){this.boxes=boxes;this.start=start;this.prepared=prepared;this.inferred=inferred;this.decided=decided;}
  }
  BaselineDetector(Context context)throws Exception {
    byte[] bytes;
    try(InputStream in=context.getAssets().open("maxbot/yolov5n-baseline.json")){provenance=new JSONObject(new String(read(in),java.nio.charset.StandardCharsets.UTF_8));}
    try(InputStream in=context.getAssets().open("maxbot/yolov5n-fp16.tflite")){bytes=read(in);}
    if(!ArtifactManager.hashBytes(bytes).equals(provenance.getString("sha256")))throw new IllegalArgumentException("Baseline model hash mismatch");
    ByteBuffer model=ByteBuffer.allocateDirect(bytes.length).order(ByteOrder.nativeOrder());model.put(bytes).rewind();
    interpreter=new Interpreter(model,new Interpreter.Options().setNumThreads(2).setUseXNNPACK(true));
    try{
      if(interpreter.getInputTensorCount()!=1||interpreter.getOutputTensorCount()!=1||
        !java.util.Arrays.equals(interpreter.getInputTensor(0).shape(),new int[]{1,320,320,3})||
        !java.util.Arrays.equals(interpreter.getOutputTensor(0).shape(),new int[]{1,6300,85})||
        interpreter.getInputTensor(0).dataType()!=DataType.FLOAT32||interpreter.getOutputTensor(0).dataType()!=DataType.FLOAT32)
        throw new IllegalArgumentException("Unsupported baseline tensor contract");
      interpreter.run(input,output); // Warm once; cold loading is recorded separately.
    }catch(Exception e){interpreter.close();throw e;}
  }
  private static byte[] read(InputStream in)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] block=new byte[8192];int n;while((n=in.read(block))!=-1)out.write(block,0,n);return out.toByteArray();}
  Result detect(LatestFramePool.Slot frame){
    if(frame.width!=640||frame.height!=480)throw new IllegalArgumentException("Baseline expects 640x480 sensor image");
    long start=SystemClock.elapsedRealtimeNanos();
    ConeDetector.prepare(input,frame.yuv,frame.width,frame.height,0,0,0);input.rewind();output.rewind();
    long prepared=SystemClock.elapsedRealtimeNanos();interpreter.run(input,output);
    long inferred=SystemClock.elapsedRealtimeNanos();List<CollisionWarning.Box> boxes=CollisionWarning.decode(output,6300);
    return new Result(boxes,start,prepared,inferred,SystemClock.elapsedRealtimeNanos());
  }
  public void close(){interpreter.close();}
}
