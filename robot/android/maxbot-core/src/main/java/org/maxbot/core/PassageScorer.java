package org.maxbot.core;
import java.util.List;
/** Ordered swept-footprint passage. Finish timestamp is distinct from subsequent rest. */
public final class PassageScorer {
  private Geometry.Pose previous;private int passed;private boolean entered;private long finishNs;private String fault="";
  public int passed(){return passed;}public long finishNs(){return finishNs;}public String fault(){return fault;}
  public void reset(){previous=null;passed=0;entered=false;finishNs=0;fault="";}
  public boolean update(Geometry.Pose next,CourseMap map,Calibration c,double uncertainty){
    if(!fault.isEmpty())return false;
    if(next==null||!next.finite()||map==null||!Double.isFinite(uncertainty)||uncertainty<0){fault="Invalid scoring state";return false;}
    if(previous==null){previous=next;return true;}
    double distance=previous.position().distance(next.position()),turn=Geometry.angle(next.yaw-previous.yaw);
    if(next.timeNs<=previous.timeNs||distance>.2||Math.abs(turn)>.4){fault="Unbounded scoring propagation";return false;}
    int count=Math.max(1,(int)Math.ceil(Math.max(distance/.005,Math.abs(turn)/.01)));List<CourseMap.Cone> cones=map.cones();
    double radius=Math.hypot(c.car.wheelbaseM+c.car.frontOverhangM,c.car.widthM/2);double bound=(distance+radius*Math.abs(turn))/count;
    for(int i=1;i<=count;i++){
      double t=(double)i/count;Geometry.Pose p=new Geometry.Pose(previous.x+t*(next.x-previous.x),previous.y+t*(next.y-previous.y),previous.yaw+t*turn,next.speed,previous.timeNs+(long)(t*(next.timeNs-previous.timeNs)));
      for(CourseMap.Cone cone:cones)if(Geometry.footprintDistance(p,cone.point(),c)<c.cones.baseDiameterM/2+c.limits.clearanceM+uncertainty+bound){fault="Swept clearance violation; contact review required";return false;}
      if(passed>=cones.size())continue;
      Geometry.Point cone=Geometry.local(map.start(),cones.get(passed).point());
      double min=ReferenceTrajectory.minimumProjection(p,map.start(),c),max=-Double.MAX_VALUE,minY=Double.MAX_VALUE,maxY=-Double.MAX_VALUE;
      for(double x:new double[]{-c.car.rearOverhangM,c.car.wheelbaseM+c.car.frontOverhangM})for(double y:new double[]{-c.car.widthM/2,c.car.widthM/2}){
        Geometry.Point q=Geometry.local(map.start(),Geometry.world(p,x,y));max=Math.max(max,q.x);minY=Math.min(minY,q.y);maxY=Math.max(maxY,q.y);
      }
      double r=c.cones.baseDiameterM/2+uncertainty;
      if(max>=cone.x&&min<=cone.x){
        Geometry.Point[] polygon=new Geometry.Point[4];double front=c.car.wheelbaseM+c.car.frontOverhangM,rear=-c.car.rearOverhangM,half=c.car.widthM/2;
        double[][] corners={{rear,-half},{front,-half},{front,half},{rear,half}};
        for(int j=0;j<4;j++)polygon[j]=Geometry.local(map.start(),Geometry.world(p,corners[j][0],corners[j][1]));
        double low=Double.POSITIVE_INFINITY,high=Double.NEGATIVE_INFINITY;
        for(int j=0;j<4;j++){Geometry.Point a=polygon[j],b=polygon[(j+1)%4];if(cone.x>=Math.min(a.x,b.x)-1e-9&&cone.x<=Math.max(a.x,b.x)+1e-9&&Math.abs(b.x-a.x)>1e-9){double y=a.y+(b.y-a.y)*(cone.x-a.x)/(b.x-a.x);low=Math.min(low,y);high=Math.max(high,y);}}
        boolean correct=passed%2==0?high<cone.y-r:low>cone.y+r;
        if(!correct){fault="Wrong side during footprint passage";return false;}entered=true;
      }
      if(min>cone.x+r){
        if(!entered){fault="Skipped cone passage";return false;}passed++;entered=false;
        if(passed==4)finishNs=p.timeNs;
      }
    }
    previous=next;return true;
  }
}
