#!/usr/bin/env python3
"""Mac USB: authenticated steering read; --save-current verifies unchanged settings across reset. Never arms/drives."""
import argparse,array,fcntl,hashlib,hmac,json,os,pathlib,secrets,select,sys,termios,time
sys.path.insert(0,str(pathlib.Path(__file__).resolve().parent))
from configure import ROOT,strict_load
FIELDS=['kp_milli','ki_milli','kd_milli','deadband_mdeg','min_pwm','max_pwm','filter_millihz']
TRACE=['drive_pwm','target_mdeg','actual_mdeg','steering_pwm','p_milli','i_milli','d_milli','pot_adc']
def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--port',required=True);parser.add_argument('--save-current',action='store_true');parser.add_argument('--snapshot',type=pathlib.Path);parser.add_argument('--restore',type=pathlib.Path);args=parser.parse_args()
    if args.save_current and args.restore:parser.error('Choose either --save-current or --restore')
    c=strict_load(ROOT/'maxbot.runtime.json');key=c['network']['pairingKey'].encode()
    sign=lambda text:hmac.new(key,text.encode(),hashlib.sha256).hexdigest()
    fd=os.open(args.port,os.O_RDWR|os.O_NOCTTY|os.O_NONBLOCK)
    try:
        a=termios.tcgetattr(fd);a[0]=a[1]=a[3]=0;a[2]=termios.CS8|termios.CREAD|termios.CLOCAL;a[4]=a[5]=termios.B115200;a[6][termios.VMIN]=a[6][termios.VTIME]=0;termios.tcsetattr(fd,termios.TCSANOW,a)
        fcntl.ioctl(fd,0x80045402,array.array('I',[c['network']['baud']]))
        fcntl.ioctl(fd,termios.TIOCMBIC,array.array('i',[termios.TIOCM_DTR|termios.TIOCM_RTS]))
        buffer=b''
        def exchange(operation='get',prior=None):
            nonlocal buffer
            seen={};boots=set();panic=[];badmac=0
            request_id=secrets.token_hex(16);issued=0 if prior is None else prior['time_ms']+int((time.monotonic()-prior['_received'])*1000)-5
            m={'v':1,'type':'steering_tuning','request_id':request_id,'operation':operation,'boot':'' if prior is None else prior['boot'],'issued_ms':issued,'expires_ms':issued+1500 if prior else 0,**{k:prior[k] if prior else 0 for k in FIELDS}}
            m['mac']=sign('|'.join(map(str,['ST',m['boot'],request_id,operation,m['issued_ms'],m['expires_ms'],*[m[k] for k in FIELDS]])))
            packet=(json.dumps(m)+'\n').encode();deadline=time.monotonic()+8;last=0
            while time.monotonic()<deadline:
                if time.monotonic()-last>.3:os.write(fd,packet);last=time.monotonic()
                if not select.select([fd],[],[],.05)[0]:continue
                try:buffer+=os.read(fd,16384)
                except BlockingIOError:continue
                if len(buffer)>65536:buffer=buffer[-32768:]
                while b'\n' in buffer:
                    raw,buffer=buffer.split(b'\n',1)
                    try:r=json.loads(raw)
                    except (ValueError,UnicodeDecodeError):
                        line=raw.decode('utf-8',errors='replace')
                        if any(word in line for word in ['Guru','CORRUPT','Stack','rst:','Backtrace','assert']):panic.append(line[:160])
                        continue
                    kind=r.get('type','unknown');seen[kind]=seen.get(kind,0)+1
                    if 'boot' in r:boots.add(r['boot'])
                    if r.get('type')!='steering_tuning_reply' or r.get('request_id')!=request_id:continue
                    canonical='|'.join(map(str,['STA',r['boot'],request_id,r['status'],r['time_ms'],*[r[k] for k in FIELDS],int(r['saved']),int(r['armed']),*[r[k] for k in TRACE]]))
                    if r.get('diagnostics_version')==2:canonical+='|'+'|'.join(map(str,[2,int(r['pid_active']),int(r['feedback_valid']),r['feedback_min_mdeg'],r['feedback_max_mdeg'],r['feedback_rejects'],r['command_min_mdeg'],r['command_max_mdeg']]))
                    if not hmac.compare_digest(sign(canonical),r.get('mac','')):badmac+=1;continue
                    assert not r['armed'] and r['drive_pwm']==0 and r['steering_pwm']==0,'Verification requires disarmed zero outputs'
                    r['_received']=time.monotonic();return r
            raise RuntimeError('No authenticated steering-settings response; observed types='+json.dumps(seen)+', invalid reply signatures='+str(badmac)+', observed boot count='+str(len(boots))+', diagnostics='+json.dumps(panic[:8]))
        first=exchange();verified=False;restored=False
        if args.restore:
            snapshot=json.loads(args.restore.read_text());wanted=snapshot['settings'];target=dict(first);target.update({k:wanted[k] for k in FIELDS})
            if any(first[k]!=wanted[k] for k in FIELDS):
                applied=exchange('apply',target);assert applied['status']=='applied' and all(applied[k]==wanted[k] for k in FIELDS)
                first=exchange();assert all(first[k]==wanted[k] for k in FIELDS);restored=True
        if args.save_current:
            saved=exchange('save',first);assert saved['status']=='saved' and saved['saved'] and all(saved[k]==first[k] for k in FIELDS)
            # Verify temporary Apply independently of Save, then restore the exact saved values by reset.
            temporary=dict(saved);temporary['kp_milli']+=1 if temporary['kp_milli']<500000 else -1
            applied=exchange('apply',temporary);assert applied['status']=='applied' and not applied['saved'] and applied['kp_milli']==temporary['kp_milli']
            for _ in range(20):
                active=exchange();assert active['boot']==first['boot'] and active['kp_milli']==temporary['kp_milli'] and not active['saved']
                time.sleep(.05)
            fcntl.ioctl(fd,termios.TIOCMBIS,array.array('i',[termios.TIOCM_RTS]));time.sleep(.15)
            fcntl.ioctl(fd,termios.TIOCMBIC,array.array('i',[termios.TIOCM_RTS]));time.sleep(1);buffer=b''
            after=exchange();assert after['boot']!=first['boot'] and after['saved'] and all(after[k]==first[k] for k in FIELDS)
            verified=True
        report={'authenticated':True,'restoredTemporarySettings':restored,'settings':{k:first[k] for k in FIELDS},'savedBefore':first['saved'],'diagnostics':{k:first.get(k) for k in ['diagnostics_version','pid_active','target_mdeg','feedback_valid','feedback_min_mdeg','feedback_max_mdeg','feedback_rejects','command_min_mdeg','command_max_mdeg']},'temporaryApplyAndRepeatedReadsVerified':verified,'savedUnchangedAndVerifiedAfterHardwareReset':verified,'armed':False,'drivePwm':0,'steeringPwm':0,'motionSent':False,'armingSent':False}
        if args.snapshot:
            args.snapshot.write_text(json.dumps(report,indent=2)+'\n');args.snapshot.chmod(0o600)
        if verified:
            path=ROOT/'.tools/steering-storage-verification.json';path.write_text(json.dumps(report,indent=2)+'\n');path.chmod(0o600)
        print(json.dumps(report,indent=2))
    finally:os.close(fd)
if __name__=='__main__':main()
