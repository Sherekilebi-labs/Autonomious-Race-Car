#!/usr/bin/env python3
"""Package already-built, matching manual firmware/APK and an offline Mac console."""
import hashlib
import json
import pathlib
import shutil
import zipfile
from configure import pwm_ceiling

ROOT = pathlib.Path(__file__).resolve().parents[2]
OUT = ROOT / 'dist/manual-drive'


def main():
    config = json.loads((ROOT / 'maxbot.runtime.json').read_text())
    header = (ROOT / 'firmware/maxbot/calibration.generated.h').read_text()
    if '#define MANUAL_COMMISSIONING_ENABLED 1' not in header:
        raise ValueError('Manual firmware is not ready; generate valid actuator configuration first')
    apk = ROOT / 'robot/android/maxbot/build/outputs/apk/debug/maxbot-debug.apk'
    with zipfile.ZipFile(apk) as archive:
        embedded = json.loads(archive.read('assets/maxbot/calibration.json'))
    if embedded != config:
        raise ValueError('APK asset differs from current runtime configuration; rebuild the APK')
    firmware = OUT / 'esp32/maxbot.ino.bin'
    if config['firmwareCalibrationId'].encode() not in firmware.read_bytes():
        raise ValueError('ESP32 binary does not contain the matching calibration fingerprint; rebuild it')
    OUT.mkdir(parents=True, exist_ok=True)
    shutil.copy2(apk, OUT / 'maxbot-manual.apk')
    shutil.copy2(ROOT / 'maxbot.runtime.json', OUT / 'maxbot.runtime.json')
    sketch = OUT / 'sketch/maxbot'
    sketch.mkdir(parents=True, exist_ok=True)
    for name in ['maxbot.ino', 'calibration.generated.h', 'actuator_pwm.generated.h']:
        shutil.copy2(ROOT / 'firmware/maxbot' / name, sketch / name)
    console = ROOT / 'controller/node-js/maxbot'
    bundled = OUT / 'controller/node-js/maxbot'
    bundled.mkdir(parents=True, exist_ok=True)
    for name in ['server.js', 'hub.js', 'package.json', 'package-lock.json']:
        shutil.copy2(console / name, bundled / name)
    shutil.copytree(console / 'public', bundled / 'public', dirs_exist_ok=True)
    # Bundle the already-installed pure-JS dependency so the isolated hotspot
    # does not need internet/npm downloads. Its optional native addons are not used.
    shutil.copytree(console / 'node_modules/ws', bundled / 'node_modules/ws', dirs_exist_ok=True)
    fonts = OUT / 'controller/node-js/client/font/Gilroy'
    fonts.mkdir(parents=True, exist_ok=True)
    for name in ['Regular', 'Bold', 'Medium']:
        shutil.copy2(ROOT / f'controller/node-js/client/font/Gilroy/Gilroy-{name}.ttf', fonts)
    h, n = config['hardware'], config['network']
    details = ('Private local connection details\n\n'
               f'Wi-Fi SSID: {h.get("wifiSsid", "")}\n'
               f'Wi-Fi password: {h.get("wifiPassword", "")}\n'
               f'ESP32 UDP: {n["espHost"]}:{n["espPort"]}\n'
               f'Dashboard default: {n["dashboardUrl"]}\n'
               f'Browser pairing key: {n["pairingKey"]}\n\n'
               'The Wi-Fi password and pairing key are separate and survive resets.\n')
    (OUT / 'connection-details.txt').write_text(details)
    (OUT / 'connection-details.txt').chmod(0o600)
    (OUT / 'maxbot.runtime.json').chmod(0o600)
    launcher = """'use strict'
const fs=require('node:fs'), path=require('node:path'), os=require('node:os')
const config=JSON.parse(fs.readFileSync(path.join(__dirname,'maxbot.runtime.json'),'utf8'))
console.log('Join the ESP32 Wi-Fi network before connecting the Pixel.')
console.log('Mac browser: http://localhost:8081')
const addresses=Object.values(os.networkInterfaces()).flat().filter(a=>a.family==='IPv4'&&!a.internal)
const local=addresses.filter(a=>a.address.startsWith('192.168.4.'))
for(const a of local.length?local:addresses)console.log('Phone dashboard address: ws://'+a.address+':8081/robot')
console.log('Use the pairing key in connection-details.txt. Press Ctrl+C to stop.')
const {server}=require('./controller/node-js/maxbot/server').start({key:config.network.pairingKey,host:'0.0.0.0',port:8081})
server.on('error',e=>{console.error('Dashboard: '+e.message);process.exitCode=1})
process.on('SIGINT',()=>server.close())
process.on('SIGTERM',()=>server.close())
"""
    (OUT / 'launch-mac.cjs').write_text(launcher)
    (OUT / 'start-mac.command').write_text('''#!/bin/sh
set -eu
TASK_BUNDLE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
TASK_NODE=$(command -v node || true)
if [ -z "$TASK_NODE" ] && [ -x /opt/homebrew/bin/node ]; then TASK_NODE=/opt/homebrew/bin/node; fi
if [ -z "$TASK_NODE" ]; then echo "Node.js 20+ is required."; exit 1; fi
exec "$TASK_NODE" "$TASK_BUNDLE_DIR/launch-mac.cjs"
''')
    (OUT / 'flash-esp32.command').write_text('''#!/bin/sh
set -eu
TASK_BUNDLE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
TASK_ARDUINO_CLI="${MAXBOT_ARDUINO_CLI:-/Applications/Arduino IDE.app/Contents/Resources/app/lib/backend/resources/arduino-cli}"
if [ "$#" -ne 1 ]; then
  echo "Raise the wheels and disconnect traction power before flashing."
  echo "Usage: sh flash-esp32.command /dev/cu.YOUR_ESP32_PORT"
  "$TASK_ARDUINO_CLI" board list
  exit 1
fi
exec "$TASK_ARDUINO_CLI" upload --fqbn esp32:esp32:esp32 --port "$1" --input-dir "$TASK_BUNDLE_DIR/esp32" "$TASK_BUNDLE_DIR/sketch/maxbot"
''')
    for name in ['start-mac.command', 'flash-esp32.command']:
        (OUT / name).chmod(0o755)
    shutil.copy2(ROOT / 'docs/manual-drive.md', OUT / 'README.md')
    artifacts = {}
    for file in sorted(OUT.rglob('*')):
        if file.is_file() and file.name != 'manifest.json':
            artifacts[str(file.relative_to(OUT))] = hashlib.sha256(file.read_bytes()).hexdigest()
    manifest = dict(profile='manual-commissioning', calibrationId=config['firmwareCalibrationId'],
                    manualMaxPwm=config['manual']['maxPwm'], transport=n['transport'],
                    driveDutyMax=pwm_ceiling(config), steerDutyMax=pwm_ceiling(config),
                    packMaxV=config['power'].get('packMaxV',8.4),
                    brakeVerified=h['driveBrakeVerified'], batteryMonitoring=config['power']['batteryMonitoring'],
                    commissioning=config['commissioning'], artifacts=artifacts)
    (OUT / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    # Keep the zip beside the bundle, never recursively inside it.
    archive = shutil.make_archive(str(ROOT / 'dist/maxbot-manual-drive'), 'zip', OUT.parent, OUT.name)
    print('Prepared matching APK / firmware / offline console:', OUT.relative_to(ROOT))
    print('Private connection details included; secrets not printed.')
    print('Archive:', pathlib.Path(archive).relative_to(ROOT))


if __name__ == '__main__':
    main()
