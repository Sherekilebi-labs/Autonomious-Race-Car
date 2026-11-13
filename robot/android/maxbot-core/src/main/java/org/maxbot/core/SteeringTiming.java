package org.maxbot.core;
import java.util.*;
/** Potentiometer response to genuine >=7° applied target steps, entirely in ESP boot time. */
public final class SteeringTiming {
  private final LinkedHashMap<Long,Double> targets=new LinkedHashMap<>();
  private final LatencyStats onset=new LatencyStats(),settling=new LatencyStats();
  private long sensorMs=-1,startMs=-1,settleSince=-1,cancelSequence=-1,lastApplied=-1,censored;
  private double angle,initial,target;private boolean onsetRecorded;
  public synchronized void sent(long seq,double degrees,boolean motion){
    if(!motion){cancelSequence=seq;if(startMs>=0)censored++;startMs=-1;targets.clear();return;}
    targets.put(seq,degrees);if(targets.size()>512)targets.remove(targets.keySet().iterator().next());
  }
  public synchronized void applied(long seq,long appliedMs){
    Double next=targets.remove(seq);if(next==null||seq<=cancelSequence||seq<=lastApplied)return;lastApplied=seq;
    if(startMs>=0&&Math.abs(next-target)<.01)return;
    if(startMs>=0){censored++;startMs=-1;}
    if(sensorMs<0||appliedMs<sensorMs||appliedMs-sensorMs>60||Math.abs(next-angle)<7)return;
    initial=angle;target=next;startMs=appliedMs;settleSince=-1;onsetRecorded=false;
  }
  public synchronized void sample(long timeMs,double actual){
    if(!Double.isFinite(actual)||timeMs<=sensorMs)return;
    if(startMs>=0&&sensorMs>=0&&timeMs-sensorMs>60){censored++;startMs=-1;}
    sensorMs=timeMs;angle=actual;if(startMs<0||timeMs<startMs)return;
    double direction=Math.signum(target-initial);
    if(!onsetRecorded&&(actual-initial)*direction>=1){onset.add(timeMs-startMs);onsetRecorded=true;}
    if(Math.abs(actual-target)<=3.5){if(settleSince<0)settleSince=timeMs;if(timeMs-settleSince>=100){settling.add(settleSince-startMs);startMs=-1;}}
    else settleSince=-1;
  }
  public synchronized double[] onset(){return onset.snapshot();}
  public synchronized double[] settling(){return settling.snapshot();}
  public synchronized int onsetSamples(){return onset.count();}
  public synchronized int settlingSamples(){return settling.count();}
  public synchronized long censored(){return censored;}
  public synchronized void reset(){targets.clear();onset.clear();settling.clear();sensorMs=startMs=settleSince=cancelSequence=lastApplied=-1;censored=0;}
}
