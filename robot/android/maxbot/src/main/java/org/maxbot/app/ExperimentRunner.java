package org.maxbot.app;

import java.util.ArrayList;
import org.json.*;
import org.maxbot.core.ExperimentSequence;

/** Executes on the control thread; network calls only change a bounded lease or cancel. */
final class ExperimentRunner {
  interface Host {
    void arm(boolean autonomous); void stop(String reason); boolean armed(boolean autonomous); boolean healthy(); int thermalStatus();
    boolean startAutonomy(long now); String autonomyState();
    boolean input(double angle,int pwm,long now,long deadline);
    void event(JSONObject event);
  }
  private final Host host;
  private ExperimentSequence sequence;
  private java.util.List<ExperimentSequence.Step> steps;
  private String id="";private boolean motion,started,autonomous,allowSevereThermal;private long requestedNs,deadlineNs,lastOrdinal=-1;private int lastPwm=10000;private double lastAngle=10000;
  ExperimentRunner(Host host){this.host=host;}
  synchronized boolean acceptsOutput(int pwm,double steeringDeg,long now){
    if(active()&&(Math.abs((long)pwm)>(allowSevereThermal?110:140)||!Double.isFinite(steeringDeg)||Math.abs(steeringDeg)>(allowSevereThermal?10:autonomous?26:12))){cancel("output_envelope_exceeded",now);return false;}return true;
  }
  synchronized boolean active(){return !id.isEmpty();}
  synchronized boolean allowsSevereThermal(){return active()&&allowSevereThermal;}
  synchronized boolean thermalAllowed(int state){return state>=0&&state<(allowsSevereThermal()?4:3);}
  private static void validateSevereTrial(String recipe,java.util.List<ExperimentSequence.Step> steps){
    if(!java.util.Set.of("camera_clock","warning","video","forward_onset","reverse_onset","steering","speed_stop","turn").contains(recipe))throw new IllegalArgumentException("Severe thermal allowance is limited to timing experiments");
    int poweredMs=0;
    for(ExperimentSequence.Step s:steps){
      if(Math.abs((long)s.pwm)>110||Math.abs(s.steeringDeg)>10)throw new IllegalArgumentException("Severe thermal trial limited to PWM 110 and 10 degrees");
      if(s.pwm!=0)poweredMs+=s.durationMs;
    }
    if(poweredMs>100)throw new IllegalArgumentException("Severe thermal trial limited to 100 ms powered total");
  }
  synchronized void receive(JSONObject m,long now)throws Exception {
    String operation=m.getString("operation");
    if("abort".equals(operation)){cancel("coordinator_abort",now);return;}
    String incoming=m.getString("experiment_id");long ordinal=m.getLong("ordinal");
    long deadline=m.getLong("mapped_deadline_ns");
    if(deadline<=now||deadline>now+300_000_000L)throw new IllegalArgumentException("Expired experiment lease");
    if("lease".equals(operation)){
      if(!incoming.equals(id)||ordinal<=lastOrdinal)return;
      if(now>=deadlineNs){cancel("lease_expired",now);return;}
      lastOrdinal=ordinal;deadlineNs=deadline;
      if(sequence!=null&&!sequence.completed(now)&&!sequence.renew(now)){cancel("local_schedule_expired",now);}
      return;
    }
    if(!"start".equals(operation)||active())throw new IllegalStateException("Stop the current experiment first");
    JSONArray rows=m.getJSONArray("steps");ArrayList<ExperimentSequence.Step> parsed=new ArrayList<>();
    boolean powered=false;
    for(int i=0;i<rows.length();i++){JSONObject s=rows.getJSONObject(i);int pwm=s.getInt("pwm");double angle=s.getDouble("steering_deg");
      if(s.getDouble("pwm")!=pwm||s.getDouble("duration_ms")!=s.getInt("duration_ms"))throw new IllegalArgumentException("Integer PWM and duration required");
      parsed.add(new ExperimentSequence.Step(s.getInt("duration_ms"),pwm,angle));powered|=pwm!=0||angle!=0;
    }
    new ExperimentSequence(parsed,now); // Validate before granting any arm request.
    Object thermalOption=m.opt("allow_severe_thermal");
    if(thermalOption!=null&&!(thermalOption instanceof Boolean))throw new IllegalArgumentException("Boolean thermal allowance required");
    boolean allowSevere=Boolean.TRUE.equals(thermalOption);
    if(allowSevere)validateSevereTrial(m.optString("recipe"),parsed);
    int thermal=host.thermalStatus();
    if(thermal<0||thermal>=(allowSevere?4:3))throw new IllegalStateException("Phone thermal status "+thermal+" exceeds trial policy");
    autonomous="slalom".equals(m.optString("recipe"));allowSevereThermal=allowSevere;
    id=incoming;steps=parsed;motion=powered||autonomous;requestedNs=now;deadlineNs=deadline;lastOrdinal=ordinal;started=false;lastPwm=10000;lastAngle=10000;
    emit("accepted",now,"");if(motion)host.arm(autonomous);
  }
  synchronized void tick(long now){
    if(!active())return;
    if(now>=deadlineNs){cancel("lease_expired",now);return;}
    int thermal=host.thermalStatus();
    if(!thermalAllowed(thermal)){cancel("thermal_status_"+thermal,now);return;}
    if(!host.healthy()){cancel("health_lost",now);return;}
    if(!started){
      if(motion&&!host.armed(autonomous)){if(now-requestedNs>1_500_000_000L)cancel("arm_ack_timeout",now);return;}
      sequence=new ExperimentSequence(steps,now);
      if(autonomous&&!host.startAutonomy(now)){cancel("autonomy_preflight_rejected",now);return;}
      started=true;emit("started",now,"");
    }
    if(autonomous){
      String state=host.autonomyState();
      if("FINISHED".equals(state)){finish("completed",now,"contact_review_required");return;}
      if(!"RUN".equals(state)){cancel("autonomy_stopped_"+state,now);return;}
    }
    if(sequence.completed(now)){if(autonomous)cancel("autonomy_duration_limit",now);else finish("completed",now,"");return;}
    ExperimentSequence.Step step=sequence.sample(now);
    if(step==null){cancel("local_deadline_missed",now);return;}
    if(motion&&!autonomous&&!host.input(step.steeringDeg,step.pwm,now,Math.min(deadlineNs,sequence.deadline(now)))){cancel("supervisor_rejected",now);return;}
    if(step.pwm!=lastPwm||step.steeringDeg!=lastAngle){lastPwm=step.pwm;lastAngle=step.steeringDeg;emit("step",now,"");}
  }
  synchronized void cancel(String reason,long now){if(active())finish("aborted",now,reason);}
  private void finish(String state,long now,String reason){if(sequence!=null)sequence.cancel();host.stop(reason.isEmpty()?"Experiment complete":reason);emit(state,now,reason);id="";sequence=null;steps=null;allowSevereThermal=false;}
  private void emit(String state,long now,String reason){try{host.event(new JSONObject().put("type","experiment_status").put("experiment_id",id).put("status",state).put("phone_ns",Long.toString(now)).put("reason",reason).put("thermal_status",host.thermalStatus()).put("allow_severe_thermal",allowSevereThermal).put("pwm",lastPwm==10000?0:lastPwm).put("steering_deg",lastAngle==10000?0:lastAngle));}catch(JSONException ignored){}}
}
