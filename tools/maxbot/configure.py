#!/usr/bin/env python3
"""Generate Android/ESP configuration from versioned defaults, saved calibration, then private overrides."""
import argparse
import hashlib
import json
import math
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parents[2]
PWM_TEMPLATE = ROOT / 'firmware/common/actuator_pwm.h'

def strict_load(path):
    return json.loads(pathlib.Path(path).read_text(), parse_constant=lambda x: (_ for _ in ()).throw(ValueError(x)))

def validate_config(c):
    if not isinstance(c,dict) or (type(c.get('schemaVersion')) is not int or c.get('schemaVersion') != 1):
        raise ValueError('Unsupported configuration schema')
    def walk(value):
        if isinstance(value,float) and not math.isfinite(value): raise ValueError('Nonfinite configuration')
        if isinstance(value,dict):
            for item in value.values(): walk(item)
        if isinstance(value,list):
            if len(value)>4096: raise ValueError('Oversize array')
            for item in value: walk(item)
    walk(c)
    for field in ['car','camera','steering','power','manual','hardware','commissioning','network','limits','cones']:
        if not isinstance(c.get(field),dict): raise ValueError('Missing configuration object: '+field)

def pwm_ceiling(c):
    p=c['power']
    reference=p.get('pwmReferenceMaxV',8.4)
    supply=p.get('packMaxV',reference)
    if any(not isinstance(v,(int,float)) or isinstance(v,bool) or not math.isfinite(v) or v<=0 for v in [reference,supply]):return 0
    if supply>14:return 0  # TA6586 documented operating supply ceiling.
    return min(255,int(math.floor(255*reference/supply)))

def pwm_header(c):
    ceiling=pwm_ceiling(c)
    p=c['power'];reference=p.get('pwmReferenceMaxV',8.4);supply=p.get('packMaxV',reference)
    def mv(v):return int(round(v*1000)) if isinstance(v,(int,float)) and not isinstance(v,bool) and math.isfinite(v) else 0
    return ('// Generated motor-voltage mapping. Regenerate with tools/maxbot/configure.py.\n'
            f'#define DRIVE_MAX_DUTY {ceiling}\n#define STEER_MAX_DUTY {ceiling}\n'
            f'#define PWM_REFERENCE_MAX_MV {mv(reference)}\n#define PWM_SUPPLY_MAX_MV {mv(supply)}\n'
            +PWM_TEMPLATE.read_text())

def read_config(readme):
    blocks = re.findall(r'<!-- MAXBOT_CONFIG_START -->\s*```json\s*(.*?)\s*```\s*<!-- MAXBOT_CONFIG_END -->', readme, re.S)
    if len(blocks) != 1:
        raise ValueError('Exactly one README calibration block required')
    config = json.loads(blocks[0], parse_constant=lambda x: (_ for _ in ()).throw(ValueError(x)))
    if config.get('schemaVersion') != 1:
        raise ValueError('Unsupported calibration schema')
    return config

def merge(base, override):
    for key, value in override.items():
        if isinstance(value, dict) and isinstance(base.get(key), dict):
            merge(base[key], value)
        else:
            base[key] = value
    return base

def actuator_id(c):
    keys=['driveForwardPin','driveReversePin','steerForwardPin','steerReversePin','steerPotPin','batteryPin','physicalStopPin','steerPolarity','drivePolarity','driveBrakeVerified']
    data={'steering':c['steering'],'power':c['power'],'pwmMappingVersion':1,'manual':c.get('manual',{}),'hardware':{key:c['hardware'].get(key) for key in keys},'serialBaud':c['network'].get('baud')}
    return hashlib.sha256(json.dumps(data,sort_keys=True,separators=(',',':')).encode()).hexdigest()

def firmware_source_id():
    digest=hashlib.sha256()
    for name in ['firmware/maxbot/maxbot.ino','firmware/common/actuator_pwm.h','firmware/common/motion_deadline.h','firmware/common/motion_protocol.h','firmware/common/steering_controller.h','firmware/maxbot/steering_tuning.h']:
        path=ROOT/name
        if path.exists():digest.update(name.encode()+b'\0'+path.read_bytes())
    return digest.hexdigest()

def firmware_header(c):
    h,s,p = c['hardware'],c['steering'],c['power']
    monitor=p.get('batteryMonitoring',True)
    def num(obj,key,default):
        v=obj.get(key)
        return v if isinstance(v,(int,float)) and not isinstance(v,bool) and math.isfinite(v) else default
    keys=['driveForwardPin','driveReversePin','steerForwardPin','steerReversePin','steerPotPin','batteryPin','physicalStopPin']
    pins=[h.get(k) for k in keys]
    safe_outputs={4,13,14,16,17,18,19,21,22,23,25,26,27,32,33}
    safe_inputs=safe_outputs  # an optional physical stop needs an internal pull-up
    adc=s.get('adc');deg=s.get('degrees')
    table=isinstance(adc,list) and isinstance(deg,list) and len(adc)==len(deg) and len(adc)>=3
    if table:
        table=all(type(x) is int and 32<=x<=4063 for x in adc) and all(isinstance(x,(int,float)) and math.isfinite(x) for x in deg)
        table=table and all(deg[i]>deg[i-1] for i in range(1,len(deg))) and (all(adc[i]>adc[i-1] for i in range(1,len(adc))) or all(adc[i]<adc[i-1] for i in range(1,len(adc))))
        table=table and any(abs(x)<.01 for x in deg) and deg[0]==s.get('rightDeg') and deg[-1]==s.get('leftDeg')
    required=pins[:5]+(pins[5:6] if monitor is not False else [])
    stop=pins[6]
    ready=pwm_ceiling(c)>0 and type(monitor) is bool and all(type(x) is int for x in required) and len(set(required))==len(required) and all(x in safe_outputs for x in required[:4]) and required[4] in {32,33,34,35,36,39}
    ready=ready and (monitor is False or required[5] in {32,33,34,35,36,39})
    ready=ready and (stop is None or (type(stop) is int and stop in safe_inputs and stop not in required))
    ready=ready and h.get('steerPolarity') in {-1,1} and h.get('drivePolarity') in {-1,1} and table
    ready=ready and all(num(s,k,0)>0 for k in ['leftDeg','travelTimeS','kp','maxPwm','deadbandDeg']) and -65<num(s,'rightDeg',0)<0 and num(s,'leftDeg',90)<65
    ready=ready and num(s,'ki',-1)>=0 and num(s,'kd',-1)>=0 and num(s,'maxPwm',999)<=255
    if monitor is not False:
        ready=ready and p.get('senseSource','battery')=='battery'
        ready=ready and all(num(p,k,0)>0 for k in ['dividerRatio','adcReferenceMv','fullV','warningV','stopV']) and p['fullV']>p['warningV']>p['stopV']
        ready=ready and isinstance(p.get('adcOffsetMv'),(int,float)) and not isinstance(p.get('adcOffsetMv'),bool) and math.isfinite(p['adcOffsetMv'])
    ready=ready and len(c['network'].get('pairingKey',''))>=32
    baud=c['network'].get('baud',460800)
    ready=ready and baud in {230400,460800,921600}
    actuators_ready=bool(ready)
    manual=c.get('manual',{})
    manual_cap=manual.get('maxPwm',255)
    manual_ready=actuators_ready and manual.get('enabled') is True and type(manual_cap) is int and 1<=manual_cap<=255
    ready=actuators_ready and h.get('driveBrakeVerified') is True and c['commissioning'].get('powerVerified') is True and c['commissioning'].get('steeringMeasured') is True
    rows=['// Generated private calibration. Never commit this file.','#pragma once',f'#define MAXBOT_CALIBRATED {int(bool(ready))}',f'#define MAXBOT_ACTUATORS_READY {int(actuators_ready)}',f'#define MANUAL_COMMISSIONING_ENABLED {int(bool(manual_ready))}',f'#define MANUAL_MAX_PWM {manual_cap if type(manual_cap) is int and 1<=manual_cap<=255 else 0}',f'#define DRIVE_BRAKE_VERIFIED {int(h.get("driveBrakeVerified") is True)}',f'#define CALIBRATION_ID "{actuator_id(c)}"']
    rows.append(f'#define FIRMWARE_SOURCE_ID "{firmware_source_id()}"')
    rows.append(f'#define SERIAL_BAUD {baud if baud in {230400,460800,921600} else 460800}')
    rows.append(f'#define BATTERY_MONITORING_ENABLED {0 if monitor is False else 1}')
    rows.append(f'#define WIFI_AP_MODE {int(h.get("wifiMode","station")=="ap")}')
    macros=['DRIVE_F','DRIVE_R','STEER_F','STEER_R','POT_PIN','BATTERY_PIN','STOP_PIN']
    for key,macro in zip(keys+['steerPolarity','drivePolarity'],macros+['STEER_POLARITY','DRIVE_POLARITY']):
        value=-1 if key=='batteryPin' and monitor is False else int(num(h,key,0 if key.endswith('Polarity') else -1))
        rows.append(f'#define {macro} {value}')
    for key,macro in [('leftDeg','STEER_LEFT'),('rightDeg','STEER_RIGHT'),('kp','STEER_KP'),('ki','STEER_KI'),('kd','STEER_KD'),('deadbandDeg','STEER_DEADBAND'),('maxPwm','STEER_MAX_PWM')]:rows.append(f'#define {macro} {num(s,key,0)}')
    for key,macro in [('dividerRatio','DIVIDER_RATIO'),('adcReferenceMv','ADC_REFERENCE_MV'),('adcOffsetMv','ADC_OFFSET_MV'),('stopV','BATTERY_STOP_V')]:rows.append(f'#define {macro} {num(p,key,0)}')
    for value,macro in [(h.get('wifiSsid',''),'WIFI_SSID'),(h.get('wifiPassword',''),'WIFI_PASSWORD'),(c['network'].get('pairingKey',''),'PAIRING_KEY')]:rows.append(f'#define {macro} {json.dumps(value)}')
    rows += [f'#define STEER_POINTS {len(adc) if table else 3}', 'static const int STEER_ADC[] = {'+', '.join(map(str,adc if table else [1000,2000,3000]))+'};', 'static const float STEER_DEG[] = {'+', '.join(str(float(x))+'f' for x in (deg if table else [-1,0,1]))+'};']
    return '\n'.join(rows)+'\n', bool(ready)

def import_calibration(base, patch):
    allowed={'car','camera','manual','steering','power','hardware','commissioning'}
    if not isinstance(patch,dict) or not patch or set(patch)-allowed:
        raise ValueError('Calibration export must contain only car/camera/manual/steering/power/hardware/commissioning')
    # Never let a calibration export erase stored network credentials.
    if any(not isinstance(value,dict) for value in patch.values()):
        raise ValueError('Calibration sections must be objects')
    if set(patch.get('hardware',{})) & {'wifiSsid','wifiPassword'}:
        raise ValueError('Calibration exports must not contain Wi-Fi credentials')
    return merge(base,patch)

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--check',action='store_true')
    parser.add_argument('--import-calibration',type=pathlib.Path,help='Merge a calibration sketch JSON export into private maxbot.local.json, preserving credentials')
    args=parser.parse_args()
    defaults=ROOT/'maxbot/config/profiles/app-defaults.v1.json'
    c=strict_load(defaults) if defaults.exists() else read_config((ROOT/'README.md').read_text())
    validate_config(c)
    private=ROOT/'maxbot.local.json'
    saved=ROOT/'firmware/calibration/calibration.json'
    if saved.exists():
        recorded=json.loads(saved.read_text(),parse_constant=lambda x: (_ for _ in ()).throw(ValueError(x)))
        import_calibration(c,recorded)
        print('Saved calibration: firmware/calibration/calibration.json')
    local=strict_load(private) if private.exists() else {}
    if args.import_calibration:
        patch=json.loads(args.import_calibration.read_text(),parse_constant=lambda x: (_ for _ in ()).throw(ValueError(x)))
        import_calibration(local,patch)
        if not args.check:
            private.write_text(json.dumps(local,indent=2)+'\n');private.chmod(0o600)
    merge(c,local)
    validate_config(c)
    c['firmwareCalibrationId']=actuator_id(c)
    c['geometryId']=hashlib.sha256(json.dumps({'car':c['car'],'camera':{k:v for k,v in c['camera'].items() if k!='streamId'}},sort_keys=True,separators=(',',':'),allow_nan=False).encode()).hexdigest()
    header,ready=firmware_header(c)
    def unresolved(v,prefix=''):
        if c['power'].get('batteryMonitoring',True) is False and (prefix=='power.' or prefix=='hardware.batteryPin.'):
            return []
        if isinstance(v,dict):return sum((unresolved(x,prefix+k+'.') for k,x in v.items()),[])
        return [prefix[:-1]] if v is None and prefix!='hardware.physicalStopPin.' else []
    print('Missing measurements: '+(', '.join(unresolved(c)) or 'none'))
    if c['power'].get('batteryMonitoring',True) is False:
        print('Battery monitoring: DISABLED — manual voltage check before each run; no automatic low-voltage stop')
    elif c['power'].get('senseSource','battery')!='battery':
        print('Battery sensing: BLOCKED — regulated converter output does not measure pack voltage')
    if c['power'].get('batteryMonitoring',True) is not False and c['hardware'].get('batteryPin') not in {32,33,34,35,36,39}:
        print('Battery ADC: BLOCKED — select an ADC1 pin (32/33/34/35/36/39) for Wi-Fi operation')
    print('Autonomous actuator arming: '+('configured; complete phone preflight still required' if ready else 'LOCKED'))
    stop_behavior='electrical brake enabled' if c['hardware'].get('driveBrakeVerified') is True else 'coast; electrical brake unverified'
    print('Manual commissioning: '+('READY ('+stop_behavior+')' if '#define MANUAL_COMMISSIONING_ENABLED 1' in header else 'LOCKED — valid actuator calibration and pairing key required'))
    print(f'Motor PWM mapping: logical ±255 -> physical ±{pwm_ceiling(c)} for traction and steering')
    if not args.check:
        for path,contents in [(ROOT/'maxbot.runtime.json',json.dumps(c,indent=2)+'\n'),(ROOT/'robot/android/maxbot/src/main/assets/maxbot/calibration.json',json.dumps(c,indent=2)+'\n'),(ROOT/'firmware/maxbot/calibration.generated.h',header),(ROOT/'firmware/maxbot/actuator_pwm.generated.h',pwm_header(c)),(ROOT/'firmware/calibration/actuator_pwm.generated.h',pwm_header(c))]:
            path.parent.mkdir(parents=True,exist_ok=True);path.write_text(contents);path.chmod(0o600)
        schemas=ROOT/'maxbot/config/schemas'
        if schemas.exists():
            target=ROOT/'robot/android/maxbot/src/main/assets/maxbot/contracts';target.mkdir(parents=True,exist_ok=True)
            for schema in schemas.glob('*.json'):(target/schema.name).write_bytes(schema.read_bytes())
        print('Config sha256: '+hashlib.sha256(json.dumps(c,sort_keys=True).encode()).hexdigest())

if __name__=='__main__':main()
