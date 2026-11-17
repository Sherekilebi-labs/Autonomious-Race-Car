#!/usr/bin/env python3
"""Package matching, locally built monitoring software; never install/flash/arm."""
import hashlib
import json
from pathlib import Path
import re
import shutil
import zipfile
from configure import ROOT, firmware_source_id, actuator_id

OUT = ROOT / 'dist/signal-monitor'


def digest(file):
    return hashlib.sha256(file.read_bytes()).hexdigest()


def main():
    runtime = json.loads((ROOT / 'maxbot.runtime.json').read_text())
    build = ROOT / '.tools/signal-monitor-firmware-build'
    header = (build / 'sketch/calibration.generated.h').read_text()
    for macro, value in [('FIRMWARE_SOURCE_ID', firmware_source_id()), ('CALIBRATION_ID', actuator_id(runtime))]:
        if not re.search(r'#define ' + macro + r' "' + re.escape(value) + '"', header):
            raise ValueError('Firmware build is stale: ' + macro)
    apk = ROOT / 'robot/android/maxbot/build/outputs/apk/debug/maxbot-debug.apk'
    assets = ROOT / 'robot/android/maxbot/src/main/assets/maxbot'
    model = json.loads((assets / 'yolov5n-baseline.json').read_text())
    with zipfile.ZipFile(apk) as archive:
        if json.loads(archive.read('assets/maxbot/calibration.json')) != runtime:
            raise ValueError('APK calibration is stale; rebuild')
        if hashlib.sha256(archive.read('assets/maxbot/yolov5n-fp16.tflite')).hexdigest() != model['sha256']:
            raise ValueError('APK does not contain the verified baseline model')
    OUT.mkdir(parents=True, exist_ok=True)
    OUT.chmod(0o700)
    apk_name = 'maxbot-0.6.1-timing-fix.apk'
    shutil.copy2(apk, OUT / apk_name)
    shutil.copy2(ROOT / 'maxbot.runtime.json', OUT / 'maxbot.runtime.json')
    firmware = OUT / 'esp32'
    firmware.mkdir(exist_ok=True)
    for name in ['maxbot.ino.bin', 'maxbot.ino.bootloader.bin', 'maxbot.ino.partitions.bin']:
        shutil.copy2(build / name, firmware / name)
    sketch = OUT / 'firmware/maxbot'
    sketch.mkdir(parents=True, exist_ok=True)
    for name in ['maxbot.ino', 'calibration.generated.h', 'actuator_pwm.generated.h', 'steering_tuning.h']:
        shutil.copy2(ROOT / 'firmware/maxbot' / name, sketch / name)
    shutil.copytree(ROOT / 'firmware/common', OUT / 'firmware/common', dirs_exist_ok=True)
    console = ROOT / 'controller/node-js/maxbot'
    bundled = OUT / 'controller/node-js/maxbot'
    bundled.mkdir(parents=True, exist_ok=True)
    for name in ['server.js', 'hub.js', 'package.json', 'package-lock.json']:
        shutil.copy2(console / name, bundled / name)
    for name in ['public', 'services', 'node_modules/ws']:
        shutil.copytree(console / name, bundled / name, dirs_exist_ok=True)
    shutil.copytree(ROOT / 'controller/node-js/client/font/Gilroy', OUT / 'controller/node-js/client/font/Gilroy', dirs_exist_ok=True)
    tools = OUT / 'tools/maxbot'
    tools.mkdir(parents=True, exist_ok=True)
    shutil.copy2(ROOT / 'tools/maxbot/signal-report.cjs', tools / 'signal-report.cjs')
    shutil.copy2(ROOT / 'docs/autonomy/signal-propagation.md', OUT / 'README.md')
    shutil.copy2(ROOT / 'docs/autonomy/signal-propagation-v0.6.1.md', OUT / 'signal-propagation-v0.6.1.md')
    shutil.copytree(ROOT / 'docs/autonomy/reports', OUT / 'reports', dirs_exist_ok=True)
    shutil.copy2(assets / 'yolov5n-baseline.json', OUT / 'model-provenance.json')
    shutil.copy2(assets / 'yolov5n-LICENSE.txt', OUT / 'yolov5n-LICENSE.txt')
    identity = dict(schemaVersion=1, profile='manual-signal-monitor', appVersion='0.6.1-timing-fix', versionCode=11,
                    calibrationId=runtime['firmwareCalibrationId'], firmwareSourceId=firmware_source_id(),
                    apkSha256=digest(apk), firmwareSha256=digest(build / 'maxbot.ino.bin'), modelSha256=model['sha256'],
                    deviceTested=False, actualMotorOnsetMeasured=False)
    (OUT / 'signal-deployment.json').write_text(json.dumps(identity, indent=2) + '\n')
    (OUT / 'launch-mac.cjs').write_text("""'use strict'
const fs=require('node:fs'),path=require('node:path')
const config=JSON.parse(fs.readFileSync(path.join(__dirname,'maxbot.runtime.json'),'utf8'))
const app=require('./controller/node-js/maxbot/server').start({key:config.network.pairingKey,espHost:config.network.espHost,host:'0.0.0.0',port:8081,discover:true})
console.log('MaxBot signal monitoring: http://localhost:8081')
console.log('Use the existing car pairing key. Reports save inside runs/signal-propagation/.')
app.server.on('error',e=>{console.error(e.code==='EADDRINUSE'?'Port 8081 is in use. Stop the other dashboard and retry.':e.message);process.exitCode=1})
let stopping=false
function stop(){if(stopping)return;stopping=true;app.hub.send(app.hub.robot,{type:'operator_left'});app.signals.stop('server_shutdown');setTimeout(()=>{for(const peer of app.websocket.clients)peer.close(1001,'Dashboard stopped');app.server.close()},2200)}
process.on('SIGTERM',stop);process.on('SIGINT',stop)
""")
    (OUT / 'start-mac.command').write_text('''#!/bin/sh
set -eu
TASK_SIGNAL_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
TASK_SIGNAL_NODE=$(command -v node || true)
if [ -z "$TASK_SIGNAL_NODE" ] && [ -x /opt/homebrew/bin/node ]; then TASK_SIGNAL_NODE=/opt/homebrew/bin/node; fi
if [ -z "$TASK_SIGNAL_NODE" ]; then echo 'Node.js 20+ is required.'; exit 1; fi
exec "$TASK_SIGNAL_NODE" "$TASK_SIGNAL_DIR/launch-mac.cjs"
''')
    (OUT / 'flash-esp32.command').write_text('''#!/bin/sh
set -eu
TASK_SIGNAL_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
TASK_SIGNAL_CLI="${MAXBOT_ARDUINO_CLI:-/Applications/Arduino IDE.app/Contents/Resources/app/lib/backend/resources/arduino-cli}"
if [ "$#" -ne 1 ]; then
  echo 'Usage: sh flash-esp32.command /dev/cu.YOUR_ESP32_PORT'
  echo 'Use the normal commissioning procedure with motor power disconnected.'
  exit 1
fi
exec "$TASK_SIGNAL_CLI" upload --fqbn esp32:esp32:esp32 --port "$1" --input-dir "$TASK_SIGNAL_DIR/esp32" "$TASK_SIGNAL_DIR/firmware/maxbot"
''')
    for file in OUT.rglob('*'):
        file.chmod(0o700 if file.is_dir() or file.name.endswith('.command') else 0o600)
    files = {str(f.relative_to(OUT)): dict(bytes=f.stat().st_size, sha256=digest(f)) for f in sorted(OUT.rglob('*')) if f.is_file() and f.name != 'manifest.json' and 'runs' not in f.relative_to(OUT).parts}
    (OUT / 'manifest.json').write_text(json.dumps(dict(**identity, containsCredentials=True, files=files), indent=2) + '\n')
    (OUT / 'manifest.json').chmod(0o600)
    archive = Path(shutil.make_archive(str(ROOT / 'dist/maxbot-signal-monitor'), 'zip', OUT.parent, OUT.name))
    archive.chmod(0o600)
    print('Private monitoring bundle:', OUT.relative_to(ROOT))
    print('Archive:', archive.relative_to(ROOT))
    print('Not installed, flashed or hardware-tested.')


if __name__ == '__main__':
    main()
