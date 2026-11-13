#include <jni.h>
#include <math.h>
#include <stdint.h>
static double clamp(double x,double a,double b){return fmax(a,fmin(b,x));}
JNIEXPORT void JNICALL Java_org_maxbot_app_ConeDetector_prepare(JNIEnv* env,jclass cls,jobject destination,
    jbyteArray source,jint width,jint height,jint kind,jfloat scale,jint zero){
  (void)cls;
  unsigned char* dst=(*env)->GetDirectBufferAddress(env,destination);
  jsize size=(*env)->GetArrayLength(env,source);
  if(!dst||width<=0||height<=0||width>1920||height>1080||width%2||height%2||kind<0||kind>2||(kind!=0&&(!isfinite(scale)||scale<=0))||size<width*height*3/2||(*env)->GetDirectBufferCapacity(env,destination)<320*320*3*(kind==0?4:1))return;
  jbyte* raw=(*env)->GetByteArrayElements(env,source,NULL);if(!raw)return;
  unsigned char* yuv=(unsigned char*)raw;
  float ratio=fminf(320.f/width,320.f/height),px=(320-width*ratio)/2,py=(320-height*ratio)/2;
  int index=0;
  for(int y=0;y<320;y++)for(int x=0;x<320;x++){
    int sx=(int)((x-px)/ratio),sy=(int)((y-py)/ratio);float rgb[3]={114,114,114};
    if(x>=px&&y>=py&&sx>=0&&sy>=0&&sx<width&&sy<height){
      int cy=yuv[sy*width+sx],uv=(sy/2)*(width/2)+sx/2;
      int u=yuv[width*height+uv]-128,v=yuv[width*height*5/4+uv]-128;
      rgb[0]=cy+1.402f*v;rgb[1]=cy-.344136f*u-.714136f*v;rgb[2]=cy+1.772f*u;
    }
    for(int c=0;c<3;c++){
      float val=clamp(rgb[c],0,255)/255.f;
      if(kind==0)((float*)dst)[index++]=val;
      else {int q=(int)roundf(val/scale)+zero;dst[index++]=(unsigned char)(kind==1?clamp(q,0,255):(signed char)clamp(q,-128,127));}
    }
  }
  (*env)->ReleaseByteArrayElements(env,source,raw,JNI_ABORT);
}


JNIEXPORT void JNICALL Java_org_maxbot_app_ArCamera_packPlanes(JNIEnv* env,jclass cls,jbyteArray target,jobject y,jobject u,jobject v,jint width,jint height,jint yr,jint yp,jint ur,jint up,jint vr,jint vp){
  (void)cls;
  if(width<=0||height<=0||width%2||height%2||width>1920||height>1080||(*env)->GetArrayLength(env,target)<width*height*3/2)return;
  jobject planes[3]={y,u,v};int rows[3]={yr,ur,vr},pixels[3]={yp,up,vp};
  unsigned char* input[3];
  for(int p=0;p<3;p++){
    int w=p?width/2:width,h=p?height/2:height;
    input[p]=(*env)->GetDirectBufferAddress(env,planes[p]);
    if(!input[p]||rows[p]<=0||pixels[p]<=0||(int64_t)(h-1)*rows[p]+(int64_t)(w-1)*pixels[p]>=(*env)->GetDirectBufferCapacity(env,planes[p]))return;
  }
  jbyte* out=(*env)->GetByteArrayElements(env,target,0);if(!out)return;int offset=0;
  for(int p=0;p<3;p++){int w=p?width/2:width,h=p?height/2:height;for(int row=0;row<h;row++)for(int col=0;col<w;col++)out[offset++]=input[p][row*rows[p]+col*pixels[p]];}
  (*env)->ReleaseByteArrayElements(env,target,out,0);
}
