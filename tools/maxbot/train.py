#!/usr/bin/env python3
"""Offline YOLOv5n training/export wrapper. Requires reviewed data; never trains on the phone."""
import argparse
import hashlib
import json
import pathlib
import subprocess
import sys
from dataset import validate_dataset

ROOT=pathlib.Path(__file__).resolve().parents[2]

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--dataset',type=pathlib.Path,required=True)
    parser.add_argument('--output',type=pathlib.Path,required=True)
    parser.add_argument('--yolo-root',type=pathlib.Path,default=ROOT/'.tools/yolov5')
    parser.add_argument('--epochs',type=int,default=100)
    parser.add_argument('--device',default='cpu',help='Use 0 for a CUDA training machine; default is CPU')
    parser.add_argument('--batch',type=int,default=16)
    args=parser.parse_args()
    if sys.version_info[:2] not in {(3,10),(3,11)}:parser.error('Use a separate Python 3.10/3.11 environment with requirements-training.txt')
    count=validate_dataset(args.dataset.resolve().parent)
    if args.output.exists():parser.error('Choose a new model version directory')
    if not args.yolo_root.exists():
        args.yolo_root.parent.mkdir(parents=True,exist_ok=True)
        subprocess.run(['git','clone','--depth','1','--branch','v7.0','https://github.com/ultralytics/yolov5.git',str(args.yolo_root)],check=True)
    tag=subprocess.check_output(['git','-C',str(args.yolo_root),'describe','--tags','--exact-match'],text=True).strip()
    if tag!='v7.0':parser.error('Expected YOLOv5 v7.0 (decoded anchor-based tensor contract)')
    commit=subprocess.check_output(['git','-C',str(args.yolo_root),'rev-parse','HEAD'],text=True).strip()
    run=[sys.executable,str(args.yolo_root/'train.py'),'--weights','yolov5n.pt','--img','320','--batch-size',str(args.batch),'--epochs',str(args.epochs),'--data',str(args.dataset.resolve()),'--project',str(args.output.resolve()),'--name','fit','--device',args.device]
    subprocess.run(run,cwd=args.yolo_root,check=True)
    weights=args.output.resolve()/'fit/weights/best.pt'
    subprocess.run([sys.executable,str(args.yolo_root/'val.py'),'--weights',str(weights),'--data',str(args.dataset.resolve()),'--img','320','--task','test','--device',args.device],cwd=args.yolo_root,check=True)
    for quantized in [False,True]:
        export_command=[sys.executable,str(args.yolo_root/'export.py'),'--weights',str(weights),'--imgsz','320','--include','tflite','--data',str(args.dataset.resolve())]
        if quantized:export_command.append('--int8')
        subprocess.run(export_command,cwd=args.yolo_root,check=True)
    exports=list(weights.parent.glob('*.tflite'))
    if not exports:raise RuntimeError('No TFLite export produced')
    import tensorflow as tf
    artifacts=[]
    for export in exports:
        interpreter=tf.lite.Interpreter(model_path=str(export));interpreter.allocate_tensors()
        inputs=interpreter.get_input_details();outputs=interpreter.get_output_details()
        if list(inputs[0]['shape'])!=[1,320,320,3] or list(outputs[0]['shape'])!=[1,6300,6]:raise RuntimeError(f'Unexpected exported tensor contract: {export}')
        artifacts.append({'file':str(export.relative_to(args.output.resolve())),'sha256':hashlib.sha256(export.read_bytes()).hexdigest(),'input_dtype':str(inputs[0]['dtype']),'output_dtype':str(outputs[0]['dtype'])})
        evaluation=subprocess.run([sys.executable,str(args.yolo_root/'val.py'),'--weights',str(export),'--data',str(args.dataset.resolve()),'--img','320','--task','test','--device','cpu'],cwd=args.yolo_root,check=True,capture_output=True,text=True)
        (args.output/(export.stem+'-heldout.txt')).write_text(evaluation.stdout+'\n'+evaluation.stderr)
    manifest={'schemaVersion':1,'yolov5_tag':tag,'yolov5_commit':commit,'images':count,'dataset_manifest_sha256':hashlib.sha256((args.dataset.parent/'manifest.json').read_bytes()).hexdigest(),'command':run,'artifacts':artifacts,'deployment_status':'NOT_VALIDATED_ON_PIXEL'}
    (args.output/'provenance.json').write_text(json.dumps(manifest,indent=2)+'\n')
    print('Exported models need held-out exported-model evaluation and sustained Pixel profiling before promotion.')

if __name__=='__main__':main()
