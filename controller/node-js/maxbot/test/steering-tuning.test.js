'use strict'
const test = require('node:test'), assert = require('node:assert/strict'), dgram = require('node:dgram')
const { once } = require('node:events')
const { SteeringTuning, FIELDS, requestText, responseText, validSettings, sign } = require('../services/steering-tuning')
const key = 'synthetic-steering-test-key-at-least-32'
const settings = { kp_milli: 50000, ki_milli: 0, kd_milli: 150, deadband_mdeg: 750, min_pwm: 80, max_pwm: 255, filter_millihz: 12000 }
async function fixture () {
  const socket = dgram.createSocket('udp4'); socket.bind(0, '127.0.0.1'); await once(socket, 'listening')
  const state = { ...settings, saved: false, armed: false, boot: 'abc01234', commands: [], badFirst: false, modern: true, applied: 0, stored: null }
  socket.on('message', (bytes, remote) => {
    const m = JSON.parse(bytes); state.commands.push(m)
    assert.equal(m.mac, sign(key, requestText(m)))
    if (m.operation !== 'get') {
      assert.equal(m.boot, state.boot); assert.ok(m.expires_ms > Date.now()); assert.ok(m.expires_ms - m.issued_ms <= 1500)
      assert.equal(state.armed, false); FIELDS.forEach(k => { state[k] = m[k] }); state.applied++
      state.saved = m.operation === 'save'; if (state.saved) state.stored = { ...state }
    }
    const out = { v: 1, type: 'steering_tuning_reply', boot: state.boot, request_id: m.request_id, status: m.operation === 'get' ? 'ok' : m.operation === 'save' ? 'saved' : 'applied', time_ms: Date.now(),
      ...Object.fromEntries(FIELDS.map(k => [k, state[k]])), saved: state.saved, armed: state.armed, drive_pwm: 0, target_mdeg: 1200, actual_mdeg: 900, steering_pwm: 80, p_milli: 15000, i_milli: 0, d_milli: -1200, pot_adc: 775 }
    if(state.modern)Object.assign(out,{diagnostics_version:2,pid_active:false,feedback_valid:true,feedback_min_mdeg:-60000,feedback_max_mdeg:60000,feedback_rejects:3,command_min_mdeg:-26000,command_max_mdeg:26000})
    out.mac = sign(key, responseText(out))
    if (state.badFirst) {
      socket.send(Buffer.from(JSON.stringify({ ...out, mac: '0'.repeat(64) })), remote.port, remote.address)
      if(state.modern)socket.send(Buffer.from(JSON.stringify({...out,feedback_max_mdeg:65000})),remote.port,remote.address)
    }
    socket.send(Buffer.from(JSON.stringify(out)), remote.port, remote.address)
  })
  const client = new SteeringTuning({ key, host: '127.0.0.1', port: socket.address().port })
  return { state, client, async close () { client.close(); await new Promise(resolve => socket.close(resolve)) } }
}
test('authenticated settings read/apply/save with exact integer values and signed verified replies', async () => {
  const f = await fixture()
  try {
    f.state.badFirst = true
    const first = await f.client.request('get'); assert.equal(first.saved, false); assert.equal(first.actual_mdeg, 900); assert.equal(first.mac, undefined)
    assert.equal(first.pid_active,false);assert.equal(first.feedback_min_mdeg,-60000);assert.equal(first.feedback_max_mdeg,60000)
    const apply = await f.client.request('apply', { ...settings, kp_milli: 55000 }); assert.equal(apply.status, 'applied'); assert.equal(apply.kp_milli, 55000); assert.equal(apply.saved, false)
    const save = await f.client.request('save', settings); assert.equal(save.status, 'saved'); assert.equal(save.saved, true); assert.equal(f.state.stored.min_pwm, 80)
    assert.equal(f.state.applied, 2)
  } finally { await f.close() }
})
test('armed state blocks configuration writes and invalid gain/PWM combinations are rejected', async () => {
  const f = await fixture()
  try {
    f.state.armed = true; await assert.rejects(f.client.request('save', settings), /disarm/); assert.equal(f.state.applied, 0)
    for (const bad of [{ ...settings, min_pwm: 256 }, { ...settings, kp_milli: NaN }, { ...settings, deadband_mdeg: 0 }, { ...settings, kd_milli: -1 }, { ...settings, max_pwm: 60 }]) {
      assert.equal(validSettings(bad), false); await assert.rejects(f.client.request('apply', bad), /Check PID/)
    }
  } finally { await f.close() }
})

test('legacy replies remain authenticated and report no PID activity capability', async () => {
  const f=await fixture();try{f.state.modern=false;const m=await f.client.request('get');assert.equal(m.diagnostics_version,undefined);assert.equal(m.actual_mdeg,900)}finally{await f.close()}
})
