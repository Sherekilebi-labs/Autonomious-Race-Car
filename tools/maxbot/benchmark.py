#!/usr/bin/env python3
"""Summarize measured event windows; absent capture/physical timings remain unavailable."""
import argparse,json,math,pathlib
from collections import Counter

def percentile(values):
    values=sorted(v for v in values if isinstance(v,(int,float)) and math.isfinite(v) and v>=0)
    return None if not values else {'p50':values[(len(values)-1)//2],'p95':values[math.ceil(.95*len(values))-1],'p99':values[math.ceil(.99*len(values))-1],'samples':len(values)}
def summarize(events):
    commands={};policy=[];information=[];frames=[];consumed=set();missing=0;failed=0;loss=0;thermal=Counter();start=None;end=None;steering_onset=None;steering_settling=None;steering_definition=None
    for e in events:
        timestamp=e.get('phone_received_ns',e.get('time_ns'))
        if timestamp is not None:
            t=int(timestamp);start=t if start is None else min(start,t);end=t if end is None else max(end,t)
        if e.get('type')=='command_sent':commands[(e.get('boot'),e.get('session'),e.get('seq'))]=e
        if e.get('type')=='telemetry':
            thermal[str(e.get('thermal_limited','unknown'))]+=1;loss=max(loss,e.get('recording_drops',0))
            def recorded(values,count):
                return {'p50':values[0],'p95':values[1],'p99':values[2],'samples':count} if isinstance(values,list) and len(values)==3 and count>0 and all(isinstance(v,(int,float)) and math.isfinite(v) for v in values) else None
            steering_onset=recorded(e.get('steering_onset_ms'),e.get('steering_onset_samples',0)) or steering_onset
            steering_settling=recorded(e.get('steering_settling_ms'),e.get('steering_settling_samples',0)) or steering_settling
            steering_definition=e.get('steering_timing_definition',steering_definition)
        if e.get('type')=='applied':
            key=(e.get('boot'),e.get('session'),e.get('seq'));source=commands.pop(key,None)
            # ESP application age from a command source requires an explicit bounded clock map.
            applied=e.get('mapped_phone_applied_ns')
            if source is None or applied is None or e.get('clock_uncertainty_ms') is None:missing+=1;continue
            applied=int(applied);state=int(source['source_state']);frame=int(source['source_frame']);output=int(source.get('policy_output_ns',0))
            if output>0:policy.append((applied-output)/1e6)
            if state>0:information.append((applied-state)/1e6)
            if frame>0 and frame not in consumed:frames.append((applied-frame)/1e6);consumed.add(frame)
            failed+=int(e['applied_ms']>=e['expires_ms'])
    return {'schemaVersion':1,'durationS':None if start is None or end is None else (end-start)/1e9,'captureToPwm':None,'captureStatus':'Unavailable until exposure timestamp mapping is validated','imageAvailableToPwmMs':percentile(frames),'policyToPwmMs':percentile(policy),'informationAgeMs':percentile(information),'missingApplicationsOrClockMappings':missing+len(commands),'failedDeadlines':failed,'recordingLoss':loss,'thermalSamples':dict(thermal),'steeringOnsetMs':steering_onset,'steeringSettlingMs':steering_settling,'steeringDefinition':steering_definition,'tractionPhysicalLatencyMs':None,'deviceBenchmarkComplete':False,'physicallyValidated':False}
def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('events',type=pathlib.Path);p.add_argument('--output',type=pathlib.Path);a=p.parse_args()
    with a.events.open() as f:report=summarize(json.loads(line) for line in f if line.strip())
    text=json.dumps(report,indent=2,allow_nan=False)+'\n'
    if a.output:a.output.write_text(text)
    else:print(text,end='')
if __name__=='__main__':main()
