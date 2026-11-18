#!/usr/bin/env python3
"""Reviewed dynamics fitting and bounded cone-slalom search. Outputs proposals, never activates them."""
import argparse, bisect, hashlib, json, math, pathlib, random, statistics

DEFAULT={'l0':.25,'kv':.3,'ldMin':.18,'ldMax':.6,'kr':.05,'kp':40.,'ki':20.,'maxCrossTrackM':.12}
BOUNDS={'l0':(.18,.35),'kv':(.15,.45),'kr':(.02,.10),'kp':(20.,60.),'ki':(0.,30.)}

def finite(x):return type(x) in (int,float) and math.isfinite(x)
def quantile(xs,q):return sorted(xs)[max(0,math.ceil(q*len(xs))-1)] if xs else None
def solve(matrix,values):
    a=[list(row)+[value] for row,value in zip(matrix,values)];n=len(values)
    for i in range(n):
        pivot=max(range(i,n),key=lambda k:abs(a[k][i]));a[i],a[pivot]=a[pivot],a[i]
        if abs(a[i][i])<1e-10:raise ValueError('Insufficient independent excitation for dynamics fit')
        scale=a[i][i];a[i]=[x/scale for x in a[i]]
        for j in range(n):
            if i!=j:
                scale=a[j][i];a[j]=[x-scale*y for x,y in zip(a[j],a[i])]
    return [a[i][-1] for i in range(n)]
def regression(rows):
    weights=[1.]*len(rows);coef=None
    for _ in range(5):
        matrix=[[sum(w*r[i]*r[j] for w,(r,y) in zip(weights,rows)) for j in range(3)] for i in range(3)]
        values=[sum(w*r[i]*y for w,(r,y) in zip(weights,rows)) for i in range(3)];coef=solve(matrix,values)
        errors=[y-sum(a*b for a,b in zip(r,coef)) for r,y in rows];scale=max(.01,1.4826*statistics.median(abs(e-statistics.median(errors)) for e in errors));weights=[min(1,1.345*scale/max(abs(e),1e-12)) for e in errors]
    return coef,statistics.mean(e*e for e in errors)
def delayed(samples,t,key):
    index=bisect.bisect_right([s['time_s'] for s in samples],t)-1
    return samples[max(0,index)][key]
def fit_model(maneuvers):
    train=[m for m in maneuvers if m['split']=='train'];valid=[m for m in maneuvers if m['split']=='validation']
    if len(train)<4 or len(valid)<2:raise ValueError('At least four training and two independent validation maneuvers required')
    if set(m['session'] for m in train)&set(m['session'] for m in valid):raise ValueError('Training and validation sessions overlap')
    for m in maneuvers:
        if m.get('reviewed') is not True or not finite(m.get('alignment_uncertainty_ms')) or not 0<=m['alignment_uncertainty_ms']<=20:raise ValueError('Reviewed motion and <=20 ms timestamp alignment required')
        rows=m['samples']
        if len(rows)<30:raise ValueError('Maneuver too short for held-out fitting')
        for i,s in enumerate(rows):
            if not all(finite(s.get(k)) for k in ['time_s','pwm','speed_mps','target_deg','steering_deg']):raise ValueError('Finite time/PWM/speed/steering samples required')
            if abs(s['pwm'])>140 or abs(s['speed_mps'])>.4:raise ValueError('Dataset exceeds initial measured envelope')
            if i and not .005<=s['time_s']-rows[i-1]['time_s']<=.1:raise ValueError('Sample gaps outside 5–100 ms must be split/censored')
    levels=set(round(s['pwm']/10)*10 for m in train for s in m['samples'] if s['pwm']>0)
    if len(levels)<3:raise ValueError('At least three distinct forward PWM levels required; one onset recipe cannot identify feedforward')
    best=None
    for delay_index in range(16):
        delay=delay_index*.02;rows=[]
        for m in train:
            ss=m['samples']
            for a,b in zip(ss,ss[1:]):
                pwm=delayed(ss,a['time_s']-delay,'pwm')
                if pwm<=0 or a['speed_mps']<.015:continue
                rows.append(([pwm,a['speed_mps'],1.],(b['speed_mps']-a['speed_mps'])/(b['time_s']-a['time_s'])))
        if len(rows)<60:raise ValueError('Too few powered moving samples for dynamics fitting')
        try:coef,loss=regression(rows)
        except ValueError:continue
        if coef[0]<=0 or coef[1]>=0:continue
        tau=-1/coef[1];gain=coef[0]*tau;deadband=-coef[2]/coef[0]
        if not .02<=tau<=3 or not 0<gain<.1 or not 0<=deadband<140:continue
        candidate={'application_delay_s':delay,'traction_tau_s':tau,'speed_gain_mps_per_pwm':gain,'sustaining_deadband_pwm':deadband,'train_acceleration_mse':loss}
        if best is None or loss<best['train_acceleration_mse']:best=candidate
    if best is None:raise ValueError('No physically plausible traction fit; gather better excitation/alignment')
    steer=[]
    for m in train:
        for a,b in zip(m['samples'],m['samples'][1:]):
            error=delayed(m['samples'],a['time_s']-best['application_delay_s'],'target_deg')-a['steering_deg'];rate=(b['steering_deg']-a['steering_deg'])/(b['time_s']-a['time_s'])
            if abs(error)>2 and rate*error>0:steer.append(error/rate)
    if len(steer)<20:raise ValueError('Insufficient loaded steering response samples')
    best['steering_tau_s']=max(.02,min(1.,statistics.median(steer)))
    errors=[]
    for m in valid:
        ss=m['samples'];v=ss[0]['speed_mps']
        for a,b in zip(ss,ss[1:]):
            pwm=delayed(ss,a['time_s']-best['application_delay_s'],'pwm')
            if pwm<=0: v=b['speed_mps'];continue # Coast requires a separately measured coast model.
            target=max(0,pwm-best['sustaining_deadband_pwm'])*best['speed_gain_mps_per_pwm'];dt=b['time_s']-a['time_s'];v=target+(v-target)*math.exp(-dt/best['traction_tau_s']);errors.append(v-b['speed_mps'])
    best['validation_speed_rmse_mps']=math.sqrt(statistics.mean(e*e for e in errors)) if errors else None
    best['validation_abs_error_p95_mps']=quantile([abs(e) for e in errors],.95)
    best['validated_for_simulation']=len(errors)>=60 and best['validation_speed_rmse_mps']<=.05
    best['observed_pwm_range']=[min(levels),max(levels)];best['coast_brake_model']='unmeasured; no deployment or stopping-distance claim';best['validation_samples']=len(errors)
    return best

def simulate(parameters,model,reference,geometry):
    samples=reference['samples']
    if len(samples)<20:raise ValueError('Validated dense reference required')
    for s in samples:
        if not all(finite(s.get(k)) for k in ['x','y','yaw','curvature','s','speed']) or not 0<=s['speed']<=.2:raise ValueError('Reference must contain finite coordinates and <=0.2 m/s speeds')
    if any(b['s']<=a['s'] or b['s']-a['s']>.02 for a,b in zip(samples,samples[1:])):raise ValueError('Reference samples must be ordered with <=20 mm spacing')
    keys=['wheelbase_m','width_m','front_overhang_m','rear_overhang_m','steering_limit_deg','steering_rate_deg_s','cone_radius_m','clearance_m']
    if any(not finite(geometry.get(k)) or geometry[k]<=0 for k in keys):raise ValueError('Measured geometry and steering limits required')
    floor=geometry['floor_bounds_m'];
    if len(floor)!=4 or not all(finite(v) for v in floor) or not floor[0]<floor[1] or not floor[2]<floor[3]:raise ValueError('Measured floor xmin,xmax,ymin,ymax required')
    cones=reference['cones'];x=samples[0]['x'];y=samples[0]['y'];yaw=samples[0]['yaw'];v=0.;angle=0.;integral=0.;index=0;minimum=1e9;dt=.005;queue=[];powered_min,powered_max=model['observed_pwm_range'];ss=[s['s'] for s in samples];last_yaw_rate=0.
    def at(distance):
        j=min(len(samples)-1,bisect.bisect_left(ss,distance));return samples[j]
    for tick in range(12000):
        now=tick*dt
        while index+1<len(samples) and math.hypot(samples[index+1]['x']-x,samples[index+1]['y']-y)<math.hypot(samples[index]['x']-x,samples[index]['y']-y):index+=1
        current=samples[index];cross=math.hypot(x-current['x'],y-current['y'])
        if cross>parameters['maxCrossTrackM']:return {'complete':False,'reason':'cross_track','time_s':now}
        target=at(current['s']+max(parameters['ldMin'],min(parameters['ldMax'],parameters['l0']+parameters['kv']*abs(v))))
        dx=target['x']-x;dy=target['y']-y;qx=math.cos(yaw)*dx+math.sin(yaw)*dy;qy=-math.sin(yaw)*dx+math.cos(yaw)*dy
        if qx<=0 or dx*dx+dy*dy<1e-6:
            if index>=len(samples)-3:return {'complete':True,'time_s':now,'minimum_clearance_m':minimum}
            return {'complete':False,'reason':'lookahead_behind','time_s':now}
        commanded=math.atan(2*geometry['wheelbase_m']*qy/(dx*dx+dy*dy))-parameters['kr']*(last_yaw_rate-v*current['curvature'])
        limit=math.radians(geometry['steering_limit_deg']);commanded=max(-limit,min(limit,commanded));desired=at(current['s']+.025)['speed'];error=desired-v;integral=max(-50,min(50,integral+error*dt))
        ff=desired/model['speed_gain_mps_per_pwm']+model['sustaining_deadband_pwm'];pwm=max(0,min(140,ff+parameters['kp']*error+parameters['ki']*integral))
        if pwm< powered_min or pwm>powered_max:return {'complete':False,'reason':'outside_identified_pwm','time_s':now}
        queue.append((now+model['application_delay_s'],pwm,commanded));applied=queue[0]
        while len(queue)>1 and queue[1][0]<=now:queue.pop(0);applied=queue[0]
        if applied[0]<=now:
            expected=max(0,applied[1]-model['sustaining_deadband_pwm'])*model['speed_gain_mps_per_pwm'];v+=dt*(expected-v)/model['traction_tau_s'];delta=dt*(applied[2]-angle)/model['steering_tau_s'];rate=math.radians(geometry['steering_rate_deg_s'])*dt;angle+=max(-rate,min(rate,delta))
        last_yaw_rate=v*math.tan(angle)/geometry['wheelbase_m'];yaw+=last_yaw_rate*dt;x+=v*math.cos(yaw)*dt;y+=v*math.sin(yaw)*dt
        # Oriented rectangle against each cone and the measured floor, at <=1 mm nominal translation steps.
        front=geometry['wheelbase_m']+geometry['front_overhang_m'];rear=-geometry['rear_overhang_m'];half=geometry['width_m']/2
        for cx,cy in cones:
            dx=cx-x;dy=cy-y;lx=math.cos(yaw)*dx+math.sin(yaw)*dy;ly=-math.sin(yaw)*dx+math.cos(yaw)*dy;gap=math.hypot(max(rear-lx,0,lx-front),max(abs(ly)-half,0))-geometry['cone_radius_m'];minimum=min(minimum,gap)
            if gap<geometry['clearance_m']:return {'complete':False,'reason':'cone_clearance','time_s':now}
        for lx,ly in [(rear,-half),(rear,half),(front,-half),(front,half)]:
            wx=x+math.cos(yaw)*lx-math.sin(yaw)*ly;wy=y+math.sin(yaw)*lx+math.cos(yaw)*ly
            if not floor[0]<=wx<=floor[1] or not floor[2]<=wy<=floor[3]:return {'complete':False,'reason':'floor_bounds','time_s':now}
        if index>=len(samples)-3:return {'complete':True,'time_s':now,'minimum_clearance_m':minimum}
    return {'complete':False,'reason':'timeout','time_s':60}

def search(dataset,seed=0,count=40):
    if dataset.get('reviewed') is not True or dataset.get('schemaVersion')!=1:raise ValueError('Reviewed schemaVersion 1 policy dataset required')
    model=fit_model(dataset['maneuvers'])
    if not model['validated_for_simulation']:return {'model':model,'candidates':[],'promotion':'blocked: held-out dynamics fit failed'}
    training=dataset['training_references'];validation=dataset['validation_references']
    if not training or not validation or {r['session'] for r in training}&{r['session'] for r in validation}:raise ValueError('Independent train/validation reference sessions required')
    rng=random.Random(seed);candidates=[]
    for i in range(count):
        p=dict(DEFAULT)
        if i:
            for k,(low,high) in BOUNDS.items():p[k]=max(low,min(high,DEFAULT[k]*(1+rng.uniform(-.25,.25))))
        train_results=[simulate(p,model,r,dataset['geometry']) for r in training]
        candidates.append({'candidate':i,'seed':seed,'parameters':p,'training':train_results,'training_score_s':sum(r['time_s'] for r in train_results)/len(train_results) if all(r['complete'] for r in train_results) else None})
    eligible=sorted((c for c in candidates if c['training_score_s'] is not None),key=lambda c:c['training_score_s']);selected=eligible[0] if eligible else None
    if selected:selected['validation']=[simulate(selected['parameters'],model,r,dataset['geometry']) for r in validation]
    return {'schemaVersion':1,'model':model,'candidates':candidates,'selected':selected,'proposed_policy':None if not selected else {'schemaVersion':1,'revision':1,'policyId':'cone_slalom_v1','parameters':selected['parameters']},'promotion':'proposal only; requires existing swept-path validation, measured coast/braking, uncertainty stress tests, and repeated reviewed attended real runs','simulation_limits':'bounded kinematic fit; no tire slip/contact physics, no automatic deployment, no braking/finish-clearance score claim'}

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('dataset',type=pathlib.Path);parser.add_argument('--output',required=True,type=pathlib.Path);parser.add_argument('--seed',type=int,default=0);parser.add_argument('--candidates',type=int,default=40);args=parser.parse_args()
    if not 1<=args.candidates<=100:parser.error('1–100 candidates required')
    source=args.dataset.read_bytes();result=search(json.loads(source),args.seed,args.candidates);result['dataset_sha256']=hashlib.sha256(source).hexdigest();args.output.write_text(json.dumps(result,indent=2,allow_nan=False)+'\n');print(json.dumps({'output':str(args.output),'promotion':result['promotion'],'candidate_count':len(result['candidates'])}))
if __name__=='__main__':main()
