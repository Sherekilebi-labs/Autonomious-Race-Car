package org.maxbot.observer;

/** Fit the entire camera image inside its available area without changing pixel proportions. */
final class PreviewGeometry {
  private PreviewGeometry() {}
  private static int rotation(int degrees){
    int normalized=((degrees%360)+360)%360;
    if(normalized%90!=0)throw new IllegalArgumentException("Camera/display rotations must be quarter turns");
    return normalized;
  }
  static int[] orientedSize(int width,int height,int sensorDegrees,int displayDegrees){
    int relative=rotation(sensorDegrees-displayDegrees);
    return relative%180==0?new int[]{width,height}:new int[]{height,width};
  }
  /**
   * TextureView already applies SENSOR_ORIENTATION to its underlying camera texture.
   * Undo the default nonuniform stretch of that natural-display texture, rotate by
   * minus the display rotation, then apply one uniform fit scale about the center.
   */
  static float[] textureTransform(int width,int height,int sensorDegrees,int displayDegrees,int viewWidth,int viewHeight){
    if(Math.min(Math.min(width,height),Math.min(viewWidth,viewHeight))<=0)throw new IllegalArgumentException("Positive image/view dimensions required");
    int sensor=rotation(sensorDegrees),display=rotation(displayDegrees);
    double naturalWidth=sensor%180==0?width:height,naturalHeight=sensor%180==0?height:width;
    double rotatedWidth=display%180==0?naturalWidth:naturalHeight,rotatedHeight=display%180==0?naturalHeight:naturalWidth;
    double scale=Math.min(viewWidth/rotatedWidth,viewHeight/rotatedHeight);
    int[] cosine={1,0,-1,0},sine={0,-1,0,1};int quadrant=display/90;
    double a=scale*cosine[quadrant]*naturalWidth/viewWidth,b=-scale*sine[quadrant]*naturalHeight/viewHeight;
    double c=scale*sine[quadrant]*naturalWidth/viewWidth,d=scale*cosine[quadrant]*naturalHeight/viewHeight;
    double tx=viewWidth/2.-a*viewWidth/2.-b*viewHeight/2.,ty=viewHeight/2.-c*viewWidth/2.-d*viewHeight/2.;
    return new float[]{(float)a,(float)b,(float)tx,(float)c,(float)d,(float)ty,0,0,1};
  }
  static int[] fit(int imageWidth,int imageHeight,int availableWidth,int availableHeight){
    if(Math.min(Math.min(imageWidth,imageHeight),Math.min(availableWidth,availableHeight))<=0)return new int[]{0,0};
    double scale=Math.min((double)availableWidth/imageWidth,(double)availableHeight/imageHeight);
    return new int[]{Math.max(1,(int)Math.round(imageWidth*scale)),Math.max(1,(int)Math.round(imageHeight*scale))};
  }
}
