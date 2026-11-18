#!/usr/bin/env python3
"""Package private 0.7 experiment APKs, dashboard and MCP. Does not install or flash devices."""
import hashlib,json,pathlib,shutil
from configure import firmware_source_id
ROOT=pathlib.Path(__file__).resolve().parents[2]
OUT=ROOT/'dist/automation'
def main():
    runtime=json.loads((ROOT/'maxbot.runtime.json').read_text())
    if runtime['network']['transport']!='usb':raise ValueError('Generate the USB configuration first')
    OUT.mkdir(parents=True,exist_ok=True);OUT.chmod(0o700)
    for name in ['usb-experiments.md','policy-training-pipeline.md']:(OUT/name).unlink(missing_ok=True)
    for module,name in [('maxbot','maxbot-0.7.3-timing.apk'),('maxbot-observer','maxbot-observer-0.7.2-rotation-fix.apk')]:
        source=ROOT/f'robot/android/{module}/build/outputs/apk/debug/{module}-debug.apk'
        if not source.exists():raise ValueError(f'Build {module} first')
        if module=='maxbot':
            import zipfile
            with zipfile.ZipFile(source) as archive:
                if json.loads(archive.read('assets/maxbot/calibration.json'))!=runtime:raise ValueError('APK calibration is stale; rebuild')
        shutil.copy2(source,OUT/name)
    for name in ['maxbot.runtime.json','tools/maxbot/dashboard.cjs','tools/maxbot/experiment_analysis.py','tools/maxbot/policy_search.py','tools/maxbot/train.py','tools/maxbot/dataset.py','tools/maxbot/planner.py','tools/maxbot/requirements-automation.txt','tools/maxbot/requirements-training.txt','tools/maxbot/requirements-planner.txt','maxbot/config/profiles/course-indoor-corridor.v1.json','robot/android/maxbot/src/main/java/org/maxbot/app/ExperimentRunner.java','robot/android/maxbot-core/src/main/java/org/maxbot/core/ExperimentSequence.java','robot/android/maxbot/build.gradle','firmware/maxbot/maxbot.ino']:
        target=OUT/name;target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(ROOT/name,target)
    backend='controller/node-js/maxbot'
    for name in ['server.js','hub.js','mcp.cjs','package.json','package-lock.json']:
        target=OUT/backend/name;target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(ROOT/backend/name,target)
    for name in ['public','services','node_modules/ws']:shutil.copytree(ROOT/backend/name,OUT/backend/name,dirs_exist_ok=True)
    shutil.copytree(ROOT/'controller/node-js/client/font/Gilroy',OUT/'controller/node-js/client/font/Gilroy',dirs_exist_ok=True)
    shutil.copytree(ROOT/'docs/autonomy',OUT/'docs/autonomy',dirs_exist_ok=True)
    shutil.copy2(ROOT/'docs/PROJECT_STATUS_AND_ROADMAP.md',OUT/'docs/PROJECT_STATUS_AND_ROADMAP.md')
    (OUT/'README.md').write_text('# MaxBot 0.7 private experiment bundle\n\n[Setup and experiment guide](docs/autonomy/usb-experiments.md) · [USB bench evidence](docs/autonomy/reports/2026-11-18-usb-migration-review.md)\n\nInstall maxbot-0.7.3-timing.apk on the car Pixel and maxbot-observer-0.7.2-rotation-fix.apk on the observer phone. The connected ESP has already been flashed and verified; do not reflash merely to start the dashboard.\n\nUse start-mac.command, status-mac.command and stop-mac.command. This folder contains the private pairing key.\n')
    for operation in ['start','stop','status']:
        target=OUT/f'{operation}-mac.command';target.write_text('#!/bin/sh\nset -eu\nTASK_AUTOMATION_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)\nTASK_AUTOMATION_NODE=$(command -v node || true)\nif [ -z "$TASK_AUTOMATION_NODE" ]; then TASK_AUTOMATION_NODE=/opt/homebrew/bin/node; fi\nexec "$TASK_AUTOMATION_NODE" "$TASK_AUTOMATION_DIR/tools/maxbot/dashboard.cjs" '+operation+'\n')
    firmware=ROOT/'.tools/usb-experiments-firmware-build'
    header=(firmware/'sketch/calibration.generated.h').read_text()
    if runtime['firmwareCalibrationId'] not in header or firmware_source_id() not in header:raise ValueError('Firmware build is stale')
    (OUT/'esp32').mkdir(exist_ok=True)
    for file in ['maxbot.ino.bin','maxbot.ino.bootloader.bin','maxbot.ino.partitions.bin']:shutil.copy2(firmware/file,OUT/'esp32'/file)
    deployment={'schemaVersion':1,'appVersion':'0.7.3-timing','versionCode':13,'observerAppVersion':'0.7.2-rotation-fix','calibrationId':runtime['firmwareCalibrationId'],'firmwareSourceId':firmware_source_id(),'apkSha256':hashlib.sha256((OUT/'maxbot-0.7.3-timing.apk').read_bytes()).hexdigest(),'firmwareSha256':hashlib.sha256((firmware/'maxbot.ino.bin').read_bytes()).hexdigest(),'pixelDeviceTested':False,'espUsbBenchTested':True}
    for name in ['calibration.generated.h','actuator_pwm.generated.h','steering_tuning.h']:shutil.copy2(ROOT/'firmware/maxbot'/name,OUT/'firmware/maxbot'/name)
    shutil.copytree(ROOT/'firmware/common',OUT/'firmware/common',dirs_exist_ok=True)
    (OUT/'signal-deployment.json').write_text(json.dumps(deployment,indent=2)+'\n')
    identity={'schemaVersion':1,'version':'0.7.0','containsCredentials':True,'hardwareValidation':'Mac USB signed disarmed 50 Hz bench passed; Pixel USB and observer deployment need device validation','firmwareReflashRequired':False,'connectedEspFlashedAndBenchVerified':True,'files':{}}
    for file in sorted(OUT.rglob('*')):
        if file.is_dir():file.chmod(0o700)
        elif file.name!='manifest.json' and 'runs' not in file.relative_to(OUT).parts and '.tools' not in file.relative_to(OUT).parts:
            file.chmod(0o700 if file.suffix=='.command' else 0o600);identity['files'][str(file.relative_to(OUT))]={'bytes':file.stat().st_size,'sha256':hashlib.sha256(file.read_bytes()).hexdigest()}
    (OUT/'manifest.json').write_text(json.dumps(identity,indent=2)+'\n');(OUT/'manifest.json').chmod(0o600)
    print('Private bundle:',OUT);print('No device installed, no firmware flashed, no motion sent.')
if __name__=='__main__':main()
