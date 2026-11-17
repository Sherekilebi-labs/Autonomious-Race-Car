package org.maxbot.core;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** COCO YOLOv5 baseline. A 2D proximity heuristic, with no metric depth or stop authority. */
public final class CollisionWarning {
  public static final float CONFIDENCE=.35f, NMS_IOU=.45f;
  public static final float LEFT=.2f, RIGHT=.8f, BOTTOM=.65f, AREA=.08f;
  public static final class Box {
    public final float left,top,right,bottom,score; public final int classId;
    public Box(float l,float t,float r,float b,float s,int id){left=l;top=t;right=r;bottom=b;score=s;classId=id;}
    public boolean warning(){return (left+right)/2>=LEFT&&(left+right)/2<=RIGHT&&bottom>=BOTTOM&&(right-left)*(bottom-top)>=AREA;}
  }
  private CollisionWarning(){}
  public static List<Box> decode(ByteBuffer output,int rows){
    List<Box> candidates=new ArrayList<>();
    output.rewind();
    for(int i=0;i<rows;i++){
      float x=output.getFloat(),y=output.getFloat(),w=output.getFloat(),h=output.getFloat(),objectness=output.getFloat();
      int id=0;float probability=-1;
      for(int c=0;c<80;c++){float p=output.getFloat();if(p>probability){probability=p;id=c;}}
      float score=objectness*probability;
      if(!Float.isFinite(x+y+w+h+score)||score<CONFIDENCE||score>1||w<=0||h<=0)continue;
      // TFDetect exports normalized coordinates in the square letterboxed image.
      // 640x480 -> 320x240 with 40px padding above/below.
      float l=clip(x-w/2),r=clip(x+w/2),t=clip((y-h/2-.125f)/.75f),b=clip((y+h/2-.125f)/.75f);
      if(l>=r||t>=b)continue;
      candidates.add(new Box(l,t,r,b,score,id));
    }
    candidates.sort(Comparator.comparingDouble((Box b)->b.score).reversed());
    // Bound NMS work even when a corrupt/noisy frame produces thousands of proposals.
    if(candidates.size()>128)candidates=new ArrayList<>(candidates.subList(0,128));
    List<Box> result=new ArrayList<>();
    for(Box box:candidates){
      boolean suppressed=false;
      for(Box kept:result)if(kept.classId==box.classId&&iou(box,kept)>NMS_IOU){suppressed=true;break;}
      if(!suppressed){result.add(box);if(result.size()==20)break;}
    }
    return result;
  }
  private static float clip(float v){return Math.max(0,Math.min(1,v));}
  private static float iou(Box a,Box b){float intersection=Math.max(0,Math.min(a.right,b.right)-Math.max(a.left,b.left))*Math.max(0,Math.min(a.bottom,b.bottom)-Math.max(a.top,b.top));return intersection/Math.max(1e-9f,(a.right-a.left)*(a.bottom-a.top)+(b.right-b.left)*(b.bottom-b.top)-intersection);}
}
