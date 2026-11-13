package org.maxbot.app;
import java.nio.ByteBuffer;
/** JNI symbol host harness only. */
final class ArCamera {static native void packPlanes(byte[] output,ByteBuffer y,ByteBuffer u,ByteBuffer v,int w,int h,int yr,int yp,int ur,int up,int vr,int vp);}
