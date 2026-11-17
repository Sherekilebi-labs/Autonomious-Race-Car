#!/usr/bin/env python3
"""Export official, untrained-by-us YOLOv5n v7.0 at 320px for manual warnings.

Run in an isolated Python 3.11 environment with torch==2.5.1,
torchvision==0.20.1, tensorflow==2.15.1 and the upstream requirements.
Downloads are confined to .tools; only verified TFLite and provenance enter assets.
"""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
WORK = ROOT / '.tools/yolov5-baseline'
ASSETS = ROOT / 'robot/android/maxbot/src/main/assets/maxbot'
UPSTREAM = 'https://github.com/ultralytics/yolov5'
COMMIT = '915bbf294bb74c859f0b41f1c23bc395014ea679'  # v7.0
WEIGHTS_SHA256 = '4f180cf23ba0717ada0badd6c685026d73d48f184d00fc159c2641284b2ac0a3'


def main():
    WORK.mkdir(parents=True, exist_ok=True)
    repo = WORK / 'source'
    if not repo.exists():
        subprocess.run(['git', 'clone', '--depth', '1', '--branch', 'v7.0', UPSTREAM, str(repo)], check=True)
    commit = subprocess.check_output(['git', '-C', str(repo), 'rev-parse', 'HEAD'], text=True).strip()
    if commit != COMMIT:
        raise ValueError('Expected the official YOLOv5 v7.0 commit')
    weights = WORK / 'yolov5n.pt'
    url = UPSTREAM + '/releases/download/v7.0/yolov5n.pt'
    if not weights.exists():
        urllib.request.urlretrieve(url, weights)
    if hashlib.sha256(weights.read_bytes()).hexdigest() != WEIGHTS_SHA256:
        raise ValueError('Official weights SHA-256 mismatch; do not load this checkpoint')
    env = dict(os.environ, OMP_NUM_THREADS='2', TF_NUM_INTRAOP_THREADS='2', TF_NUM_INTEROP_THREADS='2')
    subprocess.run([sys.executable, str(repo / 'export.py'), '--weights', str(weights),
                    '--imgsz', '320', '--batch-size', '1', '--include', 'tflite', '--device', 'cpu'],
                   cwd=repo, env=env, check=True)
    import tensorflow as tf
    import numpy as np
    model = WORK / 'yolov5n-fp16.tflite'
    interpreter = tf.lite.Interpreter(model_path=str(model), num_threads=2)
    interpreter.allocate_tensors()
    inp, out = interpreter.get_input_details()[0], interpreter.get_output_details()[0]
    assert list(inp['shape']) == [1, 320, 320, 3] and inp['dtype'] == np.float32
    assert list(out['shape']) == [1, 6300, 85] and out['dtype'] == np.float32
    interpreter.set_tensor(inp['index'], np.zeros(inp['shape'], dtype=np.float32))
    interpreter.invoke()
    assert np.isfinite(interpreter.get_tensor(out['index'])).all()
    import yaml
    names = yaml.safe_load((repo / 'data/coco128.yaml').read_text())['names']
    ASSETS.mkdir(parents=True, exist_ok=True)
    (ASSETS / model.name).write_bytes(model.read_bytes())
    metadata = dict(schemaVersion=1, model='YOLOv5n', upstream=UPSTREAM, tag='v7.0', commit=commit,
                    weightsUrl=url, weightsSha256=hashlib.sha256(weights.read_bytes()).hexdigest(),
                    sha256=hashlib.sha256(model.read_bytes()).hexdigest(), inputShape=[1, 320, 320, 3],
                    outputShape=[1, 6300, 85], inputType='FLOAT32', outputType='FLOAT32',
                    weightsType='FP16', coordinates='normalized_xywh', classes=[names[i] for i in range(80)],
                    trainedOn='COCO; original baseline weights', license='GPL-3.0 (YOLOv5 v7.0)',
                    runtime='LiteRT 1.4.2 CPU, two threads', onHardwareValidated=False)
    (ASSETS / 'yolov5n-baseline.json').write_text(json.dumps(metadata, indent=2) + '\n')
    (ASSETS / 'yolov5n-LICENSE.txt').write_bytes((repo / 'LICENSE').read_bytes())
    print('Baseline verified:', model.name, metadata['sha256'])


if __name__ == '__main__':
    main()
