package org.maxbot.core;
/** Allocation-free explicitly manifested tensor rows → unrotated CPU-image boxes. */
public final class ModelDecoder {
  private final ModelManifest manifest;
  public ModelDecoder(ModelManifest manifest){if(!manifest.validate().isEmpty())throw new IllegalArgumentException("Invalid model manifest");this.manifest=manifest;}
  public boolean decode(float[] row,int width,int height,float[] box){
    if(row.length!=6||box.length!=5||width<=0||height<=0)return false;
    for(float value:row)if(!Float.isFinite(value))return false;
    float x=row[0],y=row[1],w=row[2],h=row[3],score=row[4]*row[5];
    if("end_to_end_xyxy_score_class".equals(manifest.decoder)){
      if(row[5]!=0)return false;score=row[4];float left=x,top=y;x=(left+w)/640;y=(top+h)/640;w=(w-left)/320;h=(h-top)/320;
    }
    if(score<.45f||score>1||w<=0||h<=0)return false;
    float scale=Math.min(320f/width,320f/height),px=(320-width*scale)/2,py=(320-height*scale)/2;
    box[0]=Math.max(0,((x-w/2)*320-px)/scale);box[1]=Math.max(0,((y-h/2)*320-py)/scale);box[2]=Math.min(width-1,((x+w/2)*320-px)/scale);box[3]=Math.min(height-1,((y+h/2)*320-py)/scale);box[4]=score;
    return box[2]>box[0]&&box[3]>box[1];
  }
}
