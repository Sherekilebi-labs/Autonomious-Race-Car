package org.maxbot.observer;
import org.junit.Test;
import static org.junit.Assert.*;
public final class PreviewGeometryTest {
  @Test public void cameraPixelsKeepTheirProportionsAcrossViewportShapes(){
    for(int[] image:new int[][]{{1280,720},{1920,1080},{1440,1080},{720,1280}})
      for(int[] area:new int[][]{{1000,120},{1000,360},{320,600},{640,480},{200,150}}){
        int[] fitted=PreviewGeometry.fit(image[0],image[1],area[0],area[1]);
        assertTrue(fitted[0]<=area[0]&&fitted[1]<=area[1]);
        // Horizontal and vertical magnification must agree, including rounding to whole pixels.
        assertEquals((double)fitted[0]/image[0],(double)fitted[1]/image[1],1.0/Math.min(image[0],image[1]));
      }
  }
  @Test public void snapshotsPreserveFourByThreeAndPortraitFrames(){
    assertArrayEquals(new int[]{640,480},PreviewGeometry.fit(1440,1080,640,480));
    assertArrayEquals(new int[]{270,480},PreviewGeometry.fit(720,1280,640,480));
    assertArrayEquals(new int[]{0,0},PreviewGeometry.fit(0,720,640,480));
  }
  @Test public void quarterTurnsPreserveCompleteImageAndPixelProportions(){
    for(int sensor:new int[]{0,90,180,270})for(int display:new int[]{0,90,180,270}){
      int[] oriented=PreviewGeometry.orientedSize(1280,720,sensor,display);
      int[] view=PreviewGeometry.fit(oriented[0],oriented[1],1563,879);
      float[] matrix=PreviewGeometry.textureTransform(1280,720,sensor,display,view[0],view[1]);
      for(int x:new int[]{0,view[0]})for(int y:new int[]{0,view[1]}){
        double[] p=point(matrix,x,y);
        assertTrue(p[0]>=-.001&&p[0]<=view[0]+.001);
        assertTrue(p[1]>=-.001&&p[1]<=view[1]+.001);
      }
      double naturalWidth=sensor%180==0?1280:720,naturalHeight=sensor%180==0?720:1280;
      double xScale=Math.hypot(matrix[0],matrix[3])*view[0]/naturalWidth;
      double yScale=Math.hypot(matrix[1],matrix[4])*view[1]/naturalHeight;
      assertEquals(xScale,yScale,.00001);
      assertEquals(0,matrix[0]*matrix[1]+matrix[3]*matrix[4],.00001);
      assertTrue(matrix[0]*matrix[4]-matrix[1]*matrix[3]>0);
    }
  }
  @Test public void displayRotationMapsUpToTheCorrectAxisWithoutMirroring(){
    double[][] directions={{0,-1},{-1,0},{0,1},{1,0}};
    for(int display:new int[]{0,90,180,270}){
      int[] oriented=PreviewGeometry.orientedSize(1280,720,90,display);
      int[] view=PreviewGeometry.fit(oriented[0],oriented[1],1563,879);
      float[] matrix=PreviewGeometry.textureTransform(1280,720,90,display,view[0],view[1]);
      double[] center=point(matrix,view[0]/2.,view[1]/2.);
      double[] up=point(matrix,view[0]/2.,view[1]/2.-view[1]/1280.);
      double dx=up[0]-center[0],dy=up[1]-center[1],length=Math.hypot(dx,dy);
      assertEquals(directions[display/90][0],dx/length,.00001);
      assertEquals(directions[display/90][1],dy/length,.00001);
    }
    assertArrayEquals(new int[]{1280,720},PreviewGeometry.orientedSize(1280,720,90,90));
    assertArrayEquals(new int[]{720,1280},PreviewGeometry.orientedSize(1280,720,90,0));
  }
  private static double[] point(float[] matrix,double x,double y){
    return new double[]{matrix[0]*x+matrix[1]*y+matrix[2],matrix[3]*x+matrix[4]*y+matrix[5]};
  }
}
