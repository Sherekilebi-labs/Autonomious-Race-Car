#!/usr/bin/env python3
"""Stopped-only offline bicycle planner; local search failure never proves impossibility."""
import argparse, hashlib, json, math, pathlib, sys
import numpy as np
from scipy.optimize import minimize
from scipy.interpolate import CubicSpline
VERSION='bicycle-slsqp-v1'

def canonical(value):return json.dumps(value,sort_keys=True,separators=(',',':'),allow_nan=False).encode()
def identity(value):return hashlib.sha256(canonical(value)).hexdigest()
def strict(path):
    def pairs(items):
        out={}
        for k,v in items:
            if k in out:raise ValueError('Duplicate JSON key')
            out[k]=v
        return out
    return json.loads(pathlib.Path(path).read_text(),object_pairs_hook=pairs,parse_constant=lambda x: (_ for _ in ()).throw(ValueError(x)))
def footprint(p,cone,car):
    x,y,yaw=p[:3];dx,dy=cone['x']-x,cone['y']-y;c,s=math.cos(yaw),math.sin(yaw)
    qx,qy=c*dx+s*dy,-s*dx+c*dy
    return math.hypot(max(-car['rearOverhangM']-qx,0,qx-car['wheelbaseM']-car['frontOverhangM']),max(abs(qy)-car['widthM']/2,0))
def minimum_x(p,car):
    x,y,yaw=p[:3];c,s=math.cos(yaw),math.sin(yaw)
    return min(x+a*c-b*s for a in [-car['rearOverhangM'],car['wheelbaseM']+car['frontOverhangM']] for b in [-car['widthM']/2,car['widthM']/2])
def legacy_requirement(c):
    car=c['car'];offset=math.hypot(car['wheelbaseM']+car['frontOverhangM'],car['widthM']/2)+.1+.15
    u=np.linspace(0,1,10001);dx=.7;dy=2*offset
    first=30*u*u*(1-u)**2*dy/dx;second=60*u*(1-3*u+2*u*u)*dy/dx**2
    curvature=float(np.max(abs(second)/(1+first*first)**1.5))
    return {'curvature':curvature,'steeringDeg':math.degrees(math.atan(car['wheelbaseM']*curvature)),'limitDeg':c['steering']['leftDeg']}
def validate_inputs(req):
    if req.get('schemaVersion')!=1 or req.get('stopped') is not True:raise ValueError('Versioned stopped snapshot required')
    def finite(value):
        if isinstance(value,float) and not math.isfinite(value):raise ValueError('Nonfinite planner input')
        if isinstance(value,dict):
            for v in value.values():finite(v)
        if isinstance(value,list):
            if len(value)>8192:raise ValueError('Planner array bound')
            for v in value:finite(v)
    finite(req)
    c,m,course=req['calibration'],req['vehicleModel'],req['course']
    if req.get('uncertaintyM',0) is None or req.get('uncertaintyM',0)<0:raise ValueError('Measured pose uncertainty required')
    if req.get('coneRadiusM')!=c['cones']['baseDiameterM']/2:raise ValueError('Cone footprint mismatch')
    if not m.get('reviewed') or not m.get('provenance'):raise ValueError('Reviewed measured vehicle dynamics required')
    for k in ['steeringRateRadS','accelerationMps2','brakingMps2','lateralMps2']:
        if not isinstance(m.get(k),(int,float)) or not math.isfinite(m[k]) or m[k]<=0:raise ValueError('Missing dynamics: '+k)
    for k in ['steeringLagS','vioLagS','applicationDelayS']:
        if not isinstance(m.get(k),(int,float)) or not math.isfinite(m[k]) or m[k]<0:raise ValueError('Missing measured lag: '+k)
    table=m.get('speedPwm',[])
    if len(table)<2 or len(table)>128:raise ValueError('Measured PWM table required')
    for i,p in enumerate(table):
        if len(p)!=2 or not all(math.isfinite(v) for v in p) or p[0]<0 or not 0<=p[1]<=255 or (i and (p[0]<=table[i-1][0] or p[1]<table[i-1][1])):raise ValueError('Invalid PWM table')
    if len(course.get('cones',[]))!=4 or not course.get('frozen'):raise ValueError('Frozen four-cone map required')
    for cone in course['cones']:
        if not cone.get('observed') or cone.get('errorBoundM') is None or cone.get('independentViews',0)<2:raise ValueError('Independent measured cone bounds required')
    if req.get('clearanceM',.15)<.15:raise ValueError('Inherited 150 mm clearance cannot shrink')
    floor=req.get('floorBoundsM')
    if floor is None or len(floor)!=4 or floor[0]>=floor[1] or floor[2]>=floor[3]:raise ValueError('Measured floor xmin,xmax,ymin,ymax required')
    if not all(math.isfinite(v) for v in floor):raise ValueError('Nonfinite floor')
    for k in ['wheelbaseM','widthM','frontOverhangM','rearOverhangM']:
        if c['car'].get(k) is None:raise ValueError('Missing geometry '+k)
    return c,m,course

def integrate(curves,length,start=(0,0,0),sub=1):
    ds=length/(len(curves)-1);x,y,yaw=start;out=[[x,y,yaw,curves[0],0]];distance=0
    for i in range(1,len(curves)):
        for j in range(sub):
            k0=curves[i-1]+(curves[i]-curves[i-1])*j/sub;k1=curves[i-1]+(curves[i]-curves[i-1])*(j+1)/sub
            h=ds/sub;k=(k0+k1)/2;mid=yaw+k*h/2;x+=h*math.cos(mid);y+=h*math.sin(mid);yaw+=k*h;distance+=h
            out.append([x,y,yaw,k1,distance])
    return np.array(out)

def retime(path,req):
    m=req['vehicleModel'];L=req['calibration']['car']['wheelbaseM'];ds=np.diff(path[:,4]);angle=np.arctan(L*path[:,3]);rate=np.abs(np.gradient(angle,path[:,4]));lag=m['steeringLagS']+m['applicationDelayS']+m['vioLagS']
    vmax=min(req.get('taskSpeedMps',.2),m['validSpeedRangeMps'][1],m['speedPwm'][-1][0]);v=np.minimum(vmax,np.sqrt(m['lateralMps2']/np.maximum(abs(path[:,3]),1e-9)))
    v=np.minimum(v,m['steeringRateRadS']/np.maximum(rate,1e-9));v=np.minimum(v,.05/np.maximum(rate*max(lag,.001),1e-9));v[0]=v[-1]=0
    for i in range(1,len(v)):v[i]=min(v[i],math.sqrt(v[i-1]**2+2*m['accelerationMps2']*ds[i-1]))
    # Delay reserves stopping distance in the speed envelope.
    for i in range(len(v)-2,-1,-1):
        remaining=path[-1,4]-path[i,4];delaycap=-m['brakingMps2']*lag+math.sqrt((m['brakingMps2']*lag)**2+2*m['brakingMps2']*remaining)
        v[i]=min(v[i],math.sqrt(v[i+1]**2+2*m['brakingMps2']*ds[i]),delaycap)
    return v

def validate_path(path,speeds,req):
    c,m,course=validate_inputs(req);car=c['car'];cones=course['cones'];clear=req.get('clearanceM',.15);unc=req.get('uncertaintyM',0)+max(k['errorBoundM'] for k in cones)
    errors=[];minimum=float('inf');floor=req['floorBoundsM'];radius=math.hypot(car['wheelbaseM']+car['frontOverhangM'],car['widthM']/2)
    if len(path)<3 or len(path)>8192 or not np.all(np.isfinite(path)) or not np.all(np.isfinite(speeds)):return {'feasible':False,'diagnostics':['Invalid samples'],'version':VERSION}
    if np.any(speeds<0) or speeds[0]!=0 or speeds[-1]!=0:errors.append('Launch/braking tail speed')
    finish=None;passed=0
    for i,p in enumerate(path):
        angle=math.degrees(math.atan(car['wheelbaseM']*p[3]))
        if angle<c['steering']['rightDeg']-1e-6 or angle>c['steering']['leftDeg']+1e-6:errors.append('Steering limit')
        if i:
            a=path[i-1];distance=np.linalg.norm(p[:2]-a[:2]);dyaw=p[2]-a[2];ds=p[4]-a[4]
            if p[0]<a[0]-.001:errors.append('Reverse course progress')
            if ds<=0 or ds>.051 or abs(distance-ds)>.002 or abs(dyaw-(a[3]+p[3])*.5*ds)>.003:errors.append('Interpolation/bicycle continuity')
            n=max(1,math.ceil((distance+radius*abs(dyaw))/.002));bound=(distance+radius*abs(dyaw))/(2*n)
            for t in (np.arange(n)+.5)/n:
                q=a+t*(p-a)
                for cone in cones:minimum=min(minimum,footprint(q,cone,car)-req['coneRadiusM']-clear-unc-bound)
                for along in [-car['rearOverhangM'],car['wheelbaseM']+car['frontOverhangM']]:
                    for across in [-car['widthM']/2,car['widthM']/2]:
                        x=q[0]+along*math.cos(q[2])-across*math.sin(q[2]);y=q[1]+along*math.sin(q[2])+across*math.cos(q[2]);b=unc+bound
                        if x-b<floor[0] or x+b>floor[1] or y-b<floor[2] or y+b>floor[3]:errors.append('Floor limit')
            da=abs(math.atan(car['wheelbaseM']*p[3])-math.atan(car['wheelbaseM']*a[3]))
            if da*max(speeds[i],speeds[i-1])>m['steeringRateRadS']*ds+1e-6:errors.append('Steering rate')
            if speeds[i]**2-speeds[i-1]**2>2*m['accelerationMps2']*ds+1e-6 or speeds[i-1]**2-speeds[i]**2>2*m['brakingMps2']*ds+1e-6:errors.append('Acceleration/braking')
        if speeds[i]**2*abs(p[3])>m['lateralMps2']+1e-6:errors.append('Grip')
        if passed<4 and p[0]>=cones[passed]['x']:
            if (-1 if passed%2==0 else 1)*(p[1]-cones[passed]['y'])<car['widthM']/2+req['coneRadiusM']+clear+unc:errors.append('Wrong side')
            passed+=1
        if passed==4 and finish is None and minimum_x(p,car)>cones[-1]['x']+req['coneRadiusM']+unc:finish=float(p[4])
    if minimum<0:errors.append('Continuous footprint clearance')
    if passed!=4 or finish is None or finish>=path[-1,4]:errors.append('Finish/braking tail missing')
    return {'version':VERSION,'feasible':not errors,'diagnostics':sorted(set(errors)),'minMarginM':minimum,'finishS':finish}

def plan(req):
    c,m,course=validate_inputs(req);car=c['car'];cones=course['cones'];n=min(100,max(30,int(req.get('knots',60))));kmax=math.tan(math.radians(min(-c['steering']['rightDeg'],c['steering']['leftDeg'])))/car['wheelbaseM']
    margin=req.get('clearanceM',.15)+req.get('uncertaintyM',0)+max(k['errorBoundM'] for k in cones)+req['coneRadiusM'];floor=req['floorBoundsM'];last=cones[-1]['x'];best=None;attempts=[]
    def path_for(z):return integrate(z[:-1],z[-1],req.get('launchPose',[0,0,0]),sub=3)
    def constraints(z):
        path=path_for(z);v=[]
        for p in path:
            v.append(math.cos(p[2])-.05)
            for cone in cones:v.append(footprint(p,cone,car)-margin-.004)
            for a in [-car['rearOverhangM'],car['wheelbaseM']+car['frontOverhangM']]:
                for b in [-car['widthM']/2,car['widthM']/2]:
                    x=p[0]+a*math.cos(p[2])-b*math.sin(p[2]);y=p[1]+a*math.sin(p[2])+b*math.cos(p[2]);v.extend([x-floor[0],floor[1]-x,y-floor[2],floor[3]-y])
        for i,cone in enumerate(cones):
            j=int(np.argmin(abs(path[:,0]-cone['x'])));v.extend([(-1 if i%2==0 else 1)*(path[j,1]-cone['y'])-margin-car['widthM']/2, .08-abs(path[j,0]-cone['x'])])
        v.append(minimum_x(path[-1],car)-last-req['coneRadiusM']-.3)
        return np.array(v)
    # Free curvature/heading and longitudinal placement, with several phase/amplitude seeds.
    length=max(last+1,5)
    for phase,amplitude in [(0,.42),(-.08,.46),(.08,.46),(-.15,.5),(.15,.5)][:max(1,min(5,req.get('seeds',5)))]:
        # Smooth geometric seeds retain free approach headings; curvature controls remain optimization variables.
        stations=[0,cones[0]['x']*.5]+[cone['x']+phase for cone in cones]+[last+1,last+2]
        lateral=[0,-.1]+[cone['y']+(-1 if i%2==0 else 1)*amplitude for i,cone in enumerate(cones)]+[amplitude,amplitude]
        spline=CubicSpline(stations,lateral,bc_type=((1,0),(1,0)));xx=np.linspace(0,last+2,4000);yy=spline(xx);arc=np.r_[0,np.cumsum(np.hypot(np.diff(xx),np.diff(yy)))];sampleX=np.interp(np.linspace(0,arc[-1],n),arc,xx)
        curvature=spline(sampleX,2)/(1+spline(sampleX,1)**2)**1.5
        seed=np.r_[np.clip(curvature,-kmax,kmax),min(arc[-1],11.9)]
        def objective(z):
            candidate=path_for(z);v=retime(candidate,req);indices=np.where(candidate[:,0]>=last+car['rearOverhangM']+req['coneRadiusM'])[0];end=int(indices[0]) if len(indices) else len(v)-1
            return float(np.sum(2*np.diff(candidate[:end+1,4])/np.maximum(v[1:end+1]+v[:end],1e-4)))+.002*np.sum(np.diff(z[:-1])**2)
        result=minimize(objective,seed,method='SLSQP',bounds=[(-kmax,kmax)]*n+[(last+.5,min(12,floor[1]-floor[0]+6))],constraints=[{'type':'ineq','fun':constraints}],options={'maxiter':int(req.get('maxIterations',100)),'ftol':1e-6})
        path=integrate(result.x[:-1],result.x[-1],req.get('launchPose',[0,0,0]),sub=max(3,math.ceil(result.x[-1]/(n-1)/.025)))
        speed=retime(path,req);report=validate_path(path,speed,req);attempts.append({'success':bool(result.success),'message':str(result.message),'validator':report})
        if report['feasible']:
            elapsed=np.cumsum(np.r_[0,2*np.diff(path[:,4])/np.maximum(speed[1:]+speed[:-1],1e-6)]);finish_index=np.searchsorted(path[:,4],report['finishS']);score=float(elapsed[finish_index])
            if best is None or score<best[0]:best=(score,path,speed,report)
    if best is None:return {'ok':False,'status':'search_failed','globalInfeasibilityProven':False,'attempts':attempts,'legacyRegression':legacy_requirement(c)}
    score,path,speed,report=best
    ref={'schemaVersion':1,'revision':req.get('revision',1),'mapEpoch':course['mapEpoch'],'frameId':course['frameId'],'calibrationId':c['firmwareCalibrationId'],'geometryId':c['geometryId'],'vehicleModelId':req.get('vehicleModelId',identity(m)),'planningParametersId':req.get('planningParametersId',identity(req.get('parameters',{}))),'cameraId':course['cameraId'],'modelId':course['modelId'],'sides':[-1,1,-1,1],'clearanceM':req.get('clearanceM',.15),'uncertaintyM':req.get('uncertaintyM',0)+max(k['errorBoundM'] for k in cones),'samples':[dict(zip(['x','y','yaw','curvature','s'],map(float,p)),speed=float(v)) for p,v in zip(path,speed)],'finishS':report.pop('finishS'),'validator':{'version':VERSION,'feasible':True,'diagnostics':[]}}
    ref['referenceId']=identity(ref)
    return {'ok':True,'reference':ref,'referenceCanonical':canonical(ref).decode(),'estimatedFinishS':score,'attempts':attempts,'optimality':'local candidates only'}
def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('request');parser.add_argument('--output');args=parser.parse_args()
    try:result=plan(strict(args.request))
    except (ValueError,KeyError,TypeError) as e:result={'ok':False,'status':'invalid_input','diagnostics':[str(e)]}
    raw=canonical(result).decode()+'\n'
    if args.output:pathlib.Path(args.output).write_text(raw)
    else:sys.stdout.write(raw)
    return 0 if result['ok'] else 2
if __name__=='__main__':raise SystemExit(main())
