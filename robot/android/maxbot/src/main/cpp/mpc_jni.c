#include <jni.h>
#include <math.h>
#include <time.h>
#include "osqp.h"
#include "workspace.h"
#include "mpc_structure.h"

#define N 20
#define NV 120
#define NC 200
static OSQPFloat values[MPC_A_NNZ],q[NV],lower[NC],upper[NC];
static double now_ms(void){struct timespec t;clock_gettime(CLOCK_MONOTONIC,&t);return t.tv_sec*1000.+t.tv_nsec/1e6;}
static double clamp(double x,double a,double b){return fmax(a,fmin(b,x));}

JNIEXPORT jdoubleArray JNICALL Java_org_maxbot_app_NativeMpc_solve(JNIEnv* env,jclass cls,
    jdouble ey,jdouble epsi,jdouble speed,jdouble steering,jdouble wheelbase,jdouble rate,
    jdouble accel,jdouble brake,jdouble left,jdouble right,jdoubleArray speeds,jdoubleArray curves){
  (void)cls;
  jdouble result[3]={0,0,0};
  if((*env)->GetArrayLength(env,speeds)!=N||(*env)->GetArrayLength(env,curves)!=N||wheelbase<=0||rate<=0)goto done;
  jdouble v[N],kappa[N];(*env)->GetDoubleArrayRegion(env,speeds,0,N,v);(*env)->GetDoubleArrayRegion(env,curves,0,N,kappa);
  double initial[4]={ey,epsi,speed,steering};
  for(int i=0;i<4;i++)if(!isfinite(initial[i]))goto done;
  for(int i=0;i<N;i++)if(!isfinite(v[i])||!isfinite(kappa[i])||v[i]<0)goto done;
  for(int col=0;col<NV;col++){
    for(int idx=MPC_A_COLS[col];idx<MPC_A_COLS[col+1];idx++){
      int row=MPC_A_ROWS[idx];
      double a=0;
      if(row>=80)a=(row-80==col)?1:0;
      else {
        int step=row/4,r=row%4;
        if(col<80){
          int cs=col/4,c=col%4;
          if(cs==step&&r==c)a=1;
          if(cs==step-1){
            if(r==c)a=-1;
            if(r==0&&c==1)a=-v[step]*.05;
            if(r==1&&c==3){double delta=atan(wheelbase*kappa[step]);a=-v[step]/wheelbase/(cos(delta)*cos(delta))*.05;}
          }
        }else if((col-80)/2==step){if(r==2&&(col-80)%2==1)a=-.05;if(r==3&&(col-80)%2==0)a=-.05;}
      }
      values[idx]=(OSQPFloat)a;
    }
  }
  for(int i=0;i<NV;i++){q[i]=0;lower[80+i]=-1e6;upper[80+i]=1e6;}
  for(int step=0;step<N;step++){
    double delta=atan(wheelbase*kappa[step]);
    double gain=v[step]/wheelbase/(cos(delta)*cos(delta))*.05;
    double disturbance=v[step]/wheelbase*(tan(delta)-delta/(cos(delta)*cos(delta)))*.05-v[step]*kappa[step]*.05;
    for(int j=0;j<4;j++)lower[4*step+j]=upper[4*step+j]=0;
    lower[4*step+1]=upper[4*step+1]=disturbance;
    if(step==0){
      lower[0]=upper[0]=ey+v[0]*epsi*.05;
      lower[1]=upper[1]=epsi+gain*steering+disturbance;
      lower[2]=upper[2]=speed;
      lower[3]=upper[3]=steering;
    }
    q[4*step+2]=(OSQPFloat)(-3*v[step]);q[4*step+3]=(OSQPFloat)(-.5*delta);
    lower[80+4*step+2]=0;upper[80+4*step+2]=fmax(v[step],speed)+.05;
    lower[80+4*step+3]=right;upper[80+4*step+3]=left;
    lower[160+2*step]=-rate;upper[160+2*step]=rate;
    lower[160+2*step+1]=-brake;upper[160+2*step+1]=accel;
  }
  double start=now_ms();
  if(osqp_update_data_mat(&solver,NULL,NULL,0,values,NULL,MPC_A_NNZ)!=0)goto done;
  if(osqp_update_data_vec(&solver,q,lower,upper)!=0)goto done;
  osqp_solve(&solver);
  if(now_ms()-start>8 || solver.info->status_val!=OSQP_SOLVED)goto done;
  if(solver.solution&&solver.solution->x){
    result[0]=clamp(steering+solver.solution->x[80]*.02,right,left);
    result[1]=clamp(solver.solution->x[81],-brake,accel);result[2]=1;
    if(!isfinite(result[0]+result[1]))result[2]=0;
  }
done:;
  jdoubleArray output=(*env)->NewDoubleArray(env,3);if(output)(*env)->SetDoubleArrayRegion(env,output,0,3,result);return output;
}
