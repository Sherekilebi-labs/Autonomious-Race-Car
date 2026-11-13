package org.maxbot.core;
/** Rigid-body lever-arm correction; used for logging, never double-integrated as a speed sensor. */
public final class InertialTransform {
  private InertialTransform(){}
  public static double[] rotate(double[] matrix,double[] vector){if(!Calibration.validRotation(matrix)||!Calibration.vector(vector))throw new IllegalArgumentException("Measured sensor transform required");double[] out=new double[3];for(int i=0;i<3;i++)for(int j=0;j<3;j++)out[i]+=matrix[3*i+j]*vector[j];return out;}
  private static double[] cross(double[] a,double[] b){return new double[]{a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]};}
  public static double[] axleAcceleration(double[] acceleration,double[] gravity,double[] omega,double[] alpha,double[] lever){
    for(double[] v:new double[][]{acceleration,gravity,omega,alpha,lever})if(!Calibration.vector(v))throw new IllegalArgumentException("Unmeasured inertial geometry");
    double[] tangential=cross(alpha,lever),centripetal=cross(omega,cross(omega,lever)),out=new double[3];
    for(int i=0;i<3;i++)out[i]=acceleration[i]-gravity[i]-tangential[i]-centripetal[i];return out;
  }
}
