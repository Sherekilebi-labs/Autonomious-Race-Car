'use strict'

const test = require('node:test'), assert = require('node:assert/strict'), fs = require('node:fs/promises'), path = require('node:path'), os = require('node:os')
const { once } = require('node:events')
const { analyze, delta, stats } = require('../services/signal-analysis')
const { SignalMonitoring } = require('../services/signal-monitor')
const { start } = require('../server'), { sign } = require('../hub'), { WebSocket } = require('ws')

test('timing calculations preserve integer ns, deduplicate command lineage and distinguish physical motion', () => {
  assert.equal(delta('999999999900000001', '999999999900000000'), .000001)
  assert.equal(delta(null, '2'), null)
  assert.deepEqual([stats([{ ms: 1 }, { ms: 10 }]).p50, stats([{ ms: 1 }, { ms: 10 }]).p99], [1, 10])
  const records = [], put = event => records.push({ source: 'phone', event })
  for (let n = 0; n < 6; n++) {
    put({ type: 'vision_frame', frame_id: String(n), sensor_clock_comparable: true, sensor_timestamp_ns: '1000000000', image_available_ns: '1005000000', image_acquired_ns: '1006000000', pack_start_ns: '1007000000', published_ns: '1008000000', inference_start_ns: '1009000000', prepared_ns: '1010000000', inferred_ns: '1020000000', decision_ns: '1021000000', warning: true })
    put({ type: 'phone_warning_drawn', frame_id: String(n), draw_ns: '1030000000' })
  }
  put({ type: 'manual_input', source_state: '1010000000', accepted: true, output_produced_ns: '1011000000', phone_decided_ns: '1011500000', packet: { session_id: 'browser', input_id: 1, event_id: 1, input_perf_ms: 10, packet_perf_ms: 15, phone_received_ns: '1010000000', mapped_event_ns: '1005000000', mapped_input_ns: '1000000000', host_clock_uncertainty_ms: 2 } })
  put({ type: 'command_sent', boot: 'b', session: 's', seq: 1, mode: 'DRIVE_MANUAL', pwm: 200, source_state: '1010000000', phone_send_ns: '1012000000', phone_socket_sent_ns: '1013000000', policy_output_ns: '1011000000' })
  put({ type: 'applied', boot: 'b', session: 's', seq: 1, rx_ms: 14, validated_ms: 15, applied_ms: 19 })
  const output = { type: 'output_written', boot: 'b', session: 's', seq: 1, output_ms: 20, pwm: 200, authenticated: true, mapped_phone_output_ns: '1020000000', phone_received_ns: '1025000000', clock_uncertainty_ms: 1 }
  put(output); put(output)
  const result = analyze(records)
  assert.equal(result.metrics.warning_capture_to_phone_draw.n, 1); assert.equal(result.metrics.warning_capture_to_phone_draw.p50, 30)
  assert.equal(result.metrics.phone_handling.p50,1.5); assert.equal(result.metrics.phone_tx_queue.p50,1);
  assert.equal(result.metrics.inference.p50, 10); assert.equal(result.metrics.input_to_output.p50, 20)
  assert.equal(result.metrics.input_to_output.max_clock_bound_ms, 3)
  assert.equal(result.metrics.raw_input_to_packet.p50, 5); assert.equal(result.metrics.esp_output.p50, 1)
  assert.equal(result.counts.pwm_writes, 1); assert.equal(result.actual_traction_motion.measured, false)
  output.mapped_phone_output_ns = '999000000'; const censored = analyze(records)
  assert.ok(censored.counts.clock_censored_intervals > 0); assert.equal(censored.metrics.input_to_output.n, 0)
})

test('Mac latency uses first presentation per frame and excludes warm-up frames', () => {
  const records=[]
  for(let i=0;i<6;i++)records.push({source:'phone',event:{type:'vision_frame',frame_id:String(i),warning:true,image_available_ns:'1000000000',decision_ns:'1021000000',host_clock:{host_mapped_decision_ms:1021,host_clock_uncertainty_ms:2}}})
  for(const [id,wall,perf] of [['0',1080,1],['5',1100,100],['5',1500,500]])records.push({source:'browser',event:{type:'browser_warning_presented',frame_id:id,presented_wall_ms:wall,presented_perf_ms:perf}})
  const result=analyze(records)
  assert.equal(result.metrics.decision_to_browser_opportunity.n,1)
  assert.equal(result.metrics.decision_to_browser_opportunity.p50,79)
  assert.equal(result.metrics.warning_image_to_browser_opportunity.p50,100)
})

test('warning snapshots cannot re-display older frames or refresh their age', () => {
  const {SignalPanel}=require('../public/signal-monitor'),panel=Object.create(SignalPanel.prototype),elements=new Map(),events=[]
  panel.get=id=>{if(!elements.has(id))elements.set(id,{dataset:{}});return elements.get(id)}
  panel.record=e=>events.push(e)
  const original=global.requestAnimationFrame;global.requestAnimationFrame=callback=>callback()
  try{
    panel.warning({frame_id:'100000000000000001',status:'warning'})
    const age=panel.warningAt
    panel.warning({frame_id:'100000000000000000',status:'no_warning_detected'})
    panel.warning({frame_id:'100000000000000001',status:'warning'})
    assert.equal(panel.get('collision-warning').dataset.state,'warning');assert.equal(panel.warningAt,age)
    assert.equal(events.filter(e=>e.type==='browser_warning_presented').length,1)
    panel.warning({status:'unavailable_or_stale'});assert.equal(panel.get('collision-warning').dataset.state,'unavailable')
    panel.resetFrames();panel.warning({frame_id:'1',status:'no_warning_detected'});assert.equal(panel.get('collision-warning').dataset.state,'quiet')
  }finally{global.requestAnimationFrame=original}
})

test('automatic journal, complete export, redaction and incomplete disconnect recovery', async () => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'maxbot-signal-unit-')), sent = [], published = []
  const monitor = new SignalMonitoring({ directory, sendRobot: m => sent.push(m), publish: m => published.push(m), snapshot: () => ({ received_ms: Date.now(), signal_monitor_available: true, mode: 'STOPPED' }) })
  try {
    await monitor.request({ operation: 'start', options: { notes: 'bench' } }); const id = sent[0].monitor_id
    assert.ok((await fs.readFile(monitor.active.journal, 'utf8')).includes('start'))
    monitor.phone({ type: 'signal_trace', monitor_id: id, batch: 1, dropped: 0, phase: 'recording', events: [{ ordinal: 1, event: { type: 'monitor_started', metadata: { pairingKey: 'secret', sha256: 'model' } } }] })
    monitor.stop('operator_stop')
    monitor.phone({ type: 'signal_trace', monitor_id: id, batch: 2, dropped: 0, phase: 'stopped', events: [{ ordinal: 2, event: { type: 'monitor_stopped' } }] })
    for (let n = 0; n < 100 && monitor.active; n++) await new Promise(resolve => setTimeout(resolve, 10))
    assert.equal(monitor.last.result, 'complete')
    const raw = await fs.readFile(monitor.last.file, 'utf8'), result = JSON.parse(raw)
    assert.equal(raw.includes('secret'), false); assert.equal(result.summary.actual_traction_motion.measured, false)
    assert.match(await fs.readFile(monitor.last.markdown, 'utf8'), /## Calculations/)
    assert.equal(monitor.download(monitor.last.download), monitor.last.file); assert.equal(monitor.download('/signal-reports/../../etc/passwd'), null)
    assert.equal((await fs.stat(monitor.last.file)).mode & 0o777, 0o600)
    await monitor.request({ operation: 'start' }); monitor.disconnect('phone'); await monitor.finish()
    assert.equal(monitor.last.result, 'incomplete')
  } finally { monitor.close(); if (monitor.active) await monitor.finish(); await fs.rm(directory, { recursive: true, force: true }) }
})

function awaitMessage (peer, type) { return new Promise(resolve => { const handler = bytes => { const m = JSON.parse(bytes); if (m.type === type) { peer.off('message', handler); resolve(m) } }; peer.on('message', handler) }) }
async function connect (port, role, key) {
  const peer = new WebSocket(`ws://127.0.0.1:${port}/${role}`), challenge = await awaitMessage(peer, 'challenge'), ready = awaitMessage(peer, 'authenticated')
  peer.send(JSON.stringify({ type: 'auth', role, mac: sign(key, `AUTH|${role}|${challenge.nonce}`) })); await ready; return peer
}
test('authenticated WebSocket monitoring round-trip and actual JSON download', async () => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'maxbot-signal-integration-')), key = 'synthetic-monitor-test-key-at-least-32'
  const app = start({ key, port: 0, signalDirectory: directory }); await once(app.server, 'listening'); const port = app.server.address().port
  let robot, operator, timer, seq = 0
  try {
    robot = await connect(port, 'robot', key); operator = await connect(port, 'operator', key)
    const send = m => operator.send(JSON.stringify({ ...m, seq: ++seq }))
    send({ type: 'heartbeat' }); timer = setInterval(() => send({ type: 'heartbeat' }), 100)
    const tele = awaitMessage(operator, 'telemetry'); robot.send(JSON.stringify({ type: 'telemetry', signal_monitor_available: true, mode: 'STOPPED' })); await tele
    const started = awaitMessage(robot, 'signal_monitor'); send({ type: 'signal_monitor', operation: 'start', options: { notes: 'integration' } }); const request = await started
    assert.equal(request.operation, 'start')
    robot.send(JSON.stringify({ type: 'signal_trace', monitor_id: request.monitor_id, batch: 1, dropped: 0, phase: 'recording', events: [{ ordinal: 1, event: { type: 'monitor_started' } }] }))
    const stopped = awaitMessage(robot, 'signal_monitor'); send({ type: 'signal_monitor', operation: 'stop' }); assert.equal((await stopped).operation, 'stop')
    const saved = new Promise(resolve => { const handler = bytes => { const m = JSON.parse(bytes); if (m.type === 'signal_monitor_status' && m.status === 'saved') { operator.off('message', handler); resolve(m) } }; operator.on('message', handler) })
    robot.send(JSON.stringify({ type: 'signal_trace', monitor_id: request.monitor_id, batch: 2, dropped: 0, phase: 'stopped', events: [{ ordinal: 2, event: { type: 'monitor_stopped' } }] }))
    const result = await saved; assert.equal(result.result, 'complete')
    const response = await fetch(`http://127.0.0.1:${port}${result.download}`); assert.equal(response.status, 200)
    assert.equal((await response.json()).artifactType, 'maxbot_signal_propagation')
    assert.equal((await fetch(`http://127.0.0.1:${port}/signal-monitor.js`)).status, 200)
  } finally { clearInterval(timer); operator?.terminate(); robot?.terminate(); app.server.closeAllConnections(); await new Promise(resolve => app.server.close(resolve)); await fs.rm(directory, { recursive: true, force: true }) }
})
