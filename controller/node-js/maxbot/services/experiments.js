'use strict'
const fs = require('node:fs/promises'), path = require('node:path'), crypto = require('node:crypto')
const { Observer } = require('./observer')
const step = (duration_ms, pwm = 0, steering_deg = 0) => ({ duration_ms, pwm, steering_deg })
const RECIPES = Object.freeze({
  camera_clock: { title: 'Stationary camera clock validation', steps: [step(10000)], motion: false },
  warning: { title: 'Known-object warning entries and exits', steps: [step(30000)], motion: false },
  video: { title: 'Stationary video delay recording', steps: [step(20000)], motion: false },
  forward_onset: { title: 'One forward start; reposition before repeating', steps: [step(3000), step(500, 140), step(4000)], motion: true },
  reverse_onset: { title: 'One reverse start; reposition before repeating', steps: [step(3000), step(500, -140), step(4000)], motion: true },
  steering: { title: 'Loaded steering, zero traction', steps: Array.from({ length: 5 }, () => [step(1000), step(1000, 0, 10), step(1000), step(1000, 0, -10)]).flat().concat([step(2000)]), motion: true },
  speed_stop: { title: 'One bounded forward pulse and coast measurement', steps: [step(3000), step(1000, 140), step(6000)], motion: true },
  slalom: { title: 'One validated cone-slalom run (60 s limit)', steps: [step(30000),step(30000)], motion: true, autonomous: true },
  turn: { title: 'One low-speed steering response', steps: [step(3000), step(1000, 130, 8), step(6000)], motion: true }
})
// Overrides may only shorten powered segments; never extend a recipe's motion budget.
const boundedSteps = (recipe, parameters) => recipe.steps.map(s => ({ ...s,
  duration_ms: s.pwm && parameters.pulse_ms != null ? Math.min(s.duration_ms, parameters.pulse_ms) : s.duration_ms,
  pwm: s.pwm ? Math.sign(s.pwm) * Math.min(Math.abs(s.pwm), parameters.pwm) : 0
}))
const stopped = t => t && !t.armed && !['RUN', 'SCOUT', 'MANUAL'].includes(t.mode) && t.pwm === 0
const hash = data => crypto.createHash('sha256').update(data).digest('hex')
class Experiments {
  constructor ({ hub, signals, directory, root }) {
    this.hub = hub; this.signals = signals; this.directory = directory; this.root = root; this.active = null; this.prepared = null; this.attendance = null; this.last = { status: 'idle' }; this.ordinal = 0; this.closed = false
    this.observer = new Observer({ send: m => hub.send(hub.observer, m), publish: m => this.publish(m) })
  }
  observerConnected () { this.observer = new Observer({ send: m => this.hub.send(this.hub.observer, m), publish: m => this.publish(m) }) }
  publish (m) { this.hub.send(this.hub.operator, m) }
  current () { return { type: 'experiment_status', ...this.last, active: Boolean(this.active), prepared: this.prepared, attended: this.present(), observer: this.hub.observer ? this.observer.status : null, recipes: Object.entries(RECIPES).map(([id, r]) => ({ id, title: r.title, motion: r.motion, duration_ms: r.steps.reduce((n, s) => n + s.duration_ms, 0) })) } }
  present () { const p = this.hub.peers.get(this.hub.operator); return Boolean(this.attendance && this.attendance.peer === this.hub.operator && Date.now() < this.attendance.until && p?.authenticated && Date.now() - p.heartbeat < 350) }
  attend (peer, options) {
    if (peer !== this.hub.operator) throw new Error('Dashboard operator required')
    if (options.enabled !== true) { this.attendance = null; this.abort('attendance_disabled'); return this.current() }
    if (options.usb_power_verified !== true || options.area_clear !== true || options.observer_framed !== true) throw new Error('Confirm USB power check, clear floor, and observer framing')
    this.attendance = { peer, until: Date.now() + 10 * 60 * 1000, confirmations: { usb_power_verified: true, area_clear: true, observer_framed: true }, at: new Date().toISOString() }
    return this.current()
  }
  prepare (recipe, metadata = {}, parameters = {}) {
    if (!RECIPES[recipe]) throw new Error('Unknown experiment recipe')
    if (this.active) throw new Error('An experiment is active')
    const allow_severe_thermal=parameters.allow_severe_thermal ?? false
    if(typeof allow_severe_thermal!=='boolean')throw new Error('Boolean thermal allowance required')
    if(allow_severe_thermal&&RECIPES[recipe].autonomous)throw new Error('Thermal allowance is limited to timing experiments; slalom excluded')
    const pwm=parameters.pwm ?? (allow_severe_thermal?110:140)
    if(!Number.isInteger(pwm)||pwm<1||pwm>140)throw new Error('Trial PWM must be 1–140')
    const pulse_ms=parameters.pulse_ms ?? (allow_severe_thermal?100:null)
    if(pulse_ms!==null&&(!Number.isInteger(pulse_ms)||pulse_ms<20||pulse_ms>1000))throw new Error('Trial pulse must be 20–1000 ms')
    if(allow_severe_thermal&&(pwm>110||pulse_ms>100))throw new Error('Thermal trial limited to PWM 110 and 100 ms pulses')
    this.prepared = { parameters:{pwm,pulse_ms,allow_severe_thermal}, id: crypto.randomUUID(), recipe, title: RECIPES[recipe].title, motion: RECIPES[recipe].motion, prepared_ms: Date.now(), metadata: { notes: String(metadata.notes || '').slice(0, 2000), battery_v: typeof metadata.battery_v === 'number' && Number.isFinite(metadata.battery_v) && metadata.battery_v > 0 && metadata.battery_v < 20 ? metadata.battery_v : null } }
    this.last = { status: 'prepared' }; this.publish(this.current()); return this.current()
  }
  preflight () {
    if(this.closed)throw new Error('Dashboard is shutting down')
    const t = this.hub.lastTelemetry
    if (!this.present()) throw new Error('Keep the attended dashboard session visible and enabled')
    if (!t || Date.now() - t.received_ms > 350 || !stopped(t) || !t.vehicle_connected || t.vehicle_transport !== 'usb' || t.experiment_protocol !== 1 || t.fault && !/stop|disarm|neutral/i.test(t.fault) || t.mcu_fault) throw new Error('Fresh stopped/disarmed USB Pixel with experiment protocol required')
    if(!this.hub.peers.get(this.hub.robot)?.signed||!this.hub.peers.get(this.hub.observer)?.signed)throw new Error('Updated signed Pixel and observer apps required')
    if (!this.hub.observer || !this.observer.status || Date.now() - this.observer.status.received_ms > 1500 || !this.observer.status.camera_ready) throw new Error('Connect the observer phone and open its camera')
    if (this.signals.active) throw new Error('Stop the other signal recording first')
    if (this.active) throw new Error('Experiment already active')
    return t
  }
  async start (preparedId) {
    if (!this.prepared || this.prepared.id !== preparedId || Date.now() - this.prepared.prepared_ms > 300000) throw new Error('Prepare a fresh experiment first')
    const telemetry = this.preflight(), p = this.prepared, base = RECIPES[p.recipe], recipe={...base,steps:boundedSteps(base,p.parameters)}
    if(p.parameters.allow_severe_thermal&&telemetry.experiment_thermal_policy!=='severe_bounded_v1')throw new Error('Install MaxBot 0.7.3-timing on Pixel 5 for bounded thermal trials')
    if(typeof telemetry.thermal_status==='number'&&telemetry.thermal_status>=(p.parameters.allow_severe_thermal?4:3))throw new Error('Phone thermal status exceeds this trial policy')
    if(recipe.autonomous&&(!telemetry.calibrated||telemetry.mode!=='READY'||!telemetry.vehicle_model_id||!telemetry.config_id))throw new Error('Validated cone model/dynamics, matching reference and verified READY launch pose required')
    const dir = path.join(this.directory, p.id); await fs.mkdir(dir, { recursive: false, mode: 0o700 }).catch(async e => { if (e.code === 'ENOENT') { await fs.mkdir(this.directory, { recursive: true, mode: 0o700 }); await fs.mkdir(dir, { mode: 0o700 }) } else throw e })
    // Recheck after disk work; no asynchronous preparation can outlive a takeover.
    if (this.prepared !== p) throw new Error('Preparation cancelled')
    this.preflight()
    const a = this.active = { id: p.id, dir, recipe: p.recipe, status: 'preparing', created_ms: Date.now(), events: [], metadata: p.metadata, manifest: { schemaVersion: 1, id: p.id, recipe: p.recipe, created_at: new Date().toISOString(), metadata: p.metadata, parameters: p.parameters, effective_steps: recipe.steps, attendance: { at: this.attendance.at, confirmations: this.attendance.confirmations }, initial_telemetry: telemetry, limits: { max_pwm: p.parameters.allow_severe_thermal?110:140, steering_deg: p.parameters.allow_severe_thermal?10:recipe.autonomous?26:12, powered_total_ms: p.parameters.allow_severe_thermal?100:recipe.autonomous?60000:2000, trial_ms: 60000, lease_ms: 300 }, device_validation: 'pending' } }
    a.observer=this.observer; this.prepared = null; this.event({ type: 'preparing' }); this.observer.bind(a.id, path.join(dir, 'observer'))
    try {
      a.manifest.course_profile=JSON.parse(await fs.readFile(path.join(this.root,'maxbot/config/profiles/course-indoor-corridor.v1.json'),'utf8'))
      a.manifest.source_sha256 = {}
      for (const file of ['controller/node-js/maxbot/services/experiments.js', 'robot/android/maxbot/src/main/java/org/maxbot/app/ExperimentRunner.java', 'robot/android/maxbot-core/src/main/java/org/maxbot/core/ExperimentSequence.java', 'robot/android/maxbot/build.gradle']) a.manifest.source_sha256[file] = hash(await fs.readFile(path.join(this.root, file)))
      await fs.writeFile(path.join(dir, 'manifest.json'), JSON.stringify(a.manifest, null, 2), { mode: 0o600 })
      if (this.active !== a || a.finishing) return this.current()
      await this.signals.request({ operation: 'start', options: { notes: `Experiment ${a.id}: ${p.title}. ${p.metadata.notes}` } })
      if (!this.signals.active) throw new Error('Signal collector failed to start')
      a.monitor_id = this.signals.active.id
      await this.waitFor(() => this.signals.active?.phoneStarted, a, 5000)
      await this.observer.command('start', { requested_fps: p.recipe.endsWith('onset') ? 120 : 60, duration_ms: recipe.steps.reduce((n, s) => n + s.duration_ms, 0) + 4000 })
      if (this.active !== a || a.finishing) return this.current()
      a.status = 'running'; a.started_ms = Date.now(); a.hard_end_ms = a.started_ms + recipe.steps.reduce((n, s) => n + s.duration_ms, 0) + 2500; a.last_lease_ms = 0
      this.sendTrial('start', { steps: recipe.steps, recipe: a.recipe, allow_severe_thermal: p.parameters.allow_severe_thermal }); this.event({ type: 'start_sent' }); return this.current()
    } catch (e) { if (this.active === a && !a.finishing) await this.finish('aborted', e.message); throw e }
  }
  waitFor (condition, a, timeout) { return new Promise((resolve, reject) => { const start = Date.now(); const poll = () => { if (this.active !== a || a.finishing) return reject(new Error('Experiment cancelled')); if (condition()) return resolve(); if (Date.now() - start >= timeout) return reject(new Error('Experiment preparation timed out')); setTimeout(poll, 40) }; poll() }) }
  sendTrial (operation, values = {}) { const a = this.active; if (a) this.hub.send(this.hub.robot, { type: 'experiment', operation, experiment_id: a.id, ordinal: ++this.ordinal, deadline_ms: Date.now() + 300, ...values }) }
  event (e) { const a = this.active; if (!a) return; const event = { ...e, server_mono_ns: process.hrtime.bigint().toString(), server_ms: Date.now() }; if (a.events.length < 10000) a.events.push(event); this.signals.append('experiment', event); this.last = { status: a.status, experiment_id: a.id, recipe: a.recipe }; this.publish(this.current()) }
  phone (m) { if (!this.active || m.experiment_id !== this.active.id || this.active.finishing) return; this.event(m); if (['completed', 'aborted', 'rejected'].includes(m.status)) this.finish(m.status === 'completed' ? 'completed' : 'aborted', m.reason).catch(e => this.error(e)) }
  tick () {
    const a = this.active; if (!a || a.finishing) return
    const t = this.hub.lastTelemetry
    if (!this.present() || !this.hub.robot || !this.hub.observer || !t || Date.now() - t.received_ms > 350 || !t.vehicle_connected || t.mcu_fault || this.observer.uploadError) return this.abort('presence_or_device_lost')
    if (a.status === 'preparing' && Date.now() - a.created_ms > 23000) return this.abort('preparation_timeout')
    if (a.status === 'running') {
      if (Date.now() > a.hard_end_ms) return this.abort('hard_duration_limit')
      const o = this.observer.status
      if ((!o || Date.now() - o.received_ms > 1500 || !o.recording || o.capture_age_ms == null || o.capture_age_ms > 1500) && Date.now()-a.started_ms>1200) return this.abort('observer_recording_lost')
      if (Date.now() - a.last_lease_ms >= 100) { this.sendTrial('lease'); a.last_lease_ms = Date.now() }
    }
  }
  abort (reason = 'operator_abort') { this.prepared = null; if (this.active && !this.active.finishing) return this.finish('aborted', reason).catch(e => this.error(e)); return Promise.resolve(this.current()) }
  pixelExportResult(result){const a=this.active;if(a?.exportWaiting){a.exportWaiting=false;clearTimeout(a.exportTimer);a.exportResolve(result)}}
  async exportPixel(a){
    const until=Date.now()+3000
    while(this.hub.robot&&Date.now()<until&&!stopped(this.hub.lastTelemetry))await new Promise(resolve=>setTimeout(resolve,50))
    if(!this.hub.robot||!stopped(this.hub.lastTelemetry))return {error:'Stopped phone unavailable for training export'}
    return new Promise(resolve=>{a.exportWaiting=true;a.exportResolve=resolve;a.exportTimer=setTimeout(()=>this.pixelExportResult({error:'Pixel training export timeout'}),120000);this.hub.send(this.hub.robot,{type:'command',action:'export'})})
  }
  async finish (status, reason = '') {
    const a = this.active; if (!a || a.finishing) return this.current(); a.finishing = true
    this.sendTrial('abort'); this.hub.send(this.hub.robot, { type: 'command', action: 'stop' })
    a.status = 'saving'; this.event({ type: 'stop', outcome: status, reason }); await this.signals.request({operation:'stop',reason:`experiment_${status}`})
    let observerError = null
    try { await a.observer.command('stop'); await a.observer.command('upload'); await a.observer.queue } catch (e) { observerError = e.message }
    const waitStart = Date.now(); while (this.signals.active && Date.now() - waitStart < 15000) await new Promise(resolve => setTimeout(resolve, 100))
    const pixelExport=['slalom','warning'].includes(a.recipe)?await this.exportPixel(a):null
    const s = this.signals.last; const files = {}
    if (s.monitor_id === a.monitor_id && s.status === 'saved') for (const [key, file] of Object.entries({ json: s.file, md: s.markdown, jsonl: s.journal })) { const dest = path.join(a.dir, `signals.${key}`); await fs.copyFile(file, dest); files[key] = path.basename(dest) }
    const complete = Boolean(s.result==='complete' && files.json && a.observer.uploaded.some(f => f.name === 'video.mp4') && a.observer.uploaded.some(f => f.name === 'frames.jsonl') && !observerError && !a.observer.uploadError && (!pixelExport||!pixelExport.error))
    const summary = { schemaVersion: 1, id: a.id, recipe: a.recipe, outcome: status, reason, complete, observer_error: observerError || a.observer.uploadError, observer_files: a.observer.uploaded, pixel_export:pixelExport, signal_files: files, physically_validated: false, needs_review: true, ended_at: new Date().toISOString() }
    await fs.writeFile(path.join(a.dir, 'events.jsonl'), a.events.map(e => JSON.stringify(e)).join('\n') + '\n', { mode: 0o600 })
    await fs.writeFile(path.join(a.dir, 'summary.json'), JSON.stringify(summary, null, 2), { mode: 0o600 })
    await fs.writeFile(path.join(a.dir, 'report.md'), `# MaxBot experiment ${a.id}\n\n${RECIPES[a.recipe].title}\n\nOutcome: **${status}**. Evidence bundle: **${complete ? 'collected; review required' : 'incomplete'}**.\n\n${reason || ''}\n\nBattery: ${a.metadata.battery_v == null ? 'not measured' : a.metadata.battery_v + ' V (operator measured)'}.\n\nSee [signal calculations](signals.md), [summary](summary.json), [events](events.jsonl), and observer recordings.\n\n## Physical measurements\n\nWheel-motion onset requires synchronized visible-witness review or validated observer extraction. PWM application is not physical motor motion. No physical latency or contact-free verdict is assumed. Run tools/maxbot/experiment_analysis.py for timestamped observer tracks and reviewed witness intervals.\n`, { mode: 0o600 })
    this.active = null; this.last = { status: 'saved', experiment_id: a.id, directory: a.dir, summary }; this.publish(this.current()); if(this.onSaved)this.onSaved(a.id);return this.current()
  }
  error (e) { const a = this.active; if (a) { this.sendTrial('abort'); this.hub.send(this.hub.robot, { type: 'command', action: 'stop' }); this.active = null }; this.last = { status: 'error', error: e.message, partial_directory: a?.dir }; this.publish(this.current()) }
  async list () { await fs.mkdir(this.directory, { recursive: true, mode: 0o700 }); return (await fs.readdir(this.directory)).filter(x => /^[a-f0-9-]{36}$/.test(x)).sort() }
  async read (id, name = 'summary.json') { if (!/^[a-f0-9-]{36}$/.test(id) || !['summary.json', 'manifest.json', 'report.md', 'analysis.json', 'analysis.md'].includes(name)) throw new Error('Unknown experiment artifact'); return fs.readFile(path.join(this.directory, id, name), 'utf8') }
  async close () { this.closed = true;this.pixelExportResult({error:'Server shutdown during export'}); await this.abort('server_shutdown'); await this.observer.disconnect() }
}
module.exports = { Experiments, RECIPES, stopped, boundedSteps }
