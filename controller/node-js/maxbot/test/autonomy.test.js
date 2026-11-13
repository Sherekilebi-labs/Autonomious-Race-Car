'use strict'
const test = require('node:test'), assert = require('node:assert/strict'), { EventEmitter } = require('node:events')
const fs = require('node:fs/promises'), os = require('node:os'), path = require('node:path')
const { Hub, sign } = require('../hub'), { OperatorStop } = require('../services/operator-stop'), { Planner } = require('../services/planner')
const key = 'synthetic-contract-key-never-deploy'
class Peer { constructor () { this.readyState = 1; this.bufferedAmount = 0; this.messages = [] } send (v) { this.messages.push(JSON.parse(v)) } close () { this.readyState = 3 } }
function auth (hub, role) { const p = new Peer(); hub.attach(p, role); hub.receive(p, JSON.stringify({ type: 'auth', role, mac: sign(key, `AUTH|${role}|${p.messages[0].nonce}`) })); return p }
test('new packet sequence cannot refresh a consumed or expired browser intent', () => {
  let now = 1000; const hub = new Hub(key, () => now), robot = auth(hub, 'robot'), operator = auth(hub, 'operator')
  hub.receive(operator, JSON.stringify({ type: 'heartbeat', seq: 1 }))
  const m = { type: 'command', action: 'manual', seq: 2, steering_deg: 0, pwm: 120, deadman: true, session_id: hub.peers.get(operator).sessionId, event_id: 1, event_ms: now, deadline_ms: now + 100 }
  hub.receive(operator, JSON.stringify(m)); const n = robot.messages.length; m.seq++; hub.receive(operator, JSON.stringify(m)); assert.equal(robot.messages.length, n)
  now += 101; m.seq++; m.event_id++; hub.receive(operator, JSON.stringify(m)); assert.equal(robot.messages.length, n)
  m.deadline_ms = now + 100; m.event_ms = now; m.seq++; hub.receive(operator, JSON.stringify(m)); assert.equal(robot.messages.length, n + 1)
  const oldSession = m.session_id; hub.detach(operator); const reconnect = auth(hub, 'operator'); m.session_id = oldSession; m.seq++; hub.receive(reconnect, JSON.stringify(m)); assert.equal(robot.messages.at(-1).type, 'operator_joined')
})
test('direct stop survives absent phone; signed applied acknowledgement is required', () => {
  class Socket extends EventEmitter { constructor () { super(); this.sent = [] } send (raw, port, host) { this.sent.push({ m: JSON.parse(raw), port, host }) } close () {} }
  const socket = new Socket(), states = [], stop = new OperatorStop({ key, socket, clock: () => 1000, onStatus: m => states.push(m) })
  const hello = socket.sent[0].m, boot = '0123456789abcdef', reply = { type: 'hello', nonce: hello.nonce, sender_ms: hello.sender_ms, boot, time_ms: 12, calibration_id: 'a'.repeat(64), rx_ms: 10, tx_ms: 12, owner_epoch: 3, owner_seq: 44, firmware_source_id: "e".repeat(64) }
  reply.mac = sign(key, `H|${boot}|12|${hello.nonce}|${reply.calibration_id}|10|12|1000|3|44|${reply.firmware_source_id}`)
  socket.emit('message', Buffer.from(JSON.stringify(reply)), { address: '192.168.4.1', port: 4242 }); assert.equal(stop.request(), true)
  const packet = socket.sent.at(-1).m; assert.equal(packet.type, 'operator_stop'); assert.equal(socket.sent.at(-1).port, 4243); assert.equal(states.at(-1).status, 'requested')
  const ack = { type: 'operator_stop_applied', boot, request_id: packet.request_id, applied_ms: 20, mac: '0'.repeat(64) }
  socket.emit('message', Buffer.from(JSON.stringify(ack)), { address: '192.168.4.1', port: 4243 }); assert.ok(stop.pending)
  ack.mac = sign(sign(key, 'operator-stop-key-v1'), `SA|${boot}|${packet.request_id}|20`)
  socket.emit('message', Buffer.from(JSON.stringify(ack)), { address: '192.168.4.1', port: 4243 }); assert.equal(stop.pending, null); assert.equal(states.at(-1).status, 'applied')
  assert.ok(socket.sent.every(p => ['hello', 'operator_stop'].includes(p.m.type))); stop.close()
})
test('v2 protocol fixture matches Node, Java and Python canonical convention', () => {
  const fixture = require('../../../../maxbot/config/fixtures/protocol-golden.v1.json'); assert.equal(sign(fixture.key, fixture.canonicalMotion), fixture.motionMac); assert.equal(sign(fixture.operatorKey, fixture.canonicalStop), fixture.stopMac)
})
test('stale asynchronous planner result is discarded after dependency change', async () => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'planner-host-test-')); const executable = path.join(directory, 'worker')
  await fs.writeFile(executable, '#!/usr/bin/env node\nsetTimeout(()=>console.log(JSON.stringify({ok:true,reference:{synthetic:true}})),150)\n', { mode: 0o700 })
  const prior = process.env.MAXBOT_PYTHON; process.env.MAXBOT_PYTHON = executable
  let state = { stopped: true, mapEpoch: 'a' }; const messages = []; const planner = new Planner({ root: path.resolve(__dirname, '../../../..'), publish: m => messages.push(m), snapshot: () => state })
  try { const result = planner.run({ schemaVersion: 1, stopped: true, course: {mapEpoch:"a"} }); setTimeout(() => { state = { stopped: true, mapEpoch: 'b' } }, 50); await result; assert.equal(messages.at(-1).status, 'discarded') } finally { planner.cancel(); if (prior === undefined) delete process.env.MAXBOT_PYTHON; else process.env.MAXBOT_PYTHON = prior; await fs.rm(directory, { recursive: true, force: true }) }
})
test('Wi-Fi export bounds, partial offsets, hash and private final file', async () => {
  const { Exports } = require('../services/exports'), crypto = require('node:crypto')
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'maxbot-export-test-')), store = new Exports(directory), bytes = Buffer.from('synthetic-run-only'), id = 'a'.repeat(32)
  try {
    await store.receive({ operation: 'begin', id, size: bytes.length, sha256: crypto.createHash('sha256').update(bytes).digest('hex') })
    await assert.rejects(store.receive({ operation: 'chunk', id, offset: 4, data: bytes.toString('base64') }), /offset/)
    await store.receive({ operation: 'chunk', id, offset: 0, data: bytes.toString('base64') }); const result = await store.receive({ operation: 'end', id })
    assert.equal(result.status, 'saved'); assert.deepEqual(await fs.readFile(result.path), bytes); assert.equal((await fs.stat(result.path)).mode & 0o777, 0o600)
    await assert.rejects(store.receive({ operation: 'begin', id: '../bad', size: 10, sha256: 'a'.repeat(64) }), /ID/)
  } finally { await store.close(); await fs.rm(directory, { recursive: true, force: true }) }
})
