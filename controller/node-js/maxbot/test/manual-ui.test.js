'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const { ManualRamp } = require('../public/manual-control')

function dashboard () {
  const elements = new Map(), documentEvents = {}, windowEvents = {}
  const ctx = new Proxy({}, { get: (target, key) => target[key] || (() => {}) })
  class Element {
    constructor (id = '') {
      this.id = id; this.events = {}; this.style = {}; this.value = '0'; this.dataset = {}; this.attributes = {}; this.editable = false
      const classes = new Set()
      this.classList = { toggle: (name, on) => on ? classes.add(name) : classes.delete(name), contains: name => classes.has(name) }
    }
    addEventListener (name, fn) { (this.events[name] ||= []).push(fn) }
    focus () { document.activeElement = this }
    setPointerCapture () {}
    replaceChildren () {}
    getContext () { return ctx }
    getBoundingClientRect () { return { width: 640, height: 480 } }
    closest () { return this.editable ? this : null }
    setAttribute (name, value) { this.attributes[name] = value }
    getAttribute (name) { return this.attributes[name] }
    fire (name, values = {}) { for (const fn of this.events[name] || []) fn({ target: this, preventDefault () {}, ...values }) }
  }
  const get = id => { if (!elements.has(id)) elements.set(id, new Element(id)); return elements.get(id) }
  get('drive-rate').value = '100'; get('steering-rate').value = '250'
  get('steer').min = '-10'; get('steer').max = '10'; get('throttle').min = '-255'; get('throttle').max = '255'
  const buttons = ['arm', 'arm_manual', 'scout', 'run', 'stop', 'reset'].map(action => { const e = new Element(); e.dataset.action = action; return e })
  const document = { hidden: false, activeElement: null, hasFocus: () => true, getElementById: get, createElement: () => new Element(), querySelectorAll: () => buttons, addEventListener: (name, fn) => (documentEvents[name] ||= []).push(fn) }
  let now = 100000
  class ClockDate extends Date { static now () { return now } }
  const sandbox = { document, window: { addEventListener: (name, fn) => (windowEvents[name] ||= []).push(fn) }, TextEncoder, console,
    performance: { now: () => now }, Date: ClockDate, WebSocket: { OPEN: 1 }, setInterval: () => 1, clearInterval () {}, setTimeout () {}, sent: [] }
  vm.createContext(sandbox)
  for (const file of ['manual-control.js', 'app.js']) vm.runInContext(fs.readFileSync(path.join(__dirname, '../public', file), 'utf8'), sandbox)
  sandbox.packet = { mode: 'STOPPED', calibrated: false, manual_ready: true, manual_armed: false, armed: false,
    manual_max_pwm: 255, steering_limits_deg: [-26, 26], missing: ['cones.heightM'], manual_missing: [], battery_monitoring: false }
  const run = code => vm.runInContext(code, sandbox)
  run('latest=packet; paired=true; robot=true; sessionId="test-session"; lastTelemetry=Date.now(); socket={readyState:1,send:m=>sent.push(JSON.parse(m))}; render(latest); availability()')
  const fire = (target, name, values = {}) => { for (const fn of target[name] || []) fn({ preventDefault () {}, ...values }) }
  return { sandbox, get, buttons, run, document,
    arm () { sandbox.packet.armed = sandbox.packet.manual_armed = true; run('render(latest); availability()') },
    key (code, down = true, values = {}) { fire(documentEvents, down ? 'keydown' : 'keyup', { code, target: document.activeElement, repeat: false, ...values }) },
    step (ms = 20, fresh = true) { now += ms; if (fresh) run('lastTelemetry=Date.now()'); run('manualStep()') },
    advance (ms) { for (let remaining = ms; remaining > 0; remaining -= 20) this.step(Math.min(20, remaining)) },
    blur () { fire(windowEvents, 'blur') },
    focus (element) { document.activeElement = element; fire(documentEvents, 'focusin', { target: element }) },
    hide () { document.hidden = true; fire(documentEvents, 'visibilitychange') }
  }
}

test('ramp crosses zero at the same rate, clamps, decays, and neutralizes opposing keys', () => {
  const ramp = new ManualRamp(), keys = new Set(['KeyW'])
  for (let n = 0; n < 50; n++) ramp.step(keys, .02)
  assert.equal(ramp.drive, 1)
  keys.clear(); keys.add('KeyS')
  for (let n = 0; n < 50; n++) ramp.step(keys, .02)
  assert.ok(Math.abs(ramp.drive) < 1e-10)
  for (let n = 0; n < 50; n++) ramp.step(keys, .02)
  assert.equal(ramp.drive, -1)
  keys.clear()
  for (let n = 0; n < 25; n++) ramp.step(keys, .02)
  assert.ok(Math.abs(ramp.drive + .5) < 1e-10)
  keys.add('KeyW'); keys.add('KeyS')
  for (let n = 0; n < 25; n++) ramp.step(keys, .02)
  assert.equal(ramp.drive, 0)
  keys.clear(); keys.add('KeyD')
  for (let n = 0; n < 20; n++) ramp.step(keys, .02)
  assert.equal(ramp.steering, -1)
  keys.clear()
  for (let n = 0; n < 20; n++) ramp.step(keys, .02)
  assert.equal(ramp.steering, 0)
  keys.add('KeyA'); ramp.step(keys, .05, 100, 500)
  assert.equal(ramp.steering, .25)
  assert.equal(ramp.step(keys, .4), false); assert.equal(ramp.steering, 0)
})

test('manual arm enables WASD without autonomy; W reaches full in 1s, D in .4s, release decays', () => {
  const d = dashboard(), sent = d.sandbox.sent
  assert.equal(d.buttons.find(b => b.dataset.action === 'arm_manual').disabled, false)
  for (const action of ['arm', 'scout', 'run']) assert.equal(d.buttons.find(b => b.dataset.action === action).disabled, true)
  d.key('KeyW'); d.advance(100); assert.equal(sent.length, 0)
  d.buttons.find(b => b.dataset.action === 'arm_manual').fire('click')
  assert.equal(sent.at(-1).action, 'arm_manual')
  d.document.activeElement = d.buttons.find(b => b.dataset.action === 'arm_manual') // Button focus must allow WASD right after clicking arm.
  d.arm(); assert.equal(d.get('manual-control').dataset.ready, 'true')
  d.key('KeyW'); d.key('KeyD'); d.advance(400)
  assert.equal(sent.at(-1).steering_deg, -26); assert.equal(sent.at(-1).pwm, 102)
  d.advance(600); assert.equal(sent.at(-1).pwm, 255)
  d.key('KeyW', false); d.key('KeyD', false); d.advance(500)
  assert.equal(sent.at(-1).pwm, 127); assert.equal(sent.at(-1).steering_deg, 0)
  d.advance(500); assert.equal(sent.at(-1).pwm, 0); assert.equal(sent.at(-1).deadman, false)
  assert.equal(sent.at(-2).deadline_ms - sent.at(-2).event_ms, 300)
  assert.ok(sent.every((m, i) => i === 0 || m.seq > sent[i - 1].seq))
})

test('opposite drive cancels existing PWM before reverse; quick taps create intermediate output', () => {
  const d = dashboard(), sent = d.sandbox.sent
  d.arm(); d.key('KeyS'); d.advance(1000); assert.equal(sent.at(-1).pwm, -255)
  d.key('KeyS', false); d.key('KeyW'); d.advance(500); assert.equal(sent.at(-1).pwm, -127)
  d.advance(1000); assert.equal(sent.at(-1).pwm, 128)
  d.run('release()')
  for (let n = 0; n < 5; n++) { d.key('KeyW'); d.advance(100); d.key('KeyW', false); d.advance(60) }
  assert.ok(sent.at(-1).pwm > 0 && sent.at(-1).pwm < 100)
})

test('rate fields change ramps; typing/modifiers cannot drive; invalid rates pause controls', () => {
  const d = dashboard(), sent = d.sandbox.sent
  d.arm(); d.get('drive-rate').value = '200'; d.get('drive-rate').fire('input')
  d.key('KeyW'); d.advance(500); assert.equal(sent.at(-1).pwm, 255)
  const field = d.get('drive-rate'); field.editable = true; d.focus(field)
  assert.equal(sent.at(-1).deadman, false)
  const count = sent.length; d.key('KeyW'); d.advance(100); assert.equal(sent.length, count)
  d.focus(null); d.key('KeyW', true, { ctrlKey: true }); d.advance(100); assert.equal(sent.length, count)
  d.get('drive-rate').value = ''; d.get('drive-rate').fire('input'); d.key('KeyW'); d.advance(100)
  assert.equal(sent.length, count); assert.equal(d.get('drive-rate').getAttribute('aria-invalid'), 'true')
  assert.match(d.get('rate-error').textContent, /paused/)
  d.get('drive-rate').value = '100'; d.get('drive-rate').fire('input')
  d.get('steering-rate').value = '500'; d.get('steering-rate').fire('input')
  d.key('KeyA'); d.advance(200); assert.equal(sent.at(-1).steering_deg, 26)
})

for (const boundary of ['blur', 'hide', 'stale', 'disarm', 'disconnect', 'stall', 'stop']) {
  test(`${boundary} immediately clears input and prevents keyboard repeat from resuming it`, () => {
    const d = dashboard(), sent = d.sandbox.sent
    d.arm(); d.key('KeyW'); d.advance(200); assert.ok(sent.at(-1).pwm > 0)
    if (boundary === 'blur') d.blur()
    if (boundary === 'hide') d.hide()
    if (boundary === 'stale') d.step(650, false)
    if (boundary === 'disarm') { d.sandbox.packet.armed = false; d.step() }
    if (boundary === 'disconnect') { d.run('robot=false'); d.step() }
    if (boundary === 'stall') d.step(320)
    if (boundary === 'stop') d.buttons.find(b => b.dataset.action === 'stop').fire('click')
    assert.equal(d.get('throttle').value, 0)
    assert.ok(sent.some(m => m.action === 'manual' && m.deadman === false))
    const count = sent.length
    d.document.hidden = false; d.arm(); d.run('robot=true;lastTelemetry=Date.now()')
    d.key('KeyW', true, { repeat: true }); d.advance(200)
    assert.equal(sent.length, count)
  })
}


test('on-screen direction buttons ramp, release and share input with physical keys', () => {
  const d = dashboard(), sent = d.sandbox.sent
  d.arm()
  d.get('key-w').fire('pointerdown', { pointerId: 1, button: 0 }); d.advance(500)
  assert.equal(sent.at(-1).pwm, 128)
  d.get('key-d').fire('pointerdown', { pointerId: 2, button: 0 }); d.advance(400)
  assert.equal(sent.at(-1).steering_deg, -26)
  d.key('KeyW')
  d.get('key-w').fire('pointerup', { pointerId: 1 }); d.advance(100)
  assert.equal(sent.at(-1).pwm, 255) // Physical W still held.
  d.key('KeyW', false); d.get('key-d').fire('pointercancel', { pointerId: 2 }); d.advance(500)
  assert.ok(Math.abs(sent.at(-1).pwm - 128) <= 1); assert.equal(sent.at(-1).steering_deg, 0)
  d.advance(500); assert.equal(sent.at(-1).deadman, false)
  d.get('key-a').fire('keydown', { key: ' ', repeat: false }); d.advance(400)
  assert.equal(sent.at(-1).steering_deg, 26)
  d.get('key-a').fire('blur'); d.advance(400); assert.equal(sent.at(-1).deadman, false)
})

test('arming instruction is informational; actual faults remain visible as faults', () => {
  const d = dashboard()
  d.sandbox.packet.fault = 'Manual commissioning: verify direction and stop response with wheels raised'
  d.arm()
  assert.equal(d.get('fault').classList.contains('informational'), true)
  assert.match(d.get('fault').textContent, /Manual armed/)
  d.sandbox.packet.fault = 'ESP32: motor watchdog'
  d.run('render(latest)')
  assert.equal(d.get('fault').classList.contains('informational'), false)
  assert.equal(d.get('fault').textContent, 'ESP32: motor watchdog')
})

test('control delivery distinguishes phone output from browser request and reports missing clock evidence', () => {
  const d = dashboard()
  Object.assign(d.sandbox.packet, { mode: 'MANUAL', pwm: 140, target_steering_deg: 8, link: 'Authenticated vehicle link', clock_uncertainty_ms: 24, mcu_deadline_rejected: 7, mcu_pwm: 0, wifi_latency_mode:'foreground low-latency lock held',control_max_gap_ms:21,dashboard_control:{clock_uncertainty_ms:3,last_manual_budget_ms:48,pairingKey:'must-not-enter-report'}, vehicle_control: {gate:'Waiting for applied zero acknowledgement',last_sent_mode:'STOP',ack_age_ms:null,pairingKey:'must-not-enter-report'}, pairingKey: 'must-not-enter-report' })
  d.arm()
  assert.match(d.get('control-delivery').textContent, /Phone output: 140 PWM \/ 8.0°/)
  assert.match(d.get('control-delivery').textContent, /ESP clock uncertainty: 24.0 ms/)
  assert.match(d.get('control-delivery').textContent, /ESP rejected deadlines: 7/)
  assert.match(d.get('control-delivery').textContent, /Last sent mode: STOP/)
  assert.match(d.get('control-delivery').textContent, /ESP reported PWM: 0/)
  assert.match(d.get('control-delivery').textContent, /Manual budget at phone: 48.0 ms/)
  assert.match(d.get('control-delivery').textContent, /Control loop maximum gap: 21.0 ms/)
  assert.equal(d.run('JSON.stringify(controlHistory).includes("must-not-enter-report")'), false)
  d.sandbox.packet.clock_uncertainty_ms = null; d.run('render(latest)')
  assert.match(d.get('control-delivery').textContent, /not synchronized/)
  delete d.sandbox.packet.clock_uncertainty_ms; d.run('render(latest)')
  assert.match(d.get('control-delivery').textContent, /uncertainty: not reported/)
  d.step(650, false); d.run('availability()')
  assert.match(d.get('control-delivery').textContent, /No fresh phone telemetry/)
})

test('ESP link/fault state blocks manual controls while reset remains available', () => {
  const d=dashboard();d.arm();d.sandbox.packet.mcu_fault='ABSOLUTE_EXPIRY';d.run('render(latest);availability()');
  assert.equal(d.get('key-w').disabled,true);assert.equal(d.buttons.find(b=>b.dataset.action==='arm_manual').disabled,true);
  assert.equal(d.buttons.find(b=>b.dataset.action==='reset').disabled,false);assert.match(d.get('manual-status').textContent,/ABSOLUTE_EXPIRY/);
  d.sandbox.packet.mcu_fault='';d.sandbox.packet.vehicle_connected=false;d.run('render(latest);availability()');
  assert.equal(d.get('key-w').disabled,true);assert.match(d.get('manual-status').textContent,/ESP32 link unavailable/);
})
