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
    System.out.println("PASS independent non-MPC JNI: strides, letterbox, float/INT8 normalization and buffer bounds");
  }
}
