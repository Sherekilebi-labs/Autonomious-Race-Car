#!/usr/bin/env python3
"""Signed ESP USB bench: read-only identity/settings, with optional zero-output DISARM load. Never arms/drives."""
import argparse,array,fcntl,hashlib,hmac,json,os,pathlib,secrets,select,sys,termios,time
from configure import ROOT,strict_load,firmware_source_id
from verify_steering_storage import FIELDS,TRACE

def bench(port,samples=50,exercise_stop=False):
    config=strict_load(ROOT/'maxbot.runtime.json');key=config['network']['pairingKey'].encode();sign=lambda s:hmac.new(key,s.encode(),hashlib.sha256).hexdigest();fd=os.open(port,os.O_RDWR|os.O_NOCTTY|os.O_NONBLOCK);buffer=b'';counts={};sizes={};telemetry=[]
    try:
        settings=termios.tcgetattr(fd);settings[0]=settings[1]=settings[3]=0;settings[2]=termios.CS8|termios.CREAD|termios.CLOCAL;settings[4]=settings[5]=termios.B115200;settings[6][termios.VMIN]=settings[6][termios.VTIME]=0;termios.tcsetattr(fd,termios.TCSANOW,settings)
        if sys.platform=='darwin':fcntl.ioctl(fd,0x80045402,array.array('I',[config['network']['baud']]))
        else:
            baud=getattr(termios,'B'+str(config['network']['baud']));settings[4]=settings[5]=baud;termios.tcsetattr(fd,termios.TCSANOW,settings)
        fcntl.ioctl(fd,termios.TIOCMBIC,array.array('i',[termios.TIOCM_DTR|termios.TIOCM_RTS]))
        def exchange(message,accept):
            nonlocal buffer
            data=(json.dumps(message)+'\n').encode();sent=time.monotonic_ns();offset=0
            while offset<len(data):
                if select.select([], [fd], [], 1)[1]:offset+=os.write(fd,data[offset:])
                else:raise TimeoutError('Serial write timeout')
            until=time.monotonic()+3
            while time.monotonic()<until:
                if not select.select([fd],[],[],.1)[0]:continue
                buffer+=os.read(fd,16384)
                if len(buffer)>65536:raise ValueError('Serial input overflow')
                while b'\n' in buffer:
                    raw,buffer=buffer.split(b'\n',1)
                    try:m=json.loads(raw)
                    except (ValueError,UnicodeDecodeError):continue
                    kind=m.get('type','unknown');counts[kind]=counts.get(kind,0)+1;sizes[kind]=max(sizes.get(kind,0),len(raw)+1)
                    if kind=='telemetry':telemetry.append(m)
                    if accept(m):return m,(time.monotonic_ns()-sent)/1e6
            raise TimeoutError('No matching signed ESP response')
        nonce=secrets.token_hex(16);sender=time.monotonic_ns()//1000000
        def valid_hello(m):
            if m.get('type')!='hello' or m.get('nonce')!=nonce:return False
            canonical=f"H|{m['boot']}|{m['time_ms']}|{nonce}|{m['calibration_id']}|{m['rx_ms']}|{m['tx_ms']}|{sender}|{m['owner_epoch']}|{m['owner_seq']}|{m['firmware_source_id']}"
            return m.get('sender_ms')==sender and hmac.compare_digest(sign(canonical),m.get('mac',''))
        hello,rtt=exchange({'v':1,'type':'hello','nonce':nonce,'sender_ms':sender,'mac':sign('H|'+nonce)},valid_hello);hello_received_ns=time.monotonic_ns()
        if hello['calibration_id']!=config['firmwareCalibrationId'] or hello['firmware_source_id']!=firmware_source_id():raise ValueError('Connected firmware/calibration identity differs from intended source')
        times=[];first=None
        for _ in range(samples):
            request=secrets.token_hex(16);m={'v':1,'type':'steering_tuning','request_id':request,'operation':'get','boot':'','issued_ms':0,'expires_ms':0,**{k:0 for k in FIELDS}};m['mac']=sign('|'.join(map(str,['ST','',request,'get',0,0,*[0 for k in FIELDS]])))
            def valid_reply(reply):
                if reply.get('type')!='steering_tuning_reply' or reply.get('request_id')!=request:return False
                text='|'.join(map(str,['STA',reply['boot'],request,reply['status'],reply['time_ms'],*[reply[k] for k in FIELDS],int(reply['saved']),int(reply['armed']),*[reply[k] for k in TRACE]]))
                if reply.get('diagnostics_version')==2:text+='|'+'|'.join(map(str,[2,int(reply['pid_active']),int(reply['feedback_valid']),reply['feedback_min_mdeg'],reply['feedback_max_mdeg'],reply['feedback_rejects'],reply['command_min_mdeg'],reply['command_max_mdeg']]))
                return hmac.compare_digest(sign(text),reply.get('mac',''))
            reply,elapsed=exchange(m,valid_reply)
            if reply['armed'] or reply['drive_pwm']!=0 or reply['steering_pwm']!=0:raise ValueError('Bench check requires disarmed zero outputs')
            if reply['boot']!=hello['boot']:raise ValueError('ESP rebooted during probe')
            if first and any(reply[k]!=first[k] for k in FIELDS):raise ValueError('Steering settings changed during probe')
            first=reply;times.append(elapsed);time.sleep(.02)
        application_times=[];application_rate=None;verified_telemetry=[]
        def telemetry_valid(t):
            if t.get('type')!='telemetry':return False
            fields=['boot','session','time_ms','seq'];text='T|'+'|'.join(str(t[k]) for k in fields)
            text+='|'+'|'.join(map(str,[int(t['armed']),int(t['estop']),t['steering_mdeg'],t['battery_mv'],t['pot_adc'],t['pwm'],t['fault'],int(t['manual_armed']),t['epoch'],t['expiry_ms'],t['application_loss'],t['motor_overruns'],t['deadline_rejected'],t['sensor_ms']]))
            return hmac.compare_digest(sign(text),t.get('mac',''))
        if exercise_stop:
            import threading
            trusted=[t for t in telemetry if telemetry_valid(t)]
            session=next((t['session'] for t in reversed(trusted) if t.get('session')),secrets.token_hex(16));epoch=max(0,hello['owner_epoch']+1);sequence=max(0,hello['owner_seq']+1);anchor=time.monotonic_ns();device_anchor=hello['tx_ms']+int((anchor-hello_received_ns)/1_000_000);sent_times={};seen=set();writer_errors=[]
            def write_commands():
                try:
                    for i in range(samples):
                        time.sleep(max(0,(anchor+i*20_000_000-time.monotonic_ns())/1e9));issued=device_anchor+(time.monotonic_ns()-anchor)//1_000_000;seq=sequence+i
                        m={'v':2,'type':'command','boot':hello['boot'],'session':session,'seq':seq,'epoch':epoch,'issued_ms':issued,'expires_ms':issued+250,'mode':'DISARM','steering_mdeg':0,'pwm':0,'source_state':'0','source_frame':'0','calibration_id':config['firmwareCalibrationId']}
                        canonical='|'.join(map(str,[2,m['boot'],session,epoch,seq,issued,issued+250,'DISARM',0,0,0,0,m['calibration_id']]))
                        m['mac']=sign(canonical);data=(json.dumps(m,separators=(',',':'))+'\n').encode();offset=0;sent_times[seq]=time.monotonic_ns()
                        while offset<len(data):
                            if not select.select([],[fd],[],1)[1]:raise TimeoutError('Command serial write timeout')
                            offset+=os.write(fd,data[offset:])
                except Exception as error:writer_errors.append(str(error))
            thread=threading.Thread(target=write_commands);thread.start();until=time.monotonic()+samples*.02+5
            while time.monotonic()<until and (thread.is_alive() or len(seen)<samples):
                if not select.select([fd],[],[],.05)[0]:continue
                buffer+=os.read(fd,16384)
                while b'\n' in buffer:
                    raw,buffer=buffer.split(b'\n',1)
                    try:r=json.loads(raw)
                    except (ValueError,UnicodeDecodeError):continue
                    kind=r.get('type','unknown');counts[kind]=counts.get(kind,0)+1;sizes[kind]=max(sizes.get(kind,0),len(raw)+1)
                    if kind=='telemetry' and telemetry_valid(r):
                        if r['armed'] or r['pwm']!=0:raise ValueError('Bench outputs unexpectedly armed/nonzero')
                        verified_telemetry.append(r)
                    if kind!='applied' or r.get('session')!=session or r.get('seq') not in sent_times or r['seq'] in seen:continue
                    text='|'.join(map(str,['A',r['boot'],session,r['seq'],r['epoch'],r['rx_ms'],r['validated_ms'],r['applied_ms'],r['expires_ms'],r['pwm']]))
                    if r['boot']!=hello['boot'] or r['epoch']!=epoch or r['pwm']!=0 or not hmac.compare_digest(sign(text),r.get('mac','')):raise ValueError('Invalid USB application acknowledgement')
                    seen.add(r['seq']);application_times.append((time.monotonic_ns()-sent_times[r['seq']])/1e6)
            thread.join(2)
            if writer_errors or len(seen)!=samples:raise ValueError('USB load check failed: '+json.dumps({'verified':len(seen),'expected':samples,'writer_errors':writer_errors,'received':counts,'seen_sequences':sorted(seen),'last_telemetry':{k:verified_telemetry[-1].get(k) for k in ['seq','epoch','deadline_rejected','application_loss','motor_overruns']} if verified_telemetry else None}))
            application_rate=None if len(sent_times)<2 else (len(sent_times)-1)*1e9/(max(sent_times.values())-min(sent_times.values()))
        return {'schemaVersion':1,'authenticated':True,'firmwareSourceId':hello['firmware_source_id'],'calibrationId':hello['calibration_id'],'boot':hello['boot'],'samples':samples,'baud':config['network']['baud'],'helloRoundTripMs':rtt,'settingsRoundTripMs':{'median':sorted(times)[len(times)//2],'p95':sorted(times)[max(0,__import__('math').ceil(.95*len(times))-1)]},'settings':{k:first[k] for k in FIELDS},'saved':first['saved'],'feedbackValid':first.get('feedback_valid'),'armed':False,'drivePwm':0,'steeringPwm':0,'motionSent':False,'armSent':False,'resetSent':False,'applicationSendRateHz':application_rate,'verifiedTelemetrySamples':len(verified_telemetry),'motorOverrunDelta':None if not verified_telemetry else max(t['motor_overruns'] for t in verified_telemetry)-min(t['motor_overruns'] for t in verified_telemetry),'applicationLossDelta':None if not verified_telemetry else max(t['application_loss'] for t in verified_telemetry)-min(t['application_loss'] for t in verified_telemetry),'disarmCommandsSent':len(application_times),'signedApplicationsVerified':len(application_times),'applicationRoundTripMs':None if not application_times else {'median':sorted(application_times)[len(application_times)//2],'p95':sorted(application_times)[max(0,__import__('math').ceil(.95*len(application_times))-1)]},'maxSerialPacketBytes':sizes,'receivedTypes':counts,'endpoint':'Mac USB requests to signed settings/application replies; not Pixel USB or physical motor latency'}
    finally:os.close(fd)
def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--port',required=True);parser.add_argument('--samples',type=int,default=50);parser.add_argument('--output',type=pathlib.Path);parser.add_argument('--exercise-stop',action='store_true',help='Also send repeated authenticated zero-output DISARM commands; never arms or drives');args=parser.parse_args()
    if not 1<=args.samples<=1000:parser.error('1–1000 samples required')
    result=bench(args.port,args.samples,args.exercise_stop);text=json.dumps(result,indent=2)+'\n'
    if args.output:args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_text(text);args.output.chmod(0o600)
    print(text,end='')
if __name__=='__main__':main()
