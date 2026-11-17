package org.maxbot.app;
import java.nio.*;
import java.util.*;
public final class ImageNativeTest {
  static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
  public static void main(String[] args){
    System.loadLibrary("maxbot_image");
    ByteBuffer y=ByteBuffer.allocateDirect(16),u=ByteBuffer.allocateDirect(8),v=ByteBuffer.allocateDirect(8);
    for(int row=0;row<2;row++)for(int col=0;col<4;col++)y.put(row*8+col,(byte)255);
    u.put(0,(byte)128).put(2,(byte)128);v.put(0,(byte)128).put(2,(byte)128);
    byte[] packed=new byte[12];ArCamera.packPlanes(packed,y,u,v,4,2,8,1,8,2,8,2);
    for(int i=0;i<8;i++)check((packed[i]&255)==255,"strided Y rows");for(int i=8;i<12;i++)check((packed[i]&255)==128,"interleaved chroma strides");
    ByteBuffer out=ByteBuffer.allocateDirect(320*320*3*4).order(ByteOrder.nativeOrder());ConeDetector.prepare(out,packed,4,2,0,0,0);
    check(Math.abs(out.getFloat(0)-114f/255)<1e-6,"letterbox padding");check(Math.abs(out.getFloat((160*320+160)*12)-1)<1e-6,"white normalized RGB");
    ByteBuffer integer=ByteBuffer.allocateDirect(320*320*3);ConeDetector.prepare(integer,packed,4,2,2,1f/255,-128);check(integer.get((160*320+160)*3)==127,"INT8 quantization");
    ByteBuffer small=ByteBuffer.allocateDirect(1);small.put(0,(byte)42);ConeDetector.prepare(small,packed,4,2,0,0,0);check(small.get(0)==42,"short output rejected");
    ConeDetector.prepare(integer,packed,4,2,2,0,-128);check(integer.get((160*320+160)*3)==127,"invalid quantization rejected");
    ByteBuffer dy=ByteBuffer.allocateDirect(8),du=ByteBuffer.allocateDirect(2),dv=ByteBuffer.allocateDirect(2);
    check(VideoBridge.packPlanesDirect(dy,du,dv,y,u,v,4,2,8,1,8,2,8,2),"direct pack succeeds");
    for(int i=0;i<8;i++)check(dy.get(i)==packed[i],"direct luma matches heap path");
    for(int i=0;i<2;i++){check(du.get(i)==packed[8+i],"direct U matches heap path");check(dv.get(i)==packed[10+i],"direct V matches heap path");}
    check(!VideoBridge.packPlanesDirect(small,du,dv,y,u,v,4,2,8,1,8,2,8,2),"direct short destination rejected");
    check(!VideoBridge.packPlanesDirect(dy,du,dv,small,u,v,4,2,8,1,8,2,8,2),"direct short source rejected");
    Random random=new Random(42);
    for(int stride:new int[]{1,2,3}){
      ByteBuffer yy=ByteBuffer.allocateDirect(22*6),uu=ByteBuffer.allocateDirect(13*3),vv=ByteBuffer.allocateDirect(13*3);
      for(ByteBuffer plane:new ByteBuffer[]{yy,uu,vv})for(int i=0;i<plane.capacity();i++)plane.put(i,(byte)random.nextInt());
      byte[] expected=new byte[8*6*3/2];ArCamera.packPlanes(expected,yy,uu,vv,8,6,22,2,13,stride,13,stride);
      ByteBuffer a=ByteBuffer.allocateDirect(48),b=ByteBuffer.allocateDirect(12),c=ByteBuffer.allocateDirect(12);
      check(VideoBridge.packPlanesDirect(a,b,c,yy,uu,vv,8,6,22,2,13,stride,13,stride),"random direct strided pack");
      int offset=0;for(ByteBuffer plane:new ByteBuffer[]{a,b,c})for(int i=0;i<plane.capacity();i++)check(plane.get(i)==expected[offset++],"byte-exact direct plane");
    }
    benchmark();
    System.out.println("PASS independent non-MPC JNI: strides, byte-exact direct/heap I420, letterbox, float/INT8 normalization and buffer bounds");
  }
  static void benchmark(){
    int n=800,size=640*480;ByteBuffer y=ByteBuffer.allocateDirect(size),u=ByteBuffer.allocateDirect(size/2),v=ByteBuffer.allocateDirect(size/2);
    ByteBuffer dy=ByteBuffer.allocateDirect(size),du=ByteBuffer.allocateDirect(size/4),dv=ByteBuffer.allocateDirect(size/4);
    byte[] heap=new byte[size*3/2],fanout=new byte[heap.length];
    for(int i=0;i<100;i++){ArCamera.packPlanes(heap,y,u,v,640,480,640,1,640,2,640,2);VideoBridge.packPlanesDirect(dy,du,dv,y,u,v,640,480,640,1,640,2,640,2);}
    long[] old=new long[n],direct=new long[n];
    for(int i=0;i<n;i++){
      long start=System.nanoTime();ArCamera.packPlanes(heap,y,u,v,640,480,640,1,640,2,640,2);System.arraycopy(heap,0,fanout,0,heap.length);
      dy.clear();du.clear();dv.clear();dy.put(fanout,0,size);du.put(fanout,size,size/4);dv.put(fanout,size*5/4,size/4);old[i]=System.nanoTime()-start;
      start=System.nanoTime();VideoBridge.packPlanesDirect(dy,du,dv,y,u,v,640,480,640,1,640,2,640,2);direct[i]=System.nanoTime()-start;
    }
    Arrays.sort(old);Arrays.sort(direct);
    System.out.printf(Locale.ROOT,"HOST_ONLY I420 staging 640x480, interleaved chroma, n=%d: old p50=%.3fms p95=%.3fms; direct p50=%.3fms p95=%.3fms%n",n,old[n/2]/1e6,old[n*95/100]/1e6,direct[n/2]/1e6,direct[n*95/100]/1e6);
  }

}
