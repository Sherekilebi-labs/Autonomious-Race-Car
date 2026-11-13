'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')

test('actual dashboard enables manual commissioning without autonomy and sends PWM above motor start', () => {
  const elements = new Map()
  const ctx = new Proxy({}, { get: (target, key) => target[key] || (() => {}) })
  class Element {
    constructor () { this.events = {}; this.style = {}; this.value = '0'; this.dataset = {}; this.classList = { add () {}, remove () {} } }
    addEventListener (name, fn) { (this.events[name] ||= []).push(fn) }
    replaceChildren () {}
    getContext () { return ctx }
    getBoundingClientRect () { return { width: 640, height: 480 } }
    setPointerCapture () {}
    fire (name) { for (const fn of this.events[name] || []) fn({ preventDefault () {}, pointerId: 1 }) }
  }
  const get = id => { if (!elements.has(id)) elements.set(id, new Element()); return elements.get(id) }
  const buttons = ['arm', 'arm_manual', 'scout', 'run', 'stop', 'reset'].map(action => { const e = new Element(); e.dataset.action = action; return e })
  const document = { hidden: false, getElementById: get, createElement: () => new Element(), querySelectorAll: () => buttons, addEventListener () {} }
  const sandbox = { document, window: { addEventListener () {} }, TextEncoder, console, WebSocket: { OPEN: 1 },
    setInterval: () => 1, clearInterval () {}, setTimeout () {}, sent: [] }
  vm.createContext(sandbox)
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../public/app.js'), 'utf8'), sandbox)
  sandbox.packet = { mode: 'DISARMED', calibrated: false, manual_ready: true, manual_armed: false, armed: false,
    manual_max_pwm: 255, steering_limits_deg: [-26, 26], missing: ['cones.heightM'], manual_missing: [], battery_monitoring: false }
  vm.runInContext('latest=packet; paired=true; robot=true; lastTelemetry=Date.now(); socket={readyState:1,send:m=>sent.push(JSON.parse(m))}; render(latest); availability()', sandbox)
  assert.equal(buttons.find(b => b.dataset.action === 'arm_manual').disabled, false)
  for (const action of ['arm', 'scout', 'run']) assert.equal(buttons.find(b => b.dataset.action === action).disabled, true)
  assert.equal(get('deadman').disabled, true)
  sandbox.packet.armed = sandbox.packet.manual_armed = true
  vm.runInContext('render(latest); availability()', sandbox)
  assert.equal(get('deadman').disabled, false)
  assert.equal(get('throttle').min, '-255'); assert.equal(get('throttle').max, '255')
  get('throttle').value = '150'; get('steer').value = '8'; get('deadman').fire('pointerdown')
  assert.equal(sandbox.sent.at(-1).pwm, 150)
  assert.equal(sandbox.sent.at(-1).deadman, true)
  get('deadman').fire('blur')
  assert.equal(sandbox.sent.at(-1).deadman, false)
  vm.runInContext('lastTelemetry=Date.now()-1000; availability()', sandbox)
  assert.equal(get('deadman').disabled, true)
})
