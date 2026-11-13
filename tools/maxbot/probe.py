#!/usr/bin/env python3
"""Read-only authenticated ESP identity probe. Never sends motion/arm/reset packets."""
import argparse,hashlib,hmac,json,secrets,socket,time
from configure import ROOT,strict_load,firmware_source_id
sign=lambda key,text:hmac.new(key.encode(),text.encode(),hashlib.sha256).hexdigest()
def probe(config):
    nonce=secrets.token_hex(16);sender=time.monotonic_ns()//1_000_000;key=config['network']['pairingKey'];host=config['network']['espHost']
    request={'v':1,'type':'hello','nonce':nonce,'sender_ms':sender,'mac':sign(key,'H|'+nonce)}
    with socket.socket(socket.AF_INET,socket.SOCK_DGRAM) as udp:
        udp.settimeout(2);udp.sendto(json.dumps(request).encode(),(host,config['network']['espPort']));raw,peer=udp.recvfrom(2048)
    m=json.loads(raw);text=f"H|{m['boot']}|{m['time_ms']}|{nonce}|{m['calibration_id']}|{m['rx_ms']}|{m['tx_ms']}|{sender}|{m['owner_epoch']}|{m['owner_seq']}|{m['firmware_source_id']}"
    if peer!=(socket.gethostbyname(host),config['network']['espPort']) or m['nonce']!=nonce or m['sender_ms']!=sender or not hmac.compare_digest(sign(key,text),m['mac']):raise ValueError('Unauthenticated reply')
    if m['calibration_id']!=config['firmwareCalibrationId']:raise ValueError('Actuator calibration mismatch')
    if m['firmware_source_id']!=firmware_source_id():raise ValueError('Runtime firmware source mismatch; regenerate/build/flash the intended version')
    return {'authenticated':True,'boot':m['boot'],'calibrationId':m['calibration_id'],'firmwareSourceId':m['firmware_source_id'],'motionSent':False,'stopBehaviorVerified':False}
if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--config',default=ROOT/'maxbot.runtime.json');a=p.parse_args();print(json.dumps(probe(strict_load(a.config)),indent=2))
