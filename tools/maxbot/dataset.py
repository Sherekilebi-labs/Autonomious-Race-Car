#!/usr/bin/env python3
"""Extract clean frames, validate YOLO labels, split whole sessions, and suggest collision review."""
import argparse
import hashlib
import json
import math
import pathlib
import random
import shutil
import subprocess

IMAGE_SUFFIXES={'.png','.jpg','.jpeg'}

def validate_label(label):
    if not label.exists():raise ValueError(f'Missing label (use an empty file for background): {label}')
    for line in label.read_text().splitlines():
        fields=line.split()
        if len(fields)!=5 or fields[0]!='0':raise ValueError(f'Expected class 0 + x y width height: {label}')
        x,y,w,h=map(float,fields[1:])
        if not all(math.isfinite(v) for v in [x,y,w,h]) or not (0<=x<=1 and 0<=y<=1 and 0<w<=1 and 0<h<=1):raise ValueError(f'Invalid normalized box: {label}')
        if x-w/2 < -.001 or x+w/2>1.001 or y-h/2<-.001 or y+h/2>1.001:raise ValueError(f'Box extends beyond image: {label}')

def split(source,output,seed=42):
    if output.exists():raise ValueError('Output already exists; choose a new version to preserve split provenance')
    sessions=sorted(p for p in source.iterdir() if p.is_dir() and (p/'images').is_dir() and (p/'labels').is_dir())
    if len(sessions)<3:raise ValueError('At least three labelled recording sessions required for train/validation/test isolation')
    random.Random(seed).shuffle(sessions)
    count=len(sessions);test=max(1,round(count*.15));val=max(1,round(count*.15))
    assignment={p.name:('test' if i<test else 'val' if i<test+val else 'train') for i,p in enumerate(sessions)}
    seen={};entries=[]
    # Validate all input before creating an output dataset.
    for session in sessions:
        images=sorted(p for p in (session/'images').iterdir() if p.suffix.lower() in IMAGE_SUFFIXES)
        if not images:raise ValueError(f'Empty session: {session}')
        for image in images:
            label=session/'labels'/f'{image.stem}.txt';validate_label(label)
            digest=hashlib.sha256(image.read_bytes()).hexdigest()
            if digest in seen:raise ValueError(f'Duplicate image content across dataset: {image} and {seen[digest]}')
            seen[digest]=str(image)
            entries.append((session,image,label,digest))
    manifest={'schemaVersion':1,'seed':seed,'sessions':assignment,'images':[]}
    for session,image,label,digest in entries:
        partition=assignment[session.name];name=session.name+'__'+image.name
        for kind,path,target_name in [('images',image,name),('labels',label,pathlib.Path(name).with_suffix('.txt').name)]:
            destination=output/kind/partition/target_name;destination.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(path,destination)
        manifest['images'].append({'session':session.name,'split':partition,'name':name,'sha256':digest})
    (output/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
    (output/'data.yaml').write_text('path: '+json.dumps(str(output.resolve()))+'\ntrain: images/train\nval: images/val\ntest: images/test\nnames:\n  0: cone\n')
    return manifest

def validate_dataset(root):
    manifest=json.loads((root/'manifest.json').read_text());groups={};hashes=set();count=0
    for entry in manifest['images']:
        name=entry['name'];part=entry['split'];session=entry['session']
        if part not in {'train','val','test'}:raise ValueError('Unknown partition')
        if session in groups and groups[session]!=part:raise ValueError('Session leaks across partitions')
        groups[session]=part
        image=root/'images'/part/name
        digest=hashlib.sha256(image.read_bytes()).hexdigest()
        if digest!=entry['sha256'] or digest in hashes:raise ValueError(f'Changed or duplicated image: {image}')
        hashes.add(digest);validate_label(root/'labels'/part/(pathlib.Path(name).stem+'.txt'));count+=1
    if set(groups.values())!={'train','val','test'}:raise ValueError('All three isolated splits required')
    return count

def collision_candidates(events):
    reference=None;streak=0;result=[]
    for line in events:
        try:event=json.loads(line)
        except ValueError:continue
        if event.get('type')=='telemetry' and len(event.get('cones',[]))==4 and reference is None:reference=event['cones']
        if event.get('type')!='detections' or reference is None:continue
        points=event.get('points',[])
        moved=any(min(math.hypot(p[0]-r[0],p[1]-r[1]) for r in reference)>.08 for p in points)
        streak=streak+1 if moved else 0
        if streak==3:result.append({'capture_ns':event['capture_ns'],'label':'collision_candidate','reason':'persistent ground-frame cone displacement; human review required'})
    return result

def main():
    parser=argparse.ArgumentParser(description=__doc__);sub=parser.add_subparsers(dest='command',required=True)
    p=sub.add_parser('extract');p.add_argument('--video',type=pathlib.Path,required=True);p.add_argument('--output',type=pathlib.Path,required=True);p.add_argument('--fps',type=float,default=2)
    p=sub.add_parser('split');p.add_argument('--source',type=pathlib.Path,required=True);p.add_argument('--output',type=pathlib.Path,required=True);p.add_argument('--seed',type=int,default=42)
    p=sub.add_parser('labels');p.add_argument('--dataset',type=pathlib.Path,required=True)
    p=sub.add_parser('collisions');p.add_argument('--events',type=pathlib.Path,required=True);p.add_argument('--output',type=pathlib.Path,required=True)
    args=parser.parse_args()
    if args.command=='extract':
        if not math.isfinite(args.fps) or args.fps<=0 or args.fps>30:parser.error('fps must be in (0,30]')
        if args.output.exists():parser.error('Choose a new session directory')
        (args.output/'images').mkdir(parents=True);(args.output/'labels').mkdir()
        subprocess.run(['ffmpeg','-nostdin','-n','-i',str(args.video),'-vf',f'fps={args.fps}','-vsync','vfr',str(args.output/'images/frame-%07d.png')],check=True)
        (args.output/'session.json').write_text(json.dumps({'source':str(args.video.resolve()),'fps':args.fps,'source_sha256':hashlib.sha256(args.video.read_bytes()).hexdigest()},indent=2))
        print('Frames extracted. Human-label cones before splitting; do not assume unlabeled images are background.')
    elif args.command=='split':print(json.dumps(split(args.source,args.output,args.seed)['sessions'],indent=2))
    elif args.command=='labels':print(f'Validated {validate_dataset(args.dataset)} labelled images with session isolation')
    else:
        if args.output.exists():parser.error('Output already exists')
        with args.events.open() as source:result=collision_candidates(source)
        args.output.write_text(json.dumps(result,indent=2)+'\n');print(f'{len(result)} candidates; this is not collision ground truth')

if __name__=='__main__':main()
