#!/usr/bin/env python3
"""Package real local APK/ESP artifacts, never flash/install/arm. Credentials stay private."""
import hashlib,json,pathlib,shutil,os,re,zipfile
from configure import ROOT,strict_load,firmware_source_id,actuator_id

def main():
    c=strict_load(ROOT/'maxbot.runtime.json')
    if c['firmwareCalibrationId']!=actuator_id(c):raise ValueError('Regenerate matching private calibration first')
    destination=ROOT/'dist/task1-private';destination.mkdir(parents=True,exist_ok=True);destination.chmod(0o700)
    source=ROOT/'.tools/task1-runtime-build'
    compiled=(source/'sketch/calibration.generated.h').read_text()
    def macro(name):
        match=re.search(r'#define '+name+r' "([a-f0-9]{64})"',compiled)
        return match.group(1) if match else None
    if macro('FIRMWARE_SOURCE_ID')!=firmware_source_id() or macro('CALIBRATION_ID')!=c['firmwareCalibrationId']:raise ValueError('Rebuild the real runtime firmware before packaging')
    with zipfile.ZipFile(ROOT/'robot/android/maxbot/build/outputs/apk/debug/maxbot-debug.apk') as apk:
        if json.loads(apk.read('assets/maxbot/calibration.json'))['firmwareCalibrationId']!=c['firmwareCalibrationId']:raise ValueError('Rebuild matching APK calibration before packaging')
    files=[ROOT/'robot/android/maxbot/build/outputs/apk/debug/maxbot-debug.apk',source/'maxbot.ino.bin',source/'maxbot.ino.bootloader.bin',source/'maxbot.ino.partitions.bin']
    manifest={'schemaVersion':1,'private':True,'containsCredentials':True,'firmwareSourceId':firmware_source_id(),'calibrationId':c['firmwareCalibrationId'],'geometryId':c['geometryId'],'autonomyCommissioned':False,'deviceTested':False,'files':[]}
    for file in files:
        if not file.exists():raise ValueError('Build missing: '+str(file.relative_to(ROOT)))
        target=destination/file.name;shutil.copyfile(file,target);target.chmod(0o600);manifest['files'].append({'name':target.name,'size':target.stat().st_size,'sha256':hashlib.sha256(target.read_bytes()).hexdigest()})
    (destination/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n');(destination/'manifest.json').chmod(0o600)
    print('Private local deployment bundle: '+str(destination.relative_to(ROOT)))
    print('No flashing, installation, arming or motion performed.')
if __name__=='__main__':main()
