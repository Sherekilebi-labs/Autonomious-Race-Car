package org.maxbot.observer;

import android.content.Context;
import android.graphics.Matrix;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.TextureView;

/** Fit and orient TextureView's sensor-upright texture to the actual display, without mirroring. */
final class PreviewTexture extends TextureView {
  private int imageWidth=1280,imageHeight=720,sensorDegrees;
  private DisplayManager displays;
  private final DisplayManager.DisplayListener displayListener=new DisplayManager.DisplayListener(){
    public void onDisplayAdded(int id){}
    public void onDisplayRemoved(int id){}
    public void onDisplayChanged(int id){Display display=getDisplay();if(display!=null&&display.getDisplayId()==id)refreshOrientation();}
  };
  PreviewTexture(Context context){super(context);}
  int displayDegrees(){Display display=getDisplay();return display==null?0:display.getRotation()*90;}
  void imageSize(int width,int height,int orientation){
    if(width<=0||height<=0)return;
    imageWidth=width;imageHeight=height;sensorDegrees=orientation;refreshOrientation();
  }
  void refreshOrientation(){requestLayout();applyOrientation();}
  private void applyOrientation(){
    if(getWidth()<=0||getHeight()<=0)return;
    Matrix transform=new Matrix();
    transform.setValues(PreviewGeometry.textureTransform(imageWidth,imageHeight,sensorDegrees,displayDegrees(),getWidth(),getHeight()));
    setTransform(transform);
  }
  @Override protected void onMeasure(int widthSpec,int heightSpec){
    int[] image=PreviewGeometry.orientedSize(imageWidth,imageHeight,sensorDegrees,displayDegrees());
    int[] size=PreviewGeometry.fit(image[0],image[1],MeasureSpec.getSize(widthSpec),MeasureSpec.getSize(heightSpec));
    setMeasuredDimension(size[0],size[1]);
  }
  @Override protected void onSizeChanged(int width,int height,int oldWidth,int oldHeight){super.onSizeChanged(width,height,oldWidth,oldHeight);applyOrientation();}
  @Override protected void onAttachedToWindow(){
    super.onAttachedToWindow();displays=(DisplayManager)getContext().getSystemService(Context.DISPLAY_SERVICE);
    displays.registerDisplayListener(displayListener,new Handler(Looper.getMainLooper()));
  }
  @Override protected void onDetachedFromWindow(){if(displays!=null)displays.unregisterDisplayListener(displayListener);super.onDetachedFromWindow();}
}
