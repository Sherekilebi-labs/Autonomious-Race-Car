package org.maxbot.core;

/** Four timestamp NTP-style bound. A reset, long gap or excessive drift invalidates the map. */
public final class ClockSync {
  private String boot="";
  private long local,remote;
  private double offset,uncertainty=Double.POSITIVE_INFINITY,drift;
  public synchronized boolean sample(String id,long t1,long t2,long t3,long t4) {
    if(id==null||t1<0||t2<0||t3<t2||t4<t1||t4-t1>100||t3-t2>t4-t1)return false;
    double next=((t2-t1)+(t3-t4))/2.0, bound=((t4-t1)-(t3-t2))/2.0+1;
    if(!boot.equals(id)||t4<local||t3<remote){invalidate();boot=id;}
    if(local!=0&&t4>local){
      if(Math.abs(next-offset)>uncertainty+bound+(t4-local)*.002){invalidate();boot=id;local=t4;remote=t3;return false;}
    }
    // A slower round trip cannot tighten the clock bound. Keep a better recent
    // sample with its original age/drift allowance; discarded samples never renew it.
    if(local!=0 && t4>=local && t4-local<=5000 && bound>=uncertainty+(t4-local)*.002)return true;
    if(local!=0&&t4-local>500)drift=(next-offset)/(t4-local);
    offset=next;uncertainty=bound;local=t4;remote=t3;return true;
  }
  public synchronized double uncertaintyMs(long now){return local==0||now<local||now-local>5000?Double.POSITIVE_INFINITY:uncertainty+(now-local)*.002;}
  public synchronized long toRemote(long time,long now){return Math.round(time+offset);}
  public synchronized long toLocal(long time){return Math.round(time-offset);}
  public synchronized double driftPpm(){return drift*1e6;}
  public synchronized void invalidate(){local=remote=0;uncertainty=Double.POSITIVE_INFINITY;drift=0;}
}
