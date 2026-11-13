'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const { Hub, sign } = require('../hub')
const key = '0123456789abcdef0123456789abcdef'
class Peer {
  constructor () { this.readyState = 1; this.bufferedAmount = 0; this.messages = [] }
  send (text) { this.messages.push(JSON.parse(text)) }
  close (code, reason) { this.closed = { code, reason }; this.readyState = 3 }
}
function auth (hub, role) {
  const p = new Peer(); hub.attach(p, role); const nonce = p.messages[0].nonce
  hub.receive(p, JSON.stringify({ type: 'auth', role, mac: sign(key, `AUTH|${role}|${nonce}`) }))
  return p
}
test('wrong key never gains control', () => {
  const hub = new Hub(key); const p = new Peer(); hub.attach(p, 'operator')
  hub.receive(p, JSON.stringify({ type: 'auth', role: 'operator', mac: '0'.repeat(64) }))
  assert.equal(p.closed.code, 1008); assert.equal(hub.operator, null)
})
test('single operator, replay protection and explicit heartbeat', () => {
  let now = 1000; const hub = new Hub(key, () => now); const robot = auth(hub, 'robot'); const operator = auth(hub, 'operator')
  assert.equal(auth(hub, 'operator').closed.code, 1008)
  hub.receive(operator, JSON.stringify({ type: 'heartbeat', seq: 1 }))
  const first = robot.messages.length
  hub.receive(operator, JSON.stringify({ type: 'command', action: 'manual', steering_deg: 5, pwm: 20, deadman: true, seq: 2, session_id: hub.peers.get(operator).sessionId, event_id: 1, event_ms: now, deadline_ms: now+100 }))
  assert.equal(robot.messages.length, first + 1)
  hub.receive(operator, JSON.stringify({ type: 'command', action: 'arm', seq: 2 }))
  assert.equal(robot.messages.length, first + 1)
  now += 501; hub.tick(); assert.equal(hub.operator, null); assert.equal(robot.messages.at(-1).type, 'operator_left')
})
test('disconnect signals stop; NaN/out of range manual input rejected', () => {
  const now=Date.now(); const hub = new Hub(key); const robot = auth(hub, 'robot'); const operator = auth(hub, 'operator')
  hub.receive(operator, JSON.stringify({ type: 'heartbeat', seq: 1 })); const n = robot.messages.length
  hub.receive(operator, JSON.stringify({ type: 'command', action: 'manual', steering_deg: null, pwm: 999, deadman: true, seq: 2, session_id: hub.peers.get(operator).sessionId, event_id: 1, event_ms: now, deadline_ms: now+100 }))
  assert.equal(robot.messages.length, n); hub.detach(operator); assert.equal(robot.messages.at(-1).type, 'operator_left')
})
test('Java and node HMAC match published vector', () => assert.equal(sign(key, '1|boot|session|1|1200|100|DRIVE|1500|30'), '85c23b71e3ec7b41613deca25f323f8d7de5909138c16fda4dab7fac4ab9e2f8'))
