#!/usr/bin/env python3
"""Analyse an immutable collection bundle. Observer video remains the physical evidence source."""
import argparse, hashlib, json, math, pathlib, statistics

def load(path):
    return json.loads(path.read_text())

def percentile(values):
    values=sorted(x for x in values if isinstance(x,(int,float)) and math.isfinite(x))
    return None if not values else {"n":len(values),"median":statistics.median(values),"p95":values[max(0,math.ceil(.95*len(values))-1)]}

def interval_difference(before, after):
    if len(before)!=2 or len(after)!=2 or not all(isinstance(x,(int,float)) and math.isfinite(x) for x in [*before,*after]) or before[0]>before[1] or after[0]>after[1]:
        raise ValueError('Ordered finite event intervals required')
    return [after[0]-before[1],after[1]-before[0]]

def observer_tracks(directory, calibration):
    import cv2
    import numpy as np
    # ROI coordinates and planar calibration must be measured for this exact recording.
    if calibration.get('reviewed') is not True: raise ValueError('Review the exact recording and measured observer calibration first')
    video=directory/'observer/video.mp4'
    if calibration.get('video_sha256') != hashlib.sha256(video.read_bytes()).hexdigest(): raise ValueError('Observer calibration belongs to another video')
    cap=cv2.VideoCapture(str(video));tracks=[];quiet=[];last_pts=None;previous=None;active_run=[];events=[]
    samples=[json.loads(line) for line in (directory/'observer/samples.jsonl').read_text().splitlines() if line.strip()]
    # Extractor samples may be in decode order; video frames are presented in PTS order.
    pts=sorted(int(row['pts_us'])/1000 for row in samples)
    if not pts or len(set(pts))!=len(pts): raise ValueError('Missing/duplicate encoded frame timestamps')
    homography=None
    if 'floor_pixels' in calibration:
        src=np.asarray(calibration['floor_pixels'],dtype=np.float32);dst=np.asarray(calibration['floor_m'],dtype=np.float32)
        if src.shape!=(4,2) or dst.shape!=(4,2) or abs(cv2.contourArea(src))<100: raise ValueError('Four nondegenerate measured floor points required')
        homography=cv2.getPerspectiveTransform(src,dst)
    hsv_low=np.asarray(calibration.get('marker_hsv_low',[35,90,70]),dtype=np.uint8);hsv_high=np.asarray(calibration.get('marker_hsv_high',[90,255,255]),dtype=np.uint8)
    def roi(image,name):
        x,y,w,h=calibration[name]
        if not all(type(v) is int for v in [x,y,w,h]) or min(x,y)<0 or min(w,h)<2 or x+w>image.shape[1] or y+h>image.shape[0]: raise ValueError('ROI outside recording')
        return image[y:y+h,x:x+w]
    index=0;witness_last=None
    try:
        while True:
            ok,frame=cap.read()
            if not ok: break
            if index>=len(pts): raise ValueError('Decoded frame count exceeds retained PTS samples')
            timestamp=pts[index];row={'frame':index,'pts_ms':timestamp};index+=1
            gray=cv2.cvtColor(frame,cv2.COLOR_BGR2GRAY)
            if 'witness_roi' in calibration:
                value=float(roi(gray,'witness_roi').mean());threshold=calibration.get('witness_threshold')
                if not isinstance(threshold,(int,float)) or not 0<threshold<255: raise ValueError('Measured witness_threshold required')
                witness=value>threshold;row['witness_luma']=value;row['witness_on']=witness
                if witness_last is not None and witness!=witness_last: events.append({'type':'witness_on' if witness else 'witness_off','interval_ms':[last_pts,timestamp]})
                witness_last=witness
            if 'wheel_roi' in calibration:
                crop=roi(gray,'wheel_roi')
                if previous is not None:
                    difference=float(cv2.absdiff(crop,previous).mean());row['wheel_difference']=difference
                    baseline_frames=int(calibration.get('baseline_frames',30))
                    if len(quiet)<baseline_frames: quiet.append(difference)
                    else:
                        median=statistics.median(quiet);mad=statistics.median(abs(v-median) for v in quiet)
                        threshold=max(float(calibration.get('wheel_difference_floor',3)),median+8*max(mad,.1));row['wheel_threshold']=threshold
                        if difference>threshold:
                            active_run.append([last_pts,timestamp])
                            if len(active_run)==3:events.append({'type':'wheel_motion_onset_candidate','interval_ms':active_run[0],'definition':'first persistent 3-frame ROI difference above reviewed baseline threshold; optical candidate, not shaft motion'})
                        else:active_run=[]
                previous=crop.copy()
            if homography is not None:
                mask=cv2.inRange(cv2.cvtColor(frame,cv2.COLOR_BGR2HSV),hsv_low,hsv_high);contours,_=cv2.findContours(mask,cv2.RETR_EXTERNAL,cv2.CHAIN_APPROX_SIMPLE)
                eligible=[c for c in contours if cv2.contourArea(c)>=float(calibration.get('marker_min_area_px',8))]
                if eligible:
                    contour=max(eligible,key=cv2.contourArea);moments=cv2.moments(contour)
                    if moments['m00']>0:
                        point=np.array([[[moments['m10']/moments['m00'],moments['m01']/moments['m00']]]],dtype=np.float32);mapped=cv2.perspectiveTransform(point,homography)[0,0]
                        row['marker_floor_m']=[float(v) for v in mapped];row['marker_area_px']=float(cv2.contourArea(contour))
            tracks.append(row);last_pts=timestamp
        if index!=len(pts): raise ValueError('Decoded frame count differs from retained PTS samples; alignment requires review')
    finally:cap.release()
    return {'tracks':tracks,'events':events,'frame_intervals_ms':percentile([b-a for a,b in zip(pts,pts[1:])]),'physical_validation':'candidate events require manual frame review; rolling shutter/exposure and witness display delay remain separate'}

def analyse(directory, calibration=None):
    manifest=load(directory/'manifest.json');summary=load(directory/'summary.json');events=[json.loads(line) for line in (directory/'events.jsonl').read_text().splitlines() if line.strip()]
    result={'schemaVersion':1,'experiment_id':manifest['id'],'recipe':manifest['recipe'],'outcome':summary['outcome'],'collection_complete':summary['complete'],'physical_latency_ms':None,'physical_validation':False,'warnings':[],'observer':None}
    if not summary['complete']:result['warnings'].append('Evidence bundle incomplete; exclude from promotion and confident timing claims.')
    signals_file=directory/'signals.json'
    if signals_file.exists():
        signals=load(signals_file);result['software_summary']=signals.get('summary');result['trace_completeness']=signals.get('completeness')
        if manifest['recipe']=='camera_clock':
            gyro=[];accel=[]
            for record in signals.get('records',[]):
                e=record.get('event',{})
                if e.get('type')=='imu' and isinstance(e.get('values'),list) and len(e['values'])>=3:
                    values=e['values'][:3]
                    if all(isinstance(v,(int,float)) and math.isfinite(v) for v in values):
                        if e.get('sensor_type')==4:gyro.append(values)
                        elif e.get('sensor_type')==1:accel.append(values)
            if len(gyro)>=200 and len(accel)>=200:
                means=[statistics.mean(row[i] for row in gyro) for i in range(3)];std=[statistics.pstdev(row[i] for row in gyro) for i in range(3)];gravity=[statistics.mean(row[i] for row in accel) for i in range(3)]
                result['stationary_imu_proposal']={'gyro_bias_phone_rad_s':means,'gyro_std_rad_s':std,'gravity_phone_mps2':gravity,'samples':len(gyro),'stationarity_gate':max(std)<.02 and 9<math.sqrt(sum(v*v for v in gravity))<10.5,'activation':'proposal only; confirm car was stationary and preserve sensor/phone/mount identity','unobservable':'gravity cannot identify yaw alignment or IMU lever arm'}
    observer_file=directory/'observer/observer.json'
    if observer_file.exists(): result['observer_metadata']=load(observer_file)
    if calibration:result['observer']=observer_tracks(directory,calibration)
    review_file=directory/'review.json'
    if review_file.exists():
        review=load(review_file);video=directory/'observer/video.mp4'
        if review.get('reviewed') is not True or review.get('video_sha256')!=hashlib.sha256(video.read_bytes()).hexdigest():raise ValueError('Reviewed witness intervals must identify this exact video')
        intervals=[]
        for trial in review.get('trials',[]):
            # Both endpoints must be physical events visible in the SAME observer recording.
            if trial.get('endpoint')!='visible_witness_to_wheel':raise ValueError('Only same-video witness-to-wheel intervals are supported; do not mislabel them input-to-motor')
            interval=interval_difference(trial['witness_interval_ms'],trial['wheel_interval_ms']);intervals.append(interval)
        result['visible_witness_to_wheel_ms']={'intervals':intervals,'midpoint_summary':percentile([sum(v)/2 for v in intervals]),'endpoint':'visible screen witness → observed wheel onset; excludes input-to-witness delay'}
        result['physical_validation']=bool(intervals) and summary['complete']
    result['event_counts']={name:sum(e.get('type')==name for e in events) for name in set(e.get('type') for e in events)}
    (directory/'analysis.json').write_text(json.dumps(result,indent=2,allow_nan=False)+'\n')
    text=f"# Experiment analysis: {manifest['id']}\n\nRecipe: {manifest['recipe']}. Outcome: {summary['outcome']}. Collection complete: {summary['complete']}.\n\n## Calculations\n\nFor bounded events A=[a₀,a₁], B=[b₀,b₁], Δ=[b₀−a₁,b₁−a₀]. Cross-device comparisons require a valid clock map; frame-rate nominal values are not event timestamps.\n\nSoftware pipeline calculations are in [signals.md](signals.md). Physical wheel motion is separate from signed PWM application. Visible-witness timing excludes input-to-display delay.\n\n## Results\n\nPhysical source-input → motor-shaft onset: **unavailable without a validated endpoint/clock map or shaft sensor**.\n\n"
    if result.get('visible_witness_to_wheel_ms'):text+='Reviewed visible-witness → wheel intervals (ms): '+json.dumps(result['visible_witness_to_wheel_ms'])+'\n\n'
    text+='## Optimization experiments\n\nCompare one factor at a time: video bitrate/resolution, receiver buffering, monitoring workload, detector decoder, delegate/thread count, and USB serial scheduling. Interleave baseline/candidate runs; compare p95, thermal state, errors and losses as well as median. Never add percentiles from unmatched stages.\n'
    text+='\n'.join(result['warnings'])+'\n'
    (directory/'analysis.md').write_text(text)
    return result

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('bundle',type=pathlib.Path);parser.add_argument('--calibration',type=pathlib.Path);args=parser.parse_args()
    result=analyse(args.bundle,load(args.calibration) if args.calibration else None);print(json.dumps({k:v for k,v in result.items() if k!='observer'},indent=2))
if __name__=='__main__':main()
