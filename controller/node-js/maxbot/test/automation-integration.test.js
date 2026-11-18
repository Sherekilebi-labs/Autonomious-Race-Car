'use strict'
const test = require('node:test'), assert = require('node:assert/strict'), fs = require('node:fs/promises'), path = require('node:path'), os = require('node:os'), crypto = require('node:crypto'), { once } = require('node:events'), { spawn } = require('node:child_process'), WS = require('ws')
const { start } = require('../server'), { sign } = require('../hub'), { tokenFor } = require('../services/automation-api')
const key = 'synthetic-automation-test-key-0123456789abcdef'
const delay = ms => new Promise(resolve => setTimeout(resolve, ms))
async function device (port, role) {
  const ws = new WS(`ws://127.0.0.1:${port}/${role}`);let session, outbound = 0;const messages = [], waiters = []
  const signed = role !== 'operator'
  const send = m => { if (signed) { const payload = JSON.stringify(m), sequence = ++outbound; m = { type: 'signed_message', session_id: session, sequence, payload, mac: sign(key, `C|${session}|${sequence}|${payload}`) } }; ws.send(JSON.stringify(m)) }
  let readyResolve;const ready = new Promise(resolve => { readyResolve = resolve });const peer = { ws, send, messages, handler: null }
  ws.on('message', bytes => {
    let m = JSON.parse(bytes)
    if (m.type === 'challenge') return ws.send(JSON.stringify({ type: 'auth', role, ...(signed ? { control_signing: 1 } : {}), mac: sign(key, `AUTH|${role}|${m.nonce}${signed ? '|signed-v1' : ''}`) }))
    if (m.type === 'authenticated') { session = m.session_id; readyResolve(); return }
    if (signed) { assert.equal(m.mac, sign(key, `S|${session}|${m.sequence}|${m.payload}`)); m = JSON.parse(m.payload) }
    messages.push(m); for (const w of [...waiters]) if (w.match(m)) { waiters.splice(waiters.indexOf(w), 1); w.resolve(m) }
    peer.handler?.(m)
  })
  peer.wait = match => new Promise((resolve, reject) => { const timer = setTimeout(() => reject(new Error(`Timed out waiting for ${role}`)), 5000); waiters.push({ match, resolve: m => { clearTimeout(timer); resolve(m) } }) })
  await ready;return peer
}
test('signed observer + Pixel + attended dashboard save a complete bundle through the authenticated local API', { timeout: 15000 }, async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'maxbot-automation-e2e-')), app = start({ key, port: 0, signalDirectory: path.join(dir, 'signals') });await once(app.server, 'listening');const port = app.server.address().port;app.experiments.directory = path.join(dir, 'experiments')
  let robot, observer, operator, timer, sequence = 0, recording = false
  const api = async (operation, args = {}, auth = true) => fetch(`http://127.0.0.1:${port}/automation`, { method: 'POST', headers: { 'Content-Type': 'application/json', ...(auth ? { Authorization: `Bearer ${tokenFor(key)}` } : {}) }, body: JSON.stringify({ operation, args }) })
  try {
    assert.equal((await api('status', {}, false)).status, 403)
    robot = await device(port, 'robot');observer = await device(port, 'observer');operator = await device(port, 'operator')
    const tele = () => { robot.send({ type: 'telemetry', signal_monitor_available: true, vehicle_connected: true, vehicle_transport: 'usb', experiment_protocol: 1, experiment_thermal_policy:'severe_bounded_v1', thermal_status:3, mode: 'STOPPED', armed: false, pwm: 0, fault: '', mcu_fault: '' });observer.send({ type: 'observer_status', camera_ready: true, recording, capture_age_ms: 5 });operator.send({ type: 'heartbeat', seq: ++sequence }) };tele();timer = setInterval(tele, 80)
    robot.handler = async m => {
      if (m.type === 'signal_monitor') robot.send({ type: 'signal_trace', monitor_id: m.monitor_id, batch: m.operation === 'start' ? 1 : 2, dropped: 0, phase: m.operation === 'start' ? 'recording' : 'stopped', events: [{ ordinal: m.operation === 'start' ? 1 : 2, event: { type: m.operation === 'start' ? 'monitor_started' : 'monitor_stopped' } }] })
      if(m.type==='command'&&m.action==='export'){
        const bytes=Buffer.from('synthetic Pixel ZIP transfer fixture'),id=crypto.randomBytes(16).toString('hex')
        for(const packet of [{operation:'begin',size:bytes.length,sha256:crypto.createHash('sha256').update(bytes).digest('hex')},{operation:'chunk',offset:0,data:bytes.toString('base64')},{operation:'end'}]){
          const request_id=crypto.randomUUID(),ack=robot.wait(r=>r.type==='export_ack'&&r.request_id===request_id);robot.send({type:'export_transfer',id,request_id,...packet});assert.notEqual((await ack).status,'failed')
        }
      }
      if (m.type === 'experiment' && m.operation === 'start') {assert.equal(m.allow_severe_thermal,true);setTimeout(() => robot.send({ type: 'experiment_status', experiment_id: m.experiment_id, status: 'completed', phone_ns: '1000000000' }), 60)}
    }
    observer.handler = async m => {
      if (m.type !== 'observer_command') return
      if (m.operation === 'start') { recording = true;tele() }
      if (m.operation === 'stop') recording = false
      if (m.operation === 'upload') for (const name of ['video.mp4', 'frames.jsonl', 'samples.jsonl', 'observer.json']) {
        const bytes = Buffer.from(name==='observer.json'?JSON.stringify({fixture:true}):`synthetic fixture ${name}`)
        for (const packet of [{ operation: 'begin', name, bytes: bytes.length, sha256: crypto.createHash('sha256').update(bytes).digest('hex') }, { operation: 'chunk', offset: 0, data: bytes.toString('base64') }, { operation: 'end' }]) {
          const request_id = crypto.randomUUID(), ack = observer.wait(r => r.type === 'observer_upload_ack' && r.request_id === request_id);observer.send({ type: 'observer_upload', experiment_id: m.experiment_id, request_id, ...packet });assert.equal((await ack).error, undefined)
        }
      }
      observer.send({ type: 'observer_result', request_id: m.request_id })
    }
    operator.send({ type: 'experiment', operation: 'attend', seq: ++sequence, options: { enabled: true, usb_power_verified: true, area_clear: true, observer_framed: true } });await delay(30)
    const prepared = await (await api('prepare', { recipe: 'warning',parameters:{allow_severe_thermal:true} })).json();assert.ok(prepared.result.prepared.id)
    const result = await (await api('start', { prepared_id: prepared.result.prepared.id })).json();assert.equal(result.error, undefined)
    for (let i = 0; i < 100 && app.experiments.active; i++) await delay(40)
    assert.equal(app.experiments.last.status, 'saved', JSON.stringify(app.experiments.last));assert.equal(app.experiments.last.summary.complete, true)
    const saved = app.experiments.last.directory;assert.equal(JSON.parse(await fs.readFile(path.join(saved, 'manifest.json'))).course_profile.cones.baseDiameterM, .135)
    const manifest=JSON.parse(await fs.readFile(path.join(saved,'manifest.json')));assert.equal(manifest.parameters.allow_severe_thermal,true);assert.equal(manifest.limits.max_pwm,110);assert.equal(manifest.limits.powered_total_ms,100);assert.equal(JSON.parse(await fs.readFile(path.join(saved, 'summary.json'))).physically_validated, false);assert.ok(app.experiments.last.summary.pixel_export.file)
    assert.match(await fs.readFile(path.join(saved, 'report.md'), 'utf8'), /Physical/)
    // A real STDIO MCP subprocess retrieves status from the same service; no hardware involved.
    const child = spawn(process.execPath, [path.resolve(__dirname, '../mcp.cjs')], { env: { ...process.env, MAXBOT_PAIRING_KEY: key, MAXBOT_PORT: String(port) }, stdio: ['pipe', 'pipe', 'pipe'] })
    let output = '';child.stdout.on('data', b => { output += b })
    child.stdin.write(JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'initialize', params: {} }) + '\n')
    child.stdin.write(JSON.stringify({ jsonrpc: '2.0', id: 2, method: 'tools/call', params: { name: 'maxbot_status', arguments: {} } }) + '\n')
    for (let i = 0; i < 60 && output.trim().split('\n').length < 2; i++) await delay(30)
    child.stdin.end();await once(child, 'exit');const replies = output.trim().split('\n').map(s => JSON.parse(s));assert.equal(replies.find(r => r.id === 1).result.serverInfo.name, 'maxbot-experiments');assert.equal(replies.find(r => r.id === 2).result.isError, undefined)
  } finally { clearInterval(timer);robot?.ws.terminate();observer?.ws.terminate();operator?.ws.terminate();app.server.closeAllConnections();await new Promise(resolve => app.server.close(resolve));await fs.rm(dir, { recursive: true, force: true }) }
})
