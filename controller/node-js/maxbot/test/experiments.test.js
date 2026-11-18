'use strict'
const test = require('node:test'), assert = require('node:assert/strict'), fs = require('node:fs/promises'), os = require('node:os'), path = require('node:path'), crypto = require('node:crypto')
const { Hub, sign } = require('../hub'), { Experiments, RECIPES, boundedSteps } = require('../services/experiments'), { Observer } = require('../services/observer')
const key = 'test-experiment-key-0123456789abcdef'
function peer () { return { readyState: 1, bufferedAmount: 0, messages: [], send (s) { this.messages.push(JSON.parse(s)) }, close () {} } }
function auth (hub, role) { const p = peer(); hub.attach(p, role); hub.receive(p, JSON.stringify({ type: 'auth', role, mac: sign(key, `AUTH|${role}|${p.messages[0].nonce}`) })); return p }
test('observer role is independent of operator and cannot submit motor commands', () => {
  const hub = new Hub(key), robot = auth(hub, 'robot'), operator = auth(hub, 'operator'), observer = auth(hub, 'observer'); let seen
  hub.onObserver = m => { seen = m }; const n = robot.messages.length
  hub.receive(observer, JSON.stringify({ type: 'command', action: 'arm' })); assert.equal(seen.action, 'arm'); assert.equal(robot.messages.length, n); assert.equal(hub.operator, operator)
})
test('trial preparation bounds are checked before start and require a visible attended dashboard', async () => {
  const hub = new Hub(key), operator = auth(hub, 'operator'); const e = new Experiments({ hub, signals: {}, root: path.resolve(__dirname, '../../../..'), directory: '/tmp/unused-experiment-test' })
  assert.throws(() => e.prepare('arbitrary-drive')); assert.throws(() => e.prepare('forward_onset', {}, { pwm: 255 }))
  const result = e.prepare('forward_onset', { battery_v: 12 }, { pwm: 120 }); assert.equal(result.prepared.parameters.pwm, 120)
  await assert.rejects(e.start(result.prepared.id), /attended/)
  assert.throws(() => e.attend(operator, { enabled: true }), /Confirm/)
  e.attend(operator, { enabled: true, usb_power_verified: true, area_clear: true, observer_framed: true }); assert.equal(e.present(), true)
  hub.detach(operator); assert.equal(e.present(), false)
})
test('observer writes only ordered session files with matching hashes and never traverses paths', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'observer-test-')), sent = [], o = new Observer({ send: m => sent.push(m) });o.bind('id', dir)
  const bytes = Buffer.from('video fixture bytes'), hash = crypto.createHash('sha256').update(bytes).digest('hex')
  const m = (operation, values = {}) => ({ type: 'observer_upload', experiment_id: 'id', request_id: crypto.randomUUID(), operation, ...values })
  try {
    await o.receive(m('begin', { name: '../escape.mp4', bytes: 3, sha256: hash })); assert.match(sent.at(-1).error, /Invalid/)
    o.uploadError = null
    await o.receive(m('begin', { name: 'video.mp4', bytes: bytes.length, sha256: hash }))
    await o.receive(m('chunk', { offset: 0, data: bytes.toString('base64') }));await o.receive(m('end'))
    assert.deepEqual(await fs.readFile(path.join(dir, 'video.mp4')), bytes); assert.equal(o.uploaded[0].sha256, hash)
    await o.receive(m('begin', { name: 'video.mp4', bytes: bytes.length, sha256: hash }));assert.match(sent.at(-1).error, /Invalid/)
  } finally { await o.disconnect(); await fs.rm(dir, { recursive: true, force: true }) }
})
test('observer refuses a stale upload and a noncontiguous offset', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'observer-offset-')), sent = [], o = new Observer({ send: m => sent.push(m) });o.bind('current', dir)
  try {
    await o.receive({ type: 'observer_upload', experiment_id: 'old', operation: 'begin' });assert.match(sent.at(-1).error, /session/)
    await o.receive({ type: 'observer_upload', experiment_id: 'current', operation: 'begin', name: 'video.mp4', bytes: 3, sha256: 'a'.repeat(64) })
    await o.receive({ type: 'observer_upload', experiment_id: 'current', operation: 'chunk', offset: 1, data: 'YWJj' });assert.match(sent.at(-1).error, /chunk/)
  } finally { await o.disconnect(); await fs.rm(dir, { recursive: true, force: true }) }
})

test('signed device sessions reject tampering, downgrade and cross-session replay', () => {
  const hub = new Hub(key), robot = peer();hub.attach(robot, 'robot')
  hub.receive(robot, JSON.stringify({ type: 'auth', role: 'robot', control_signing: 1, mac: sign(key, `AUTH|robot|${robot.messages[0].nonce}|signed-v1`) }))
  const session = hub.peers.get(robot).sessionId, payload = JSON.stringify({ type: 'telemetry', vehicle_connected: true })
  const envelope = { type: 'signed_message', session_id: session, sequence: 1, payload, mac: sign(key, `C|${session}|1|${payload}`) }
  hub.receive(robot, JSON.stringify({ type: 'telemetry', vehicle_connected: true }));assert.equal(hub.lastTelemetry, null)
  hub.receive(robot, JSON.stringify({ ...envelope, payload: payload.replace('true', 'false') }));assert.equal(hub.lastTelemetry, null)
  hub.receive(robot, JSON.stringify(envelope));assert.equal(hub.lastTelemetry.vehicle_connected, true)
  hub.lastTelemetry = null;hub.receive(robot, JSON.stringify(envelope));assert.equal(hub.lastTelemetry, null)
  hub.send(robot, { type: 'experiment', operation: 'abort' });const output = robot.messages.at(-1)
  assert.equal(output.type, 'signed_message');assert.equal(output.mac, sign(key, `S|${session}|${output.sequence}|${output.payload}`))
})

test('abort during asynchronous collector preparation drains the recording and never starts a trial', { timeout: 5000 }, async () => {
  const { SignalMonitoring } = require('../services/signal-monitor')
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'maxbot-cancel-preparation-')), hub = new Hub(key)
  const operator = auth(hub, 'operator'), robot = auth(hub, 'robot'), observer = auth(hub, 'observer'), sent = []
  hub.peers.get(robot).signed = hub.peers.get(observer).signed = true
  hub.lastTelemetry = { received_ms: Date.now(), signal_monitor_available: true, mode: 'STOPPED', armed: false, pwm: 0, vehicle_connected: true, vehicle_transport: 'usb', experiment_protocol: 1 }
  let signals
  const deliver = m => {
    sent.push(m)
    if (m.type === 'signal_monitor') queueMicrotask(() => signals.phone({ type: 'signal_trace', monitor_id: m.monitor_id, batch: m.operation === 'start' ? 1 : 2, dropped: 0, phase: m.operation === 'start' ? 'recording' : 'stopped', events: [{ ordinal: m.operation === 'start' ? 1 : 2, event: { type: m.operation === 'start' ? 'monitor_started' : 'monitor_stopped' } }] }))
  }
  hub.send = (peer, m) => deliver(m)
  signals = new SignalMonitoring({ directory: path.join(dir, 'signals'), sendRobot: deliver, publish () {}, snapshot: () => hub.lastTelemetry })
  const e = new Experiments({ hub, signals, root: path.resolve(__dirname, '../../../..'), directory: path.join(dir, 'experiments') })
  e.observer.status = { camera_ready: true, received_ms: Date.now() };e.observer.command = async () => ({})
  e.attend(operator, { enabled: true, usb_power_verified: true, area_clear: true, observer_framed: true })
  let release, entered;const gate = new Promise(resolve => { release = resolve }), waiting = new Promise(resolve => { entered = resolve }), original = signals.start.bind(signals)
  signals.start = async options => { entered();await gate;await original(options) }
  try {
    const id = e.prepare('camera_clock').prepared.id, started = e.start(id);await waiting
    const aborted = e.abort('test_takeover');release();await Promise.allSettled([started, aborted])
    assert.equal(signals.active, null);assert.equal(e.active, null);assert.equal(e.last.summary.outcome, 'aborted')
    assert.equal(sent.some(m => m.type === 'experiment' && m.operation === 'start'), false)
  } finally { signals.close();await e.observer.disconnect();await fs.rm(dir, { recursive: true, force: true }) }
})

test('short pulse overrides preserve neutral recovery and cannot extend a recipe motion budget', () => {
  const e=new Experiments({hub:new Hub(key),signals:{},root:'.',directory:'/tmp/unused-experiment-test'})
  for(const pulse_ms of [0,19,1001,100.5,NaN,Infinity])assert.throws(()=>e.prepare('forward_onset',{}, {pulse_ms}), /pulse/)
  for(const recipe of ['forward_onset','reverse_onset','speed_stop','turn']){
    const p=e.prepare(recipe,{}, {pwm:110,pulse_ms:100}).prepared
    const steps=boundedSteps(RECIPES[recipe],p.parameters)
    assert.equal(steps.filter(s=>s.pwm!==0).reduce((n,s)=>n+s.duration_ms,0),100)
    assert.ok(steps.every(s=>Math.abs(s.pwm)<=110))
    assert.deepEqual(steps.filter(s=>s.pwm===0),RECIPES[recipe].steps.filter(s=>s.pwm===0))
    assert.deepEqual(boundedSteps(RECIPES[recipe],{pwm:140,pulse_ms:1000}),RECIPES[recipe].steps)
  }
  assert.deepEqual(boundedSteps(RECIPES.camera_clock,{pwm:110,pulse_ms:100}),RECIPES.camera_clock.steps)
})

test('thermal timing allowance is explicit and narrows power limits; slalom is excluded',async()=>{
  const e=new Experiments({hub:new Hub(key),signals:{},root:'.',directory:'/tmp/unused-experiment-test'})
  assert.equal(e.prepare('camera_clock').prepared.parameters.allow_severe_thermal,false)
  const p=e.prepare('forward_onset',{}, {allow_severe_thermal:true}).prepared
  assert.deepEqual(p.parameters,{pwm:110,pulse_ms:100,allow_severe_thermal:true})
  for(const parameters of [{allow_severe_thermal:'true'},{allow_severe_thermal:true,pwm:111},{allow_severe_thermal:true,pulse_ms:101}])assert.throws(()=>e.prepare('forward_onset',{},parameters))
  assert.throws(()=>e.prepare('slalom',{}, {allow_severe_thermal:true}),/slalom/)
})
