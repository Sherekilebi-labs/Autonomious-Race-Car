'use strict'

const fs = require('node:fs'), fsp = require('node:fs/promises'), path = require('node:path'), crypto = require('node:crypto'), os = require('node:os')
const { Worker } = require('node:worker_threads')
const redact = value => {
  if (Array.isArray(value)) return value.map(redact)
  if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value).filter(([k]) => !/^(mac|pairingKey|pairing_key|wifiPassword|password|key)$/i.test(k)).map(([k, v]) => [k, redact(v)]))
  return value
}

class SignalMonitoring {
  constructor ({ directory, sendRobot, publish, snapshot, root = path.resolve(__dirname, '../../../..') }) {
    this.directory = directory; this.sendRobot = sendRobot; this.publish = publish; this.snapshot = snapshot; this.root = root
    this.active = null; this.saved = new Map(); this.last = { type: 'signal_monitor_status', status: 'idle' }; this.operations = Promise.resolve()
  }
  request (m) {
    this.operations = this.operations.catch(() => {}).then(async () => {
      if (m.operation === 'start') await this.start(m.options || {})
      else if (m.operation === 'stop') this.stop('operator_stop')
      else if (m.operation === 'status') this.publish(this.current())
      else if (m.operation === 'events' && Array.isArray(m.events) && m.events.length <= 32) {
        for (const event of m.events) if (event && typeof event.type === 'string') {
          const a = this.active
          if (a && Number.isSafeInteger(event.browser_ordinal)) { if (event.browser_ordinal <= a.browserOrdinal) continue; a.browserGaps += event.browser_ordinal - a.browserOrdinal - 1; a.browserOrdinal = event.browser_ordinal }
          if (a && Number.isSafeInteger(event.browser_dropped)) a.browserDropped = Math.max(a.browserDropped, event.browser_dropped)
          this.append('browser', event)
        }
      }
    }).catch(error => this.publish({ type: 'signal_monitor_status', status: 'error', active: Boolean(this.active), error: error.message }))
    return this.operations
  }
  current () { return this.active ? { type: 'signal_monitor_status', status: this.active.stopping || this.active.finalizing ? 'saving' : 'recording', monitor_id: this.active.id, journal: this.active.journal, records: this.active.count, bytes: this.active.bytes } : this.last }
  async start (options) {
    if (this.active) throw new Error('Monitoring is already running')
    const t = this.snapshot()
    if (!t || Date.now() - t.received_ms > 600 || !t.signal_monitor_available) throw new Error('Connect the updated Pixel APK and wait for fresh telemetry')
    if (['RUN', 'SCOUT'].includes(t.mode)) throw new Error('Signal monitoring requires manual mode')
    await fsp.mkdir(this.directory, { recursive: true })
    const id = crypto.randomBytes(16).toString('hex'), basename = `maxbot-signals-${new Date().toISOString().replaceAll(':', '-')}-${id.slice(0, 8)}`
    const journal = path.join(this.directory, basename + '.jsonl'), stream = fs.createWriteStream(journal, { flags: 'wx', mode: 0o600 })
    await new Promise((resolve, reject) => { stream.once('open', resolve); stream.once('error', reject) })
    const sourceFiles = ['robot/android/maxbot/build.gradle', 'firmware/maxbot/maxbot.ino', 'controller/node-js/maxbot/public/app.js', 'controller/node-js/maxbot/public/signal-monitor.js', 'controller/node-js/maxbot/services/signal-analysis.js', 'controller/node-js/maxbot/services/signal-monitor.js', 'controller/node-js/maxbot/services/signal-report-worker.js']
    const hashes = {}
    for (const file of sourceFiles) { try { hashes[file] = crypto.createHash('sha256').update(await fsp.readFile(path.join(this.root, file))).digest('hex') } catch { hashes[file] = null } }
    let deploymentIdentity = null
    try { deploymentIdentity = JSON.parse(await fsp.readFile(path.join(this.root, 'signal-deployment.json'), 'utf8')) } catch {}
    const a = this.active = { id, basename, journal, stream, started_at: new Date().toISOString(), count: 0, bytes: 0,
      server_dropped: 0, lastBatch: 0, lastOrdinal: 0, batchGaps: 0, ordinalGaps: 0, phoneStopped: false, phoneStarted: false,
      browserOrdinal: 0, browserGaps: 0, browserDropped: 0,
      metadata: { node: process.version, platform: os.platform(), architecture: os.arch(), source_sha256: hashes,
        deployment_identity: deploymentIdentity,
        options: { notes: String(options.notes || '').slice(0, 2000), drive_rate: options.drive_rate, steering_rate: options.steering_rate,
          user_agent: String(options.user_agent || '').slice(0, 300), browser_clock: options.browser_clock },
        limits: { duration_s: 900, journal_bytes: 32 * 1024 * 1024, writer_queue_bytes: 2 * 1024 * 1024 },
        motor_endpoint: 'signed output_written after PWM API calls; physical traction onset requires external evidence' } }
    stream.on('error', error => { a.diskError = error.message; this.stop('disk_error') })
    this.append('session', { type: 'start', id, started_at: a.started_at, metadata: a.metadata })
    this.sendRobot({ type: 'signal_monitor', operation: 'start', monitor_id: id })
    a.duration = setTimeout(() => this.stop('duration_limit'), 900000); a.duration.unref()
    a.sync = setInterval(() => { if (typeof stream.fd === 'number') fs.fsync(stream.fd, error => { if (error && error.code !== 'EBADF') { a.diskError = error.message; this.stop('disk_error') } }) }, 1000); a.sync.unref()
    this.publish(this.current())
  }
  append (source, event) {
    const a = this.active
    if (!a || a.finalizing || a.diskError) return
    if (a.stream.writableLength > 2 * 1024 * 1024) { a.server_dropped++; return }
    const line = JSON.stringify({ source, server_mono_ns: process.hrtime.bigint().toString(), server_wall_ms: Date.now(), event: redact(event) }) + '\n'
    if (a.bytes + Buffer.byteLength(line) > 32 * 1024 * 1024) { a.server_dropped++; this.stop('size_limit'); return }
    a.stream.write(line); a.count++; a.bytes += Buffer.byteLength(line)
  }
  phone (m) {
    const a = this.active
    if (!a || m.monitor_id !== a.id) return
    if (m.type === 'signal_monitor_status' && m.error) { a.phoneError = String(m.error); this.stop('phone_error'); return }
    if (m.type !== 'signal_trace' || !Number.isSafeInteger(m.batch) || m.batch <= a.lastBatch || !Array.isArray(m.events) || m.events.length > 64) return
    a.batchGaps += m.batch - a.lastBatch - 1; a.lastBatch = m.batch
    this.append('phone_batch', { type: 'phone_batch', batch: m.batch, dropped: m.dropped, phase: m.phase, event_count: m.events.length })
    for (const item of m.events) {
      if (!Number.isSafeInteger(item.ordinal) || item.ordinal <= a.lastOrdinal || !item.event || typeof item.event.type !== 'string') continue
      a.ordinalGaps += item.ordinal - a.lastOrdinal - 1; a.lastOrdinal = item.ordinal
      if (item.event.type === 'monitor_started') a.phoneStarted = true
      this.append('phone', { ...item.event, _phone_ordinal: item.ordinal })
    }
    if (m.phase === 'stopped') { a.phoneStopped = true; this.finish().catch(error => this.failed(error, a)) }
  }
  telemetry (m) {
    if (!this.active) return
    const fields = ['type', 'time_ns', 'mode', 'armed', 'manual_armed', 'pwm', 'mcu_pwm', 'steering_deg', 'target_steering_deg', 'fault', 'mcu_fault', 'vehicle_control', 'dashboard_control', 'stream', 'collision_warning', 'thermal_limited', 'clock_uncertainty_ms', 'mcu_deadline_rejected', 'mcu_application_loss', 'wifi_latency_mode', 'control_max_gap_ms', 'camera_capabilities', 'recording_drops', 'steering_onset_ms', 'steering_settling_ms', 'steering_onset_samples', 'steering_settling_samples', 'steering_censored', 'steering_timing_definition']
    this.append('telemetry', Object.fromEntries(fields.filter(k => Object.hasOwn(m, k)).map(k => [k, m[k]])))
  }
  stop (reason) {
    const a = this.active
    if (!a || a.stopping) return
    a.stopping = true; a.reason = reason
    clearTimeout(a.duration)
    this.append('session', { type: 'stop_requested', reason })
    this.sendRobot({ type: 'signal_monitor', operation: 'stop', monitor_id: a.id })
    this.publish(this.current())
    a.drain = setTimeout(() => this.finish().catch(error => this.failed(error, a)), 2000); a.drain.unref()
  }
  async finish () {
    const a = this.active
    if (!a || a.finalizing) return
    a.finalizing = true; clearTimeout(a.duration); clearTimeout(a.drain); clearInterval(a.sync)
    await new Promise((resolve, reject) => { a.stream.once('error', reject); a.stream.end(resolve) })
    if (a.diskError) throw new Error(a.diskError)
    const completeness = { phone_started: a.phoneStarted, phone_drained: a.phoneStopped, phone_batch_gaps: a.batchGaps,
      phone_ordinal_gaps: a.ordinalGaps, browser_ordinal_gaps: a.browserGaps, browser_dropped: a.browserDropped, server_dropped: a.server_dropped, end_reason: a.reason || 'phone_stopped', phone_error: a.phoneError || null }
    const file = path.join(this.directory, a.basename + '.json'), md = path.join(this.directory, a.basename + '.md')
    const result = await new Promise((resolve, reject) => {
      const worker = new Worker(path.join(__dirname, 'signal-report-worker.js'), { workerData: { journal: a.journal, id: a.id, file, md,
        started_at: a.started_at, ended_at: new Date().toISOString(), metadata: a.metadata, completeness } })
      let received = false
      worker.once('message', m => { received = true; if (m.error) reject(new Error(m.error)); else resolve(m) })
      worker.once('error', reject)
      worker.once('exit', code => { if (!received) reject(new Error(`Report worker exited without a result (${code})`)) })
    })
    const token = crypto.randomBytes(16).toString('hex'); this.saved.set(token, { json: file, md })
    if (this.saved.size > 10) this.saved.delete(this.saved.keys().next().value)
    this.active = null
    this.last = { type: 'signal_monitor_status', status: 'saved', result: result.status, monitor_id: a.id,
      file, markdown: md, journal: a.journal, records: a.count, summary: { counts: result.counts },
      sha256: result.sha256, download: `/signal-reports/${token}.json`, markdown_download: `/signal-reports/${token}.md` }
    this.publish(this.last)
  }
  failed (error, a) {
    clearInterval(a.sync); clearTimeout(a.duration); clearTimeout(a.drain); if (this.active === a) this.active = null
    this.last = { type: 'signal_monitor_status', status: 'error', error: `Report save failed: ${error.message}. Partial journal: ${a.journal}` }; this.publish(this.last)
  }
  download (route) { const match = /^\/signal-reports\/([a-f0-9]{32})\.(json|md)$/.exec(route); return match && this.saved.get(match[1])?.[match[2]] }
  disconnect (role) { if (this.active) { this.append('session', { type: 'disconnected', role }); this.stop(`${role}_disconnected`) } }
  close () { if (this.active) this.stop('server_shutdown') }
}
module.exports = { SignalMonitoring, redact }
