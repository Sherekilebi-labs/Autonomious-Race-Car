package org.maxbot.app;
import java.nio.ByteBuffer;
/** JNI symbol host harness only; never part of Android artifacts. */
final class ConeDetector {static native void prepare(ByteBuffer output,byte[] yuv,int w,int h,int kind,float scale,int zero);}
