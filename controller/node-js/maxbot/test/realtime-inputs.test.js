'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const { RealtimeInputs } = require('../public/realtime-inputs')
function rig () {
  let now = 1000
  const packets = [], fallback = [], signatures = []
  const lane = new RealtimeInputs({ session: 'operator', sign: async text => { signatures.push(text); return 'signature' }, fallback: p => fallback.push(p), now: () => now })
  const channel = { readyState: 'open', bufferedAmount: 0, send: s => packets.push(JSON.parse(s)), close () { this.readyState = 'closed' } }
  lane.bind(channel)
  const receive = m => lane.receive(JSON.stringify(m), channel)
  return { lane, channel, packets, fallback, signatures, receive, advance: ms => { now += ms },
    async auth () { await receive({ type: 'control_challenge', session_id: 'operator', nonce: 'a'.repeat(32) }); await receive({ type: 'control_ready', session_id: 'operator', nonce: 'a'.repeat(32) }) },
    packet: (id, active = true) => ({ type: 'command', action: 'manual', event_id: id, event_ms: 1000, deadline_ms: 1300, deadman: active, pwm: 17, steering_deg: 2, session_id: 'operator' }) }
}
test('direct control requires session-bound authentication; manual payload and deadline are unchanged', async () => {
  const r = rig()
  try {
    r.lane.send(r.packet(1)); assert.equal(r.fallback.length, 1)
    await r.receive({ type: 'control_ready', session_id: 'operator', nonce: 'a'.repeat(32) }); assert.equal(r.lane.ready, false)
    await r.receive({ type: 'control_challenge', session_id: 'other', nonce: 'a'.repeat(32) }); assert.equal(r.signatures.length, 0)
    await r.auth(); assert.deepEqual(r.signatures, ['RTC|operator|' + 'a'.repeat(32)])
    assert.equal(r.lane.send(r.packet(2)), true); assert.deepEqual(r.packets.at(-1), r.packet(2)); assert.equal(r.fallback.length, 1)
    r.advance(7); await r.receive({ type: 'control_ack', event_id: 2 }); assert.equal(r.lane.stats().round_trip_ms, 7)
    r.lane.send(r.packet(3, false)); assert.deepEqual(r.fallback.at(-1), r.packet(3, false))
  } finally { r.lane.close() }
})
test('blocked, lossy, stale and closed channels fall back immediately without extending deadlines', async () => {
  const r = rig()
  try {
    await r.auth(); r.lane.send(r.packet(1)); r.advance(121)
    r.lane.send(r.packet(2)); assert.equal(r.fallback.at(-1).deadline_ms, 1300)
    r.channel.bufferedAmount = 2048; r.lane.send(r.packet(3)); assert.equal(r.fallback.at(-1).event_id, 3)
    r.channel.bufferedAmount = 0; r.advance(200); r.lane.ping(); const ping = r.packets.at(-1)
    r.advance(3); await r.receive({ type: 'control_pong', id: ping.id }); assert.equal(r.lane.send(r.packet(4)), true)
    r.channel.readyState = 'closed'; assert.equal(r.lane.send(r.packet(5)), false)
    r.lane.close(); assert.equal(r.lane.send(r.packet(6)), false)
  } finally { r.lane.close() }
})
test('clock samples return Mac timestamps and late channel callbacks cannot restore authority', async () => {
  const r = rig()
  try {
    await r.auth(); await r.receive({ type: 'control_clock', session_id: 'operator', t1: 77 })
    assert.equal(r.packets.at(-1).t1, 77); assert.ok(r.packets.at(-1).t3 >= r.packets.at(-1).t2)
    r.lane.close(); await r.receive({ type: 'control_ready', session_id: 'operator', nonce: 'a'.repeat(32) }); assert.equal(r.lane.ready, false)
  } finally { r.lane.close() }
})
