#!/usr/bin/env node
'use strict'
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const crypto = require('node:crypto')
const { once } = require('node:events')
const dir = path.resolve(__dirname, '../../dist/manual-drive')
const manifest = JSON.parse(fs.readFileSync(path.join(dir, 'manifest.json'), 'utf8'))
const config = JSON.parse(fs.readFileSync(path.join(dir, 'maxbot.runtime.json'), 'utf8'))
assert.equal(manifest.calibrationId, config.firmwareCalibrationId)
assert.equal(manifest.manualMaxPwm, 255)
const ceiling = Math.min(255, Math.floor(255 * config.power.pwmReferenceMaxV / config.power.packMaxV))
assert.equal(manifest.driveDutyMax, ceiling); assert.equal(manifest.steerDutyMax, ceiling)
const mapping = fs.readFileSync(path.join(dir, 'sketch/maxbot/actuator_pwm.generated.h'), 'utf8')
assert(mapping.includes(`#define DRIVE_MAX_DUTY ${ceiling}`)); assert(mapping.includes(`#define STEER_MAX_DUTY ${ceiling}`))
assert.equal(manifest.transport, 'wifi')
assert.equal(config.hardware.wifiMode, 'ap')
assert.equal(config.network.espHost, '192.168.4.1')
assert.equal(manifest.brakeVerified, config.hardware.driveBrakeVerified)
assert.equal(manifest.batteryMonitoring, false)
assert.deepEqual(manifest.commissioning, config.commissioning)
for (const [file, hash] of Object.entries(manifest.artifacts)) {
  assert.equal(crypto.createHash('sha256').update(fs.readFileSync(path.join(dir, file))).digest('hex'), hash, file)
}
const { WebSocket } = require(path.join(dir, 'controller/node-js/maxbot/node_modules/ws'))
const { start } = require(path.join(dir, 'controller/node-js/maxbot/server'))
const key = config.network.pairingKey
async function next(peer) { return JSON.parse((await once(peer, 'message'))[0].toString()) }
async function connect(port, role) {
  const peer = new WebSocket(`ws://127.0.0.1:${port}/${role}`)
  const challenge = await next(peer), response = next(peer)
  peer.send(JSON.stringify({ type: 'auth', role, mac: crypto.createHmac('sha256', key).update(`AUTH|${role}|${challenge.nonce}`).digest('hex') }))
  assert.equal((await response).type, 'authenticated')
  return peer
}
async function main() {
  const { server } = start({ key, host: '127.0.0.1', port: 0 })
  let robot, operator
  try {
    await once(server, 'listening')
    const port = server.address().port
    const home = await fetch(`http://127.0.0.1:${port}/`)
    assert.match(await home.text(), /Arm manual commissioning/)
    for (const name of ['Regular', 'Bold', 'Medium']) {
      const font = await fetch(`http://127.0.0.1:${port}/font/Gilroy-${name}.ttf`)
      assert.equal(font.status, 200); assert((await font.arrayBuffer()).byteLength > 1000)
    }
    robot = await connect(port, 'robot')
    const joined = next(robot); operator = await connect(port, 'operator'); assert.equal((await joined).type, 'operator_joined')
    let forwarded = next(robot)
    operator.send(JSON.stringify({ type: 'heartbeat', seq: 1 })); assert.equal((await forwarded).type, 'heartbeat')
    forwarded = next(robot)
    operator.send(JSON.stringify({ type: 'command', action: 'arm_manual', seq: 2 })); assert.equal((await forwarded).action, 'arm_manual')
    forwarded = next(robot)
    operator.send(JSON.stringify({ type: 'command', action: 'manual', steering_deg: 8, pwm: 150, deadman: true, seq: 3 }))
    assert.equal((await forwarded).pwm, 150)
    const stopped = next(robot); operator.close(); assert.equal((await stopped).type, 'operator_left')
    console.log('PASS packaged hashes, private configuration consistency, offline fonts, WebSocket pairing, manual command and operator-disconnect path')
  } finally {
    robot?.terminate(); operator?.terminate(); server.closeAllConnections(); await new Promise(resolve => server.close(resolve))
  }
}
main().catch(error => { console.error(error); process.exitCode = 1 })
