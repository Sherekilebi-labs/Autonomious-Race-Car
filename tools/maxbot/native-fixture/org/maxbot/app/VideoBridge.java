package org.maxbot.app;
import java.nio.ByteBuffer;
/** JNI symbol host harness only. */
final class VideoBridge {static native boolean packPlanesDirect(ByteBuffer dy,ByteBuffer du,ByteBuffer dv,ByteBuffer y,ByteBuffer u,ByteBuffer v,int w,int h,int yr,int yp,int ur,int up,int vr,int vp);}
