package org.maxbot.core;
import java.util.*;
/** Bounded command lineage. Counts only the first application that actually consumes a frame. */
public final class CausalTiming {
  private static final class Source {long frame,state,output,send; Source(long f,long s,long o,long t){frame=f;state=s;output=o;send=t;}}
  private final LinkedHashMap<Long,Source> commands=new LinkedHashMap<>();
  private final LinkedHashSet<Long> seenFrames=new LinkedHashSet<>();
  private final LatencyStats imageAvailable=new LatencyStats(),policyApplied=new LatencyStats(),informationAge=new LatencyStats();
  private long missing,failed,samples,lastApplied,imageLastApplied;private final long[] window=new long[512];
  public synchronized void sent(long seq,long frame,long state,long output,long send){
    commands.put(seq,new Source(frame,state,output,send));
    if(commands.size()>512){commands.remove(commands.keySet().iterator().next());missing++;}
  }
  public synchronized void applied(long seq,long localApplied,long expiresLocal,boolean clockValid){
    Source s=commands.remove(seq);if(s==null)return;if(!clockValid){missing++;return;}
    if(localApplied>=expiresLocal)failed++;
    if(localApplied<s.send||s.output>localApplied){missing++;return;}
    policyApplied.add((localApplied-s.output)/1e6);informationAge.add((localApplied-s.state)/1e6);window[(int)(samples%512)]=localApplied;samples++;lastApplied=localApplied;
    if(s.frame>0&&seenFrames.add(s.frame)){
      imageLastApplied=localApplied;imageAvailable.add((localApplied-s.frame)/1e6);
      if(seenFrames.size()>4096)seenFrames.remove(seenFrames.iterator().next());
    }
  }
  public synchronized void reset(){commands.clear();seenFrames.clear();imageAvailable.clear();policyApplied.clear();informationAge.clear();samples=missing=failed=lastApplied=imageLastApplied=0;}
  public synchronized double[] imageAvailable(){return imageAvailable.snapshot();}
  public synchronized double[] policyApplied(){return policyApplied.snapshot();}
  public synchronized double[] informationAge(){return informationAge.snapshot();}
  public synchronized int windowSamples(){return policyApplied.count();}
  public synchronized int imageSamples(){return imageAvailable.count();}
  public synchronized double windowMs(){return samples==0?Double.NaN:(lastApplied-window[(int)(samples<512?0:samples%512)])/1e6;}
  public synchronized double applicationAgeMs(long now){return lastApplied==0?Double.NaN:(now-lastApplied)/1e6;}
  public synchronized double imageAgeMs(long now){return imageLastApplied==0?Double.NaN:(now-imageLastApplied)/1e6;}
  public synchronized long samples(){return samples;}
  public synchronized long missing(){return missing;}
  public synchronized int pending(){return commands.size();}
  public synchronized void expire(long now){java.util.Iterator<Source> iterator=commands.values().iterator();while(iterator.hasNext()){Source s=iterator.next();if(now-s.send>500_000_000L){iterator.remove();missing++;}}}
  public synchronized long failed(){return failed;}
}
