package org.maxbot.core;

import java.nio.*;
import java.util.List;

final class CollisionWarningTest {
  private static void row(ByteBuffer b,float x,float y,float w,float h,float objectness,int category,float probability){b.putFloat(x).putFloat(y).putFloat(w).putFloat(h).putFloat(objectness);for(int i=0;i<80;i++)b.putFloat(i==category?probability:0);}
  static void run(){
    ByteBuffer b=ByteBuffer.allocate(5*85*4).order(ByteOrder.nativeOrder());
    row(b,.5f,.6f,.4f,.45f,.8f,0,.8f); // Large central person, score .64.
    row(b,.5f,.6f,.4f,.45f,.8f,0,.7f); // Same-class duplicate suppressed.
    row(b,.5f,.6f,.4f,.45f,.8f,2,.7f); // Overlapping different class retained.
    row(b,.5f,.6f,.4f,.45f,.5f,0,.5f); // Objectness*probability below threshold.
    row(b,Float.NaN,.6f,.4f,.45f,1,0,1);
    List<CollisionWarning.Box> boxes=CollisionWarning.decode(b,5);
    CoreTest.check(boxes.size()==2,"COCO decode uses class-aware NMS and objectness product");
    CoreTest.check(boxes.get(0).warning(),"central large lower-image detection triggers advisory");
    CoreTest.check(Math.abs(boxes.get(0).top-(.6-.45/2-.125)/.75)<1e-6,"undo 640x480 letterbox padding");
    CoreTest.check(!new CollisionWarning.Box(0,.7f,.1f,1,1,0).warning(),"peripheral box does not trigger central zone");
    CoreTest.check(!new CollisionWarning.Box(.45f,.7f,.55f,.8f,1,0).warning(),"small box does not trigger area threshold");
    CoreTest.check(CollisionWarning.decode(ByteBuffer.allocate(0),0).isEmpty(),"empty detections remain no warning");
  }
}
