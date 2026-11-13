// Execute the shipped script with an in-memory DOM and stalled network.
// No browser, external dependency, real telemetry or hardware is involved.
'use strict';
const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
let clock=100,networkMode='stall';const intervals=[],timeouts=[],elements=new Map(),sent=[];
const document={activeElement:null,createElement:tag=>new Element(tag),getElementById:id=>{if(!elements.has(id))elements.set(id,new Element('div'));return elements.get(id)},addEventListener(){},hidden:false};
class Element{
  constructor(tag){this.tag=tag;this.children=[];this.parent=null;this.dataset={};this.attributes={};this.value='';this.checked=false;this.disabled=false;this.textContent=''}
  get parentNode(){return this.parent}
  append(...nodes){for(const node of nodes)this.insertBefore(node,null)}
  insertBefore(node,before){node.remove();const i=before?this.children.indexOf(before):this.children.length;this.children.splice(i,0,node);node.parent=this}
  remove(){if(this.parent){const i=this.parent.children.indexOf(this);this.parent.children.splice(i,1);this.parent=null}}
  contains(node){return this===node||this.children.some(child=>child.contains(node))}
  querySelector(selector){return this.children.find(child=>selector==='[data-empty]'&&'empty' in child.dataset)||null}
  setAttribute(k,v){this.attributes[k]=v}focus(){document.activeElement=this}
  getContext(){return {clearRect(){},beginPath(){},moveTo(){},lineTo(){},stroke(){}}}
}
const motors=[new Element('button'),new Element('button')];
document.querySelectorAll=selector=>selector==='[data-motion]'?motors:[];
const context=vm.createContext({document,window:{addEventListener(){}},performance:{now:()=>clock},AbortController,Blob,URL,Map,Set,console,
  setTimeout(fn,ms){const t={fn,ms};timeouts.push(t);return t},clearTimeout(){},setInterval(fn){intervals.push(fn)},
  fetch(url,options){if(options.body)sent.push(JSON.parse(options.body));if(networkMode!=='stall'&&url==='/api/action')return Promise.resolve({ok:networkMode==='ok'||networkMode==='pending',json:async()=>networkMode==='ok'?{ok:true,message:'Immediate device result',pending:false,eventRevision:0}:networkMode==='pending'?{ok:true,message:'Test active',pending:true,eventRevision:0}:{error:'Test action rejected beside its own button'}});return new Promise((resolve,reject)=>options.signal.addEventListener('abort',()=>{const e=new Error('aborted');e.name='AbortError';reject(e)}))}});
const script=fs.readFileSync(process.argv[2],'utf8');vm.runInContext(script,context);
const run=source=>vm.runInContext(source,context);
const fixture={armed:true,moving:false,fault:'',adc:2000,pinMv:1510,angle:0,batteryAdc:-1,batteryV:null,stopPin:-1,stopPressed:false,steerPolarity:1,drivePolarity:1,event:'Unit test only',probe1:20,probe2:-20,batterySamples:[],points:[],kp:4,ki:0,kd:0,maxPwm:255,deadbandDeg:1,batteryPin:-1,travelTimeS:0,dividerRatio:0,fullV:0,warningV:0,stopV:0,startPwm:[[0,0],[0,0]],lastPulse:{valid:false},outputsReady:true,adcStable:true,adcSpread:5,eventRevision:0,eventActionSeq:0,eventIsError:false,appliedSteer:0,appliedDrive:0,pidRaw:0,pidClipped:0,pidP:0,pidI:0,pidD:0,pidSaturated:false,tableValid:true};
context.fixture=fixture;run('state=fixture;lastState=performance.now();render(state)');
assert.equal(motors[0].disabled,false);
clock=701;intervals[0](); // Pending fetch has not completed or rejected.
assert.equal(motors[0].disabled,true);assert.match(elements.get('status').textContent,/stale/);
assert.match(elements.get('armed').textContent,/Unknown/);assert.match(elements.get('feedback').textContent,/stale/);
const points=[{degrees:-25,adc:700},{degrees:0,adc:2000},{degrees:25,adc:3300}];context.points=points;run('renderPoints(points)');
const tbody=elements.get('points'),center=tbody.children[1].children[2].children[0].children[0];center.focus();
for(let i=0;i<100;++i)run('renderPoints(points)');
assert.equal(document.activeElement,center);assert.equal(tbody.children[1].children[2].children[0].children[0],center);
assert.match(center.attributes['aria-label'],/0 degrees/);
points.shift();run('renderPoints(points)');assert.equal(document.activeElement,center);
points.shift();run('renderPoints(points)');assert.equal(document.activeElement,elements.get('capture'));
(async()=>{
 const pendingRequest=run('request("/unit-test-timeout")');timeouts[timeouts.length-1].fn();await assert.rejects(pendingRequest,/timed out/);
 networkMode='error';await run('run(el("targetTest"),b=>action("target",{kp:5000,kd:2000},b))');
 assert.match(run('messages.get(el("targetTest")).textContent'),/beside its own button/);
 assert.match(run('messages.get(el("targetTest")).className'),/error/);
 assert.equal(run('messages.get(el("saveTuning")).textContent'),'');
 networkMode='ok';await run('run(el("saveTuning"),b=>action("tuning",{kp:5000,ki:150,kd:2000,maxPwm:255,deadbandDeg:1},b))');
 assert.equal(sent[sent.length-1].kp,5000);assert.equal(sent[sent.length-1].kd,2000);
 const accepted=sent[sent.length-1].seq;clock=800;fixture.eventActionSeq=accepted;fixture.eventRevision=1;fixture.event='Tuning recorded';fixture.pidRaw=50000;fixture.pidClipped=255;fixture.pidSaturated=true;
 fixture.events=[{seq:accepted,revision:1,message:'Tuning recorded',error:false,complete:true}];run('lastState=performance.now();render(fixture)');
 assert.equal(run('messages.get(el("saveTuning")).textContent'),'Tuning recorded');
 assert.match(run('messages.get(el("targetTest")).textContent'),/beside its own button/);
 assert.equal(elements.get('pidRaw').textContent,'50000.0');assert.equal(elements.get('pidClipped').textContent,255);
 // Both synchronous responses reach their buttons without waiting for a poll.
 await run('run(el("saveDirection"),b=>action("driveDirection",{direction:1},b))');
 assert.equal(run('messages.get(el("saveDirection")).textContent'),'Immediate device result');
 networkMode='pending';
 await run('run(el("steerIn1"),b=>action("pulse",{bridge:"steer",direction:1,pwm:255,ms:20},b))');const pulseSeq=sent[sent.length-1].seq;
 await run('run(el("recordSteer"),b=>action("recordPwm",{motor:0,observed:true},b))');const recordSeq=sent[sent.length-1].seq;
 // Two completions between polls are retained and delivered to both initiators.
 fixture.events=[{seq:pulseSeq,revision:2,message:'Pulse completed',error:false,complete:true},{seq:recordSeq,revision:3,message:'Starting PWM recorded',error:false,complete:true}];
 run('render(fixture)');assert.equal(run('messages.get(el("steerIn1")).textContent'),'Pulse completed');assert.equal(run('messages.get(el("recordSteer")).textContent'),'Starting PWM recorded');
 // An older HTTP acknowledgement must not overwrite a later completion.
 run('deliver(pending.get('+pulseSeq+'),"Old active acknowledgement",false,1,false)');assert.equal(run('messages.get(el("steerIn1")).textContent'),'Pulse completed');
 await run('run(el("steerIn2"),b=>action("pulse",{bridge:"steer",direction:-1,pwm:255,ms:20},b))');const evictedSeq=sent[sent.length-1].seq;
 fixture.events=[];fixture.eventRevision=20;fixture.eventsOverflow=true;run('render(fixture)');
 assert.match(run('messages.get(el("steerIn2")).textContent'),/history was exceeded/);
 run('deliver(pending.get('+evictedSeq+'),"Delayed active acknowledgement",false,0,false)');
 assert.match(run('messages.get(el("steerIn2")).textContent'),/history was exceeded/);assert.equal(run('pending.get('+evictedSeq+').complete'),true);
 // A retained active action is not abandoned just because unrelated old events overflowed.
 await run('run(el("driveIn1"),b=>action("pulse",{bridge:"drive",direction:1,pwm:255,ms:20},b))');const activeSeq=sent[sent.length-1].seq;
 fixture.events=[{seq:activeSeq,revision:21,message:'Active pulse retained',error:false,complete:false}];run('render(fixture)');
 assert.equal(run('messages.get(el("driveIn1")).textContent'),'Active pulse retained');assert.equal(run('pending.get('+activeSeq+').complete'),false);
 console.log('PASS calibration dashboard: request deadlines, stale lock, keyboard focus, local button errors, aggressive gain transport and raw/clipped PID display');
})().catch(e=>{console.error(e);process.exitCode=1});
