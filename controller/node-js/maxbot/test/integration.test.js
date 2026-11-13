'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const { once } = require('node:events')
const { WebSocket } = require('ws')
const { start } = require('../server')
const { sign } = require('../hub')
const key = '0123456789abcdef0123456789abcdef'
async function next (peer) { const [bytes] = await once(peer, 'message'); return JSON.parse(bytes.toString()) }
async function connect (port, role) {
  const peer = new WebSocket(`ws://127.0.0.1:${port}/${role}`)
  const challenge = await next(peer)
  const response = next(peer)
  peer.send(JSON.stringify({ type: 'auth', role, mac: sign(key, `AUTH|${role}|${challenge.nonce}`) }))
  assert.equal((await response).type, 'authenticated')
  return peer
}
test('HTTP + actual WebSocket pairing, telemetry, stop and disconnect path', async () => {
  const { server } = start({ key, port: 0 }); await once(server, 'listening'); const port = server.address().port
  let robot, operator
  try {
    const home = await fetch(`http://127.0.0.1:${port}/`); assert.equal(home.status, 200); assert.match(await home.text(), /Emergency stop/)
    const ramp = await fetch(`http://127.0.0.1:${port}/manual-control.js`); assert.equal(ramp.status, 200); assert.match(await ramp.text(), /class ManualRamp/)
    assert.equal((await fetch(`http://127.0.0.1:${port}/../server.js`)).status, 404)
    robot = await connect(port, 'robot')
    const joined = next(robot); operator = await connect(port, 'operator'); assert.equal((await joined).type, 'operator_joined')
    // The operator receives robot_connected before the requested telemetry.
    const telemetry = new Promise(resolve => operator.on('message', bytes => { const m = JSON.parse(bytes); if (m.type === 'telemetry') resolve(m) }))
    robot.send(JSON.stringify({ type: 'telemetry', mode: 'DISARMED', battery_v: 8.1 }))
    assert.equal((await telemetry).battery_v, 8.1)
    const manualBattery = new Promise(resolve => operator.on('message', bytes => {
      const m = JSON.parse(bytes); if (m.type === 'telemetry' && m.battery_monitoring === false) resolve(m)
    }))
    robot.send(JSON.stringify({ type: 'telemetry', mode: 'DISARMED', battery_monitoring: false, battery_v: null, battery_percent: null }))
    const unavailable = await manualBattery
    assert.equal(unavailable.battery_v, null); assert.equal(unavailable.battery_percent, null)
    let forwarded = next(robot); operator.send(JSON.stringify({ type: 'heartbeat', seq: 1 })); assert.equal((await forwarded).type, 'heartbeat')
    forwarded = next(robot); operator.send(JSON.stringify({ type: 'command', action: 'arm_manual', seq: 2 })); assert.equal((await forwarded).action, 'arm_manual')
    forwarded = next(robot); operator.send(JSON.stringify({ type: 'command', action: 'estop', seq: 3 })); assert.equal((await forwarded).action, 'estop')
    const stopped = next(robot); operator.close(); assert.equal((await stopped).type, 'operator_left')
  } finally {
    if (operator) operator.terminate(); if (robot) robot.terminate()
    server.closeAllConnections(); await new Promise(resolve => server.close(resolve))
  }
})
