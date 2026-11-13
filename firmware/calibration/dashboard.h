#pragma once
#include <Arduino.h>
static const char CALIBRATION_PAGE[] PROGMEM = R"MAXBOT(<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>MaxBot bench calibration</title><style>
:root{color-scheme:light;--blue:#0064b2;--red:#ae1829;--ink:#182530;--muted:#4c606e;--line:#cbd4dc;--bg:#f5f7f9}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--ink);font:16px/1.5 Arial,sans-serif}
header{position:sticky;top:0;z-index:3;background:white;border-bottom:1px solid var(--line);padding:12px 28px;display:flex;justify-content:space-between;align-items:center;gap:16px}
h1{font-size:24px;letter-spacing:-.025em;margin:0}h2{font-size:21px;margin:0 0 10px}h3,legend{font-size:17px;font-weight:700;margin:0 0 8px}p{max-width:72ch;margin:8px 0 14px}.muted,small{color:var(--muted)}#status{font-size:14px;color:var(--muted)}#headerFault{color:var(--red);font-size:14px;max-width:70ch}
main{max-width:1240px;margin:auto;padding:24px 28px;display:grid;grid-template-columns:minmax(0,1.6fr) minmax(0,1fr);gap:28px;align-items:start}
.wide{grid-column:1/-1}.panel{background:white;padding:22px;margin-bottom:24px}.panel:last-child{margin-bottom:0}fieldset{border:0;border-top:1px solid var(--line);margin:24px 0 0;padding:20px 0 0;min-width:0}
.notice{background:#fff2d3;color:#573c00;padding:12px 16px;margin-bottom:16px}aside{position:sticky;top:120px}
.row{display:flex;flex-wrap:wrap;align-items:end;gap:12px;margin:12px 0}.row>label{flex:1;min-width:105px}
label{display:block;font-size:14px;color:var(--muted)}input,select,textarea,button{font:inherit;border-radius:6px}
input:not([type=checkbox]):not([type=range]),select,textarea{display:block;width:100%;padding:9px;border:1px solid #8293a0;background:white;color:var(--ink);caret-color:var(--blue)}
input[type=range]{width:100%;accent-color:var(--blue);min-height:32px}input[type=checkbox]{height:20px;width:20px;vertical-align:middle;margin-right:8px;accent-color:var(--blue)}.check{color:var(--ink);margin:12px 0}
button{min-height:44px;border:1px solid #8b9da9;background:white;color:var(--ink);padding:9px 14px;cursor:pointer}
button:hover{background:#edf3f8}button:active{background:#dce7ef}button:disabled{opacity:.45;cursor:not-allowed}
.primary{background:var(--blue);border-color:var(--blue);color:white}.primary:hover{background:#004e8b}.danger{background:var(--red);border-color:var(--red);color:white;font-weight:700}.danger:hover{background:#8e1020}
.buttons{display:flex;flex-wrap:wrap;gap:12px;margin:12px 0;align-items:start}.buttons>.action{flex:1 1 160px}
.action{display:inline-flex;flex-direction:column;min-width:0;max-width:100%;vertical-align:top}.action-message{display:block;font-size:14px;line-height:1.4;max-width:42ch;margin-top:6px;overflow-wrap:anywhere;color:var(--blue)}.action-message:empty{display:none}.action-message.error{color:var(--red);font-weight:600}
:focus-visible{outline:3px solid var(--blue);outline-offset:3px}::selection{background:#c3e0f6;color:var(--ink)}
dl{display:grid;grid-template-columns:minmax(0,1fr) auto;gap:0 12px;margin:0}dt,dd{padding:6px 0;border-bottom:1px solid var(--line)}dt{color:var(--muted)}dd{margin:0;font-weight:700;text-align:right;overflow-wrap:anywhere}
output,dd,table{font-variant-numeric:tabular-nums}.result{background:var(--bg);padding:10px 12px;display:block;margin:12px 0;overflow-wrap:anywhere}
.row output{display:block;font-size:18px;padding:9px 0}dt{min-width:0;padding-right:10px}
table{width:100%;border-collapse:collapse}td,th{padding:8px;text-align:left;border-bottom:1px solid var(--line)}th{font-size:14px;color:var(--muted)}td button{font-size:14px}
canvas{display:block;width:100%;height:110px;background:var(--bg);margin-top:12px}#pidHistory{height:150px}meter{width:100%;height:20px;accent-color:var(--blue)}
textarea{min-height:160px;font:13px/1.5 monospace}details{margin:20px 0}summary{cursor:pointer;color:var(--blue);font-weight:600}.note{font-size:14px;color:var(--muted)}a{color:var(--blue);text-underline-offset:3px}
@media(max-width:850px){main{display:block;padding:16px}aside{position:static}.panel{padding:18px;margin-bottom:20px}header{padding:10px 16px}h1{font-size:19px}.row{gap:8px}header .action{max-width:160px}#headerFault{font-size:12px}dd{max-width:180px}}
@media(prefers-reduced-motion:no-preference){button{transition:background-color .15s}}
</style></head><body>
<header><div><h1>MaxBot bench calibration</h1><div id="status" role="status">Connecting to ESP32…</div><div id="headerFault"></div></div><button id="estop" class="danger">STOP / Escape</button></header>
<main>
<section class="panel wide" aria-labelledby="benchTitle"><h2 id="benchTitle">Bench outputs</h2>
<div class="notice">Raise all four wheels. Steering feedback is on GPIO35. Confirm the motor supply and ADC voltage before enabling outputs. Download measurements before unplugging or flashing.</div>
<label class="check"><input id="bench" type="checkbox">Wheels raised, motor supply checked, feedback voltage verified</label>
<div class="buttons"><button id="arm" class="primary">Enable bench outputs</button><button id="disarm">Disable outputs</button><button id="reset">Reset stop / fault</button></div>
<p id="readiness" class="note" role="status">Waiting for live feedback.</p></section>
<div>
<section class="panel" aria-labelledby="pwmTitle"><h2 id="pwmTitle">PWM calibration · PID bypass</h2>
<p>These buttons send PWM directly to the chosen H-bridge. Kp, Ki and Kd have no effect here. Start with a short pulse, then adjust PWM until the motor reliably starts. Record the lowest reliable value in each direction.</p>
<fieldset><legend>Steering motor</legend><p class="note">IN1 → GPIO22 · IN2 → GPIO23 · feedback → GPIO35</p>
<div class="row"><label>PWM / 255<input id="steerPwm" type="number" min="1" max="255" value="45"></label><label>Pulse duration (ms)<input id="steerMs" type="number" min="20" max="250" value="100"></label><label>Duty cycle<output id="steerDuty">17.6%</output></label></div>
<label for="steerRange">Steering PWM adjustment</label><input id="steerRange" type="range" min="1" max="255" value="45">
<div class="buttons"><button id="steerIn1" data-motion>Pulse steering IN1</button><button id="steerIn2" data-motion>Pulse steering IN2</button></div>
<output id="steerPulseResult" class="result">No steering pulse completed yet.</output>
<label class="check"><input id="steerObserved" type="checkbox">The last steering pulse reliably started the motor</label>
<button id="recordSteer">Record last pulse as starting PWM</button>
<dl><dt>IN1 starting PWM</dt><dd id="steerStart1">Not measured</dd><dt>IN2 starting PWM</dt><dd id="steerStart2">Not measured</dd></dl>
<p id="probe" class="note">Both input directions must show opposite ADC movement to derive steering polarity.</p></fieldset>
<fieldset><legend>Traction motor</legend><p class="note">IN1 → GPIO4 · IN2 → GPIO16 · direction and movement are visually confirmed</p>
<div class="row"><label>PWM / 255<input id="drivePwm" type="number" min="1" max="255" value="35"></label><label>Pulse duration (ms)<input id="driveMs" type="number" min="20" max="250" value="80"></label><label>Duty cycle<output id="driveDuty">13.7%</output></label></div>
<label for="driveRange">Traction PWM adjustment</label><input id="driveRange" type="range" min="1" max="255" value="35">
<div class="buttons"><button id="driveIn1" data-motion>Pulse drive IN1</button><button id="driveIn2" data-motion>Pulse drive IN2</button></div>
<output id="drivePulseResult" class="result">No traction pulse completed yet.</output>
<label class="check"><input id="driveObserved" type="checkbox">I observed the last traction pulse reliably start the wheels</label>
<button id="recordDrive">Record last pulse as starting PWM</button>
<dl><dt>IN1 starting PWM</dt><dd id="driveStart1">Not measured</dd><dt>IN2 starting PWM</dt><dd id="driveStart2">Not measured</dd></dl>
<label>Which input drives the car forward?<select id="driveDirection"><option value="0">Not observed yet</option><option value="1">IN1 / GPIO4</option><option value="-1">IN2 / GPIO16</option></select></label>
<div class="buttons"><button id="saveDirection">Record forward direction</button><button id="stopPwm">Stop current test</button></div>
<p class="note">PWM 255 is full duty cycle. Every open-loop pulse ends within 250 ms. Measured starting PWM is exported as calibration metadata; it does not silently change the PID output.</p></fieldset>
</section>
<section class="panel" aria-labelledby="anglesTitle"><h2 id="anglesTitle">Wheel-angle calibration</h2>
<p>Measure front-wheel angle: negative right, zero straight, positive left. Capture at least three monotonic points; five or more are better. Use measured wheel angles, not the servo's shaft travel.</p>
<div class="row"><label>Measured wheel angle (°)<input id="captureAngle" type="number" min="-64" max="64" step="any" value="0"></label><button id="capture">Capture stable ADC</button></div>
<table><thead><tr><th>Wheel angle</th><th>ADC</th><th>Action</th></tr></thead><tbody id="points"><tr data-empty><td colspan="3">No measurements captured.</td></tr></tbody></table>
<div class="buttons"><button id="derive">Derive steering polarity</button><button id="clearPoints">Clear angle measurements</button></div>
<p id="angleReady" class="note">Capture right, center and left, then derive polarity.</p></section>
<section class="panel" aria-labelledby="pidTitle"><h2 id="pidTitle">Steering PID tuning</h2>
<p>Enter any finite, nonnegative gains. The PID demand may exceed 255; only the final signed PWM is clipped. The output cap defaults to 255. Derivative acts on measured angle with a 20 ms filter; integration pauses when pushing farther into saturation.</p>
<div class="row"><label>Kp<input id="kp" type="number" min="0" step="any" value="4"></label><label>Ki<input id="ki" type="number" min="0" step="any" value="0"></label><label>Kd<input id="kd" type="number" min="0" step="any" value="0"></label></div>
<div class="row"><label>Output cap (1–255)<input id="maxPwm" type="number" min="1" max="255" value="255"></label><label>Deadband (°)<input id="deadbandDeg" type="number" min="0.1" max="5" step="any" value="1"></label></div>
<div class="row"><label>Target wheel angle (°)<input id="target" type="number" min="-64" max="64" step="any" value="0"></label><label>Test duration (ms)<input id="pidMs" type="number" min="100" max="5000" value="3000"></label></div>
<div class="buttons"><button id="targetTest" class="primary" data-motion>Run PID test</button><button id="stopPid">Stop PID test</button><button id="saveTuning">Record PID tuning</button></div>
<dl><dt>Raw PID demand</dt><dd id="pidRaw">—</dd><dt>Clipped PID PWM</dt><dd id="pidClipped">—</dd><dt>Actual steering bridge PWM</dt><dd id="pidApplied">—</dd><dt>P / I / D terms</dt><dd id="pidTerms">—</dd><dt>Output saturation</dt><dd id="pidSaturation">—</dd></dl>
<canvas id="pidHistory" width="600" height="150" aria-label="Applied steering PWM over recent telemetry samples"></canvas>
<p class="note">Blue trace: applied bridge PWM, −255 to +255. The target stays controlled for the test duration, including after entering the deadband. Stop, feedback limits and supervision still apply.</p>
<label>Measured right-to-left travel time (s)<input id="travelTimeS" type="number" min="0.01" max="10" step="any" placeholder="Measure at the selected operating PWM"></label><button id="saveTravel">Record travel time</button></section>
<section class="panel"><h2>Battery and physical stop</h2><p>Enter only installed input pins. Disable outputs before changing them.</p>
<div class="row"><label>Battery ADC1 pin<select id="batteryPin"><option value="-1">Not wired</option><option>32</option><option>33</option><option>34</option><option>36</option><option>39</option></select></label><label>Active-low stop pin<select id="stopPin"><option value="-1">Not wired</option><option>13</option><option>14</option><option>17</option><option>18</option><option>19</option><option>21</option><option>25</option><option>26</option><option>27</option><option>32</option><option>33</option></select></label></div>
<button id="pins">Apply installed input pins</button>
<div class="row"><label>Battery divider ratio<input id="dividerRatio" type="number" min="1" max="100" step="any" placeholder="(Rtop + Rbottom) / Rbottom"></label><label>Full pack voltage (V)<input id="fullV" type="number" min="1" max="12" step="any"></label></div>
<div class="row"><label>Warning voltage (V)<input id="warningV" type="number" min="1" max="12" step="any"></label><label>Stop voltage (V)<input id="stopV" type="number" min="1" max="12" step="any"></label></div>
<h3>Two-point voltage calibration</h3><label>Multimeter pack reading (V)<input id="meterV" type="number" min="1" max="12" step="any"></label>
<div class="buttons"><button id="batterySample">Capture voltage sample</button><button id="savePower">Fit ADC and record thresholds</button></div><p id="batterySamples" class="note">No voltage samples.</p>
<details><summary>Verified electrical brake test</summary><p>Check the TA6586 board truth table and current behavior first. Ordinary calibration stops coast with both inputs low.</p><label class="check"><input id="brakeConfirmed" type="checkbox">Both-high brake behavior/current has been verified</label><button id="brake" data-motion>Apply brake for 50 ms</button></details></section>
</div>
<aside class="panel"><h2>Live feedback</h2><dl><dt>Outputs</dt><dd id="armed">Unknown</dd><dt>Fault</dt><dd id="fault">—</dd><dt>Steering ADC</dt><dd id="adc">—</dd><dt>ADC voltage</dt><dd id="mv">—</dd><dt>ADC spread / 80 ms</dt><dd id="spread">—</dd><dt>Wheel angle</dt><dd id="angle">Not calibrated</dd><dt>Steering bridge PWM</dt><dd id="appliedSteer">—</dd><dt>Traction bridge PWM</dt><dd id="appliedDrive">—</dd><dt>Battery ADC</dt><dd id="batteryAdc">Not wired</dd><dt>Battery voltage</dt><dd id="battery">Not calibrated</dd><dt>Physical stop</dt><dd id="physicalStop">Not wired</dd><dt>Steering polarity</dt><dd id="polarity">Not measured</dd><dt>Drive polarity</dt><dd id="drivePolarity">Not measured</dd></dl>
<label for="adcMeter">Potentiometer position</label><meter id="adcMeter" min="0" max="4095" value="0"></meter><canvas id="history" width="600" height="110" aria-label="Recent raw steering ADC readings"></canvas>
<p id="feedback" class="note" role="status">Waiting for telemetry. Action results also appear next to their buttons.</p></aside>
<section class="panel wide"><h2>Export calibration</h2><p>Download before resetting or flashing. Angle captures, PID settings and observed starting PWM are included. Commissioning remains unconfirmed.</p>
<div class="buttons"><button id="download" class="primary">Download calibration JSON</button><button id="showExport">Show JSON</button></div><textarea id="export" readonly aria-label="Calibration export JSON"></textarea></section>
</main><script>
'use strict';
const TOKEN='__MAXBOT_TOKEN__',el=id=>document.getElementById(id);
let state=null,lastState=0,sequence=0,formLoaded=false,heartbeatBusy=false,eventRevision=-1,messageId=0;
const messages=new Map(),pending=new Map(),pointRows=new Map(),samples=[],pidSamples=[];
function messageFor(button){
 if(messages.has(button))return messages.get(button);
 const box=document.createElement('span'),msg=document.createElement('span');box.className='action';msg.className='action-message';
 msg.id=(button.id||'action'+(++messageId))+'Message';msg.setAttribute('aria-live','polite');button.setAttribute('aria-describedby',msg.id);
 if(button.parentNode){button.parentNode.insertBefore(box,button);box.append(button,msg)}
 messages.set(button,msg);return msg;
}
function feedback(button,text,error=false){const msg=messageFor(button);msg.textContent=text;msg.className='action-message'+(error?' error':'')}
function number(id){const value=el(id).value;if(value==='')throw Error('Enter '+id+' first.');const n=Number(value);if(!Number.isFinite(n))throw Error('Enter a finite number for '+id+'.');return n}
function fresh(){return state!==null&&performance.now()-lastState<600}
async function request(url,options={}){
 const controller=new AbortController(),timer=setTimeout(()=>controller.abort(),1000);
 try{const response=await fetch(url,{...options,cache:'no-store',signal:controller.signal});const data=await response.json();if(!response.ok)throw Error(data.error||'ESP32 rejected this request.');return data}
 catch(e){if(e.name==='AbortError')throw Error('ESP32 request timed out. Check Wi-Fi and retry.');throw e}finally{clearTimeout(timer)}
}
function deliver(item,text,error,revision,complete){if(item.abandoned||(item.complete&&!complete)||revision<(item.revision||0))return;item.revision=revision;item.received=true;item.complete=complete;feedback(item.button,text,error)}
function action(name,fields={},button=null){
 const seq=++sequence;if(button){pending.set(seq,{button,received:false,revision:0,complete:false});if(pending.size>64)pending.delete(pending.keys().next().value)}
 return request('/api/action',{method:'POST',headers:{'Content-Type':'application/json','X-MaxBot-Token':TOKEN},body:JSON.stringify({action:name,seq,...fields})}).then(data=>{
  const item=pending.get(seq);if(item&&data.message)deliver(item,data.message,false,data.eventRevision||0,!data.pending);else if(item&&!item.received)feedback(item.button,'Command accepted.');return data;
 });
}
async function run(button,fn){
 feedback(button,'');button.dataset.pending='1';button.disabled=true;
 try{await fn(button);return true}catch(e){feedback(button,e.message||String(e),true);return false}
 finally{delete button.dataset.pending;button.disabled=false;availability()}
}
function bind(id,fn){const b=el(id);messageFor(b);b.onclick=()=>run(b,fn)}
function stop(){return run(el('estop'),b=>action('estop',{},b))}
bind('estop',b=>action('estop',{},b));
document.addEventListener('keydown',e=>{if(e.key==='Escape'){e.preventDefault();stop()}});
window.addEventListener('blur',()=>action('release').catch(()=>{}));
document.addEventListener('visibilitychange',()=>{if(document.hidden)action('release').catch(()=>{})});
bind('arm',b=>action('arm',{bench:el('bench').checked},b));bind('disarm',b=>action('disarm',{},b));bind('reset',b=>action('reset',{},b));
for(const axis of ['steer','drive']){
 for(const dir of [1,-1])bind(axis+'In'+(dir>0?1:2),b=>{el(axis+'Observed').checked=false;return action('pulse',{bridge:axis,direction:dir,pwm:number(axis+'Pwm'),ms:number(axis+'Ms')},b)});
 const n=el(axis+'Pwm'),range=el(axis+'Range');function duty(){el(axis+'Duty').textContent=(Number(n.value)/255*100).toFixed(1)+'%'}
 n.oninput=()=>{range.value=n.value;duty()};range.oninput=()=>{n.value=range.value;duty()};duty();
}
bind('recordSteer',b=>action('recordPwm',{motor:0,observed:el('steerObserved').checked},b));bind('recordDrive',b=>action('recordPwm',{motor:1,observed:el('driveObserved').checked},b));
bind('saveDirection',b=>action('driveDirection',{direction:number('driveDirection')},b));
bind('stopPwm',b=>action('stopTest',{},b));bind('stopPid',b=>action('stopTest',{},b));
bind('capture',b=>action('capture',{degrees:number('captureAngle')},b));bind('derive',b=>action('derive',{},b));bind('clearPoints',b=>action('clear',{},b));
const tuning=()=>Object.fromEntries(['kp','ki','kd','maxPwm','deadbandDeg'].map(k=>[k,number(k)]));
bind('saveTuning',b=>action('tuning',tuning(),b));bind('targetTest',b=>action('target',{...tuning(),degrees:number('target'),ms:number('pidMs')},b));
bind('saveTravel',b=>action('travel',{seconds:number('travelTimeS')},b));
bind('pins',b=>action('pins',{batteryPin:number('batteryPin'),stopPin:number('stopPin')},b));
bind('batterySample',b=>action('batterySample',{voltage:number('meterV')},b));
bind('savePower',b=>action('power',Object.fromEntries(['dividerRatio','fullV','warningV','stopV'].map(k=>[k,number(k)])),b));
bind('brake',b=>action('brake',{confirmed:el('brakeConfirmed').checked},b));
async function exportData(){const text=JSON.stringify(await request('/api/export'),null,2);el('export').value=text;return text}
bind('showExport',async b=>{await exportData();feedback(b,'Current calibration JSON is shown below.')});
bind('download',async b=>{const blob=new Blob([await exportData()],{type:'application/json'}),a=document.createElement('a');a.href=URL.createObjectURL(blob);a.download='maxbot-calibration.json';a.click();setTimeout(()=>URL.revokeObjectURL(a.href),1000);feedback(b,'Calibration JSON downloaded.')});
function renderPoints(points){
 const tbody=el('points'),keys=new Set(points.map(p=>String(p.degrees)));let removedFocus=false;
 for(const [key,tr] of pointRows)if(!keys.has(key)){removedFocus=removedFocus||tr.contains(document.activeElement);tr.remove();pointRows.delete(key)}
 if(points.length){const empty=tbody.querySelector('[data-empty]');if(empty)empty.remove()}
 points.forEach((p,i)=>{const key=String(p.degrees);let tr=pointRows.get(key);
  if(!tr){tr=document.createElement('tr');tr.append(document.createElement('td'),document.createElement('td'));const td=document.createElement('td'),b=document.createElement('button');b.textContent='Remove';b.setAttribute('aria-label','Remove measurement at '+p.degrees+' degrees');td.append(b);tr.append(td);messageFor(b);b.onclick=()=>run(b,button=>action('remove',{degrees:p.degrees},button));pointRows.set(key,tr)}
  tr.children[0].textContent=p.degrees+'°';tr.children[1].textContent=p.adc;if(tbody.children[i]!==tr)tbody.insertBefore(tr,tbody.children[i]||null);
 });
 if(!points.length&&!tbody.querySelector('[data-empty]')){const tr=document.createElement('tr'),td=document.createElement('td');tr.dataset.empty='';td.colSpan=3;td.textContent='No measurements captured.';tr.append(td);tbody.append(tr)}
 if(removedFocus)el('capture').focus();
}
function trace(id,values,min,max){const ctx=el(id).getContext('2d'),height=id==='pidHistory'?150:110;ctx.clearRect(0,0,600,height);ctx.strokeStyle='#cbd4dc';ctx.beginPath();const zero=height-(0-min)/(max-min)*height;ctx.moveTo(0,zero);ctx.lineTo(600,zero);ctx.stroke();ctx.strokeStyle='#0064b2';ctx.lineWidth=2;ctx.beginPath();values.forEach((v,i)=>{const x=i*600/99,y=height-5-(v-min)/(max-min)*(height-10);i?ctx.lineTo(x,y):ctx.moveTo(x,y)});ctx.stroke()}
function availability(){
 const live=fresh();document.querySelectorAll('[data-motion]').forEach(b=>b.disabled=!live||!state.armed||Boolean(state.fault)||state.moving||Boolean(b.dataset.pending));
 el('arm').disabled=!live||!el('bench').checked||Boolean(el('arm').dataset.pending)||(state&&state.armed);
 if(!live){el('status').textContent='Telemetry stale — motion controls disabled';el('armed').textContent='Unknown — stale';el('feedback').textContent='Displayed readings are stale. Reconnect to the ESP32.';el('readiness').textContent='No current feedback. Check Wi-Fi before enabling outputs.';return}
 const reason=!state.outputsReady?'PWM initialization failed.':state.fault?'Latched fault: '+state.fault:state.stopPressed?'Physical stop is pressed.':state.adc<32||state.adc>4063?'ADC is outside 32–4063. Check GPIO35 wiring and voltage.':!state.adcStable?'ADC is noisy: '+state.adcSpread+' counts spread; need at most 18.':state.armed?'Bench outputs enabled.':'Feedback is ready. Enable bench outputs to run a test.';
 el('readiness').textContent=reason;
}
const fmt=v=>v===null||v===undefined?'Overflow / unavailable':Number(v).toFixed(1);
function render(s){
 if(!formLoaded){for(const k of ['kp','ki','kd','maxPwm','deadbandDeg','batteryPin','stopPin'])el(k).value=s[k];for(const k of ['travelTimeS','dividerRatio','fullV','warningV','stopV'])if(s[k]>0)el(k).value=s[k];el('driveDirection').value=s.drivePolarity;formLoaded=true}
 el('status').textContent=s.moving?'Motor test active · device deadlines enforced':'ESP32 connected · '+(s.armed?'outputs enabled':'outputs disabled');
 el('headerFault').textContent=s.fault||'';el('armed').textContent=s.armed?'ENABLED':'Disabled';el('fault').textContent=s.fault||'None';
 el('adc').textContent=s.adc;el('adcMeter').value=s.adc;el('mv').textContent=s.pinMv+' mV';el('spread').textContent=s.adcSpread+' counts';
 el('angle').textContent=s.angle===null?'Not calibrated':fmt(s.angle)+'°';el('batteryAdc').textContent=s.batteryAdc<0?'Not wired':s.batteryAdc;el('battery').textContent=s.batteryV===null?'Not calibrated':fmt(s.batteryV)+' V';
 el('physicalStop').textContent=s.stopPin<0?'Not wired':s.stopPressed?'PRESSED':'Released';el('polarity').textContent=s.steerPolarity||'Not measured';el('drivePolarity').textContent=s.drivePolarity||'Not measured';
 el('appliedSteer').textContent=s.appliedSteer;el('appliedDrive').textContent=s.appliedDrive;el('feedback').textContent=s.event;
 el('pidRaw').textContent=fmt(s.pidRaw);el('pidClipped').textContent=s.pidClipped;el('pidApplied').textContent=s.appliedSteer;el('pidTerms').textContent=[s.pidP,s.pidI,s.pidD].map(fmt).join(' / ');el('pidSaturation').textContent=s.pidSaturated?'Clipped at output cap':'Not saturated';
 el('probe').textContent='IN1 ADC change: '+(s.probe1===null?'not measured':s.probe1)+' · IN2: '+(s.probe2===null?'not measured':s.probe2);
 el('angleReady').textContent=s.tableValid?(s.steerPolarity?'Angle table and steering polarity ready.':'Angle table valid. Derive steering polarity before PID testing.'):'Capture a monotonic right/center/left angle table first.';
 el('batterySamples').textContent=s.batterySamples.map(p=>p.voltage.toFixed(3)+' V → ADC '+p.adc).join(' / ')||'No voltage samples.';
 for(let m=0;m<2;m++)for(let i=0;i<2;i++)el((m===0?'steer':'drive')+'Start'+(i+1)).textContent=s.startPwm[m][i]||'Not measured';
 if(s.lastPulse.valid){const p=s.lastPulse,axis=p.motor===0?'steer':'drive';el(axis+'PulseResult').textContent='Last IN'+(p.direction>0?1:2)+' pulse: PWM '+p.pwm+' ('+(p.pwm/255*100).toFixed(1)+'%), '+p.duration+' ms'+(p.motor===0?', ADC '+p.before+' → '+p.after+' (Δ '+(p.after-p.before)+')':'. Confirm movement visually.')}
 for(const ev of s.events||[]){const item=pending.get(ev.seq);if(item)deliver(item,ev.message,ev.error,ev.revision,ev.complete);eventRevision=Math.max(eventRevision,ev.revision)}
 if(s.eventsOverflow){const oldest=s.events&&s.events.length?s.events[0].revision:s.eventRevision;for(const item of pending.values())if(!item.complete&&item.revision<oldest){feedback(item.button,'Device result history was exceeded. Check current state and retry if needed.',true);item.complete=true;item.abandoned=true}}
 renderPoints(s.points);samples.push(s.adc);pidSamples.push(s.appliedSteer);if(samples.length>100)samples.shift();if(pidSamples.length>100)pidSamples.shift();trace('history',samples,0,4095);trace('pidHistory',pidSamples,-255,255);availability();
}
async function poll(){try{state=await request('/api/state?after='+Math.max(0,eventRevision));lastState=performance.now();render(state)}catch(e){availability()}setTimeout(poll,180)}
el('bench').onchange=availability;
setInterval(()=>{availability();if(!document.hidden&&fresh()&&!heartbeatBusy){heartbeatBusy=true;action('heartbeat').catch(()=>{}).finally(()=>heartbeatBusy=false)}},150);
availability();poll();
</script></body></html>)MAXBOT";
