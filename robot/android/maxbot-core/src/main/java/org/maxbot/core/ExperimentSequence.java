package org.maxbot.core;

import java.util.List;

/** Monotonic local trial schedule. A lease cannot revive an expired or cancelled trial. */
public final class ExperimentSequence {
  public static final int MAX_PWM = 140, MAX_STEERING_DEG = 12, LEASE_MS = 300;
  public static final class Step {
    public final int durationMs, pwm; public final double steeringDeg;
    public Step(int durationMs, int pwm, double steeringDeg) {
      this.durationMs=durationMs; this.pwm=pwm; this.steeringDeg=steeringDeg;
    }
  }
  private final List<Step> steps;
  private final long startedNs, endsNs;
  private long leaseNs, lastTickNs;
  private boolean cancelled;
  public ExperimentSequence(List<Step> steps, long nowNs) {
    if (steps==null || steps.isEmpty() || steps.size()>120) throw new IllegalArgumentException("1–120 trial steps required");
    int total=0, powered=0, neutral=3000; int direction=0;
    for (Step s: steps) {
      if (s.durationMs<20 || s.durationMs>30000 || Math.abs((long)s.pwm)>MAX_PWM || !Double.isFinite(s.steeringDeg) || Math.abs(s.steeringDeg)>MAX_STEERING_DEG)
        throw new IllegalArgumentException("Trial exceeds duration/PWM/steering bounds");
      total+=s.durationMs;
      if (s.pwm!=0) {
        if (s.durationMs>1000 || powered+s.durationMs>2000) throw new IllegalArgumentException("Powered trial limited to 2 s total, 1 s per segment");
        if(direction!=0 && direction!=Integer.signum(s.pwm) && neutral<3000) throw new IllegalArgumentException("Reversal requires 3 s neutral");
        direction=Integer.signum(s.pwm); powered+=s.durationMs; neutral=0;
      } else neutral+=s.durationMs;
    }
    if(total>60000 || steps.get(steps.size()-1).pwm!=0 || steps.get(steps.size()-1).steeringDeg!=0) throw new IllegalArgumentException("Trial must end neutral within 60 s");
    this.steps=List.copyOf(steps); startedNs=nowNs; lastTickNs=nowNs; endsNs=nowNs+total*1_000_000L; leaseNs=nowNs+LEASE_MS*1_000_000L;
  }
  public synchronized boolean renew(long nowNs) {
    if(cancelled || nowNs>=leaseNs || nowNs>=endsNs){cancelled=true;return false;}
    leaseNs=Math.min(endsNs,nowNs+LEASE_MS*1_000_000L);return true;
  }
  public synchronized Step sample(long nowNs) {
    if(cancelled || nowNs<lastTickNs || nowNs-lastTickNs>100_000_000L || nowNs>=leaseNs || nowNs>=endsNs){cancelled=true;return null;}
    lastTickNs=nowNs;long offset=(nowNs-startedNs)/1_000_000L;
    for(Step s:steps){if(offset<s.durationMs)return s;offset-=s.durationMs;}
    cancelled=true;return null;
  }
  public synchronized boolean completed(long nowNs){return !cancelled && nowNs>=endsNs;}
  public synchronized long deadline(long nowNs){return Math.min(endsNs,Math.min(leaseNs,nowNs+80_000_000L));}
  public synchronized void cancel(){cancelled=true;}
}
