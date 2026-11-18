'use strict'
const fs = require('node:fs/promises'), path = require('node:path'), crypto = require('node:crypto'), { spawn } = require('node:child_process')
class AutomationJobs {
  constructor ({ root, experiments, snapshot }) { this.root = root; this.experiments = experiments; this.snapshot = snapshot; this.jobs = new Map(); this.child = null }
  current () { return [...this.jobs.values()].map(({ id, operation, status, directory }) => ({ id, operation, status, directory })) }
  stopped () { const t = this.snapshot(); return !this.experiments.active && (!t || (Date.now() - t.received_ms < 600 && !t.armed && !['RUN', 'SCOUT', 'MANUAL'].includes(t.mode))) }
  async start (operation, args) {
    if (this.child || !this.stopped()) throw new Error('Stop the car and the other background job first')
    const id = crypto.randomUUID(), directory = path.join(this.root, 'runs/automation-jobs', id); await fs.mkdir(directory, { recursive: true, mode: 0o700 })
    let commandArgs
    if (operation === 'analyse') {
      if (!/^[a-f0-9-]{36}$/.test(args.id)) throw new Error('Invalid experiment ID')
      commandArgs = [path.join(this.root, 'tools/maxbot/experiment_analysis.py'), path.join(this.experiments.directory, args.id)]
      if (args.calibration) { const file = path.join(directory, 'observer-calibration.json'); await fs.writeFile(file, JSON.stringify(args.calibration), { mode: 0o600 }); commandArgs.push('--calibration', file) }
    } else {
      if (typeof args.dataset !== 'string') throw new Error('Dataset path required')
      const allowed = await fs.realpath(path.join(this.root, 'runs/datasets')), dataset = await fs.realpath(path.resolve(this.root, args.dataset))
      if (!dataset.startsWith(allowed + path.sep)) throw new Error('Dataset must be inside runs/datasets')
      if (operation === 'policy_search') {
        const count = args.candidates ?? 40; if (!Number.isInteger(count) || count < 1 || count > 100 || !Number.isSafeInteger(args.seed ?? 0)) throw new Error('Invalid candidate count or seed')
        commandArgs = [path.join(this.root, 'tools/maxbot/policy_search.py'), dataset, '--output', path.join(directory, 'result.json'), '--seed', String(args.seed ?? 0), '--candidates', String(count)]
      } else if (operation === 'train_detector') {
        const epochs = args.epochs ?? 100; if (!Number.isInteger(epochs) || epochs < 1 || epochs > 100) throw new Error('Epochs must be 1–100')
        commandArgs = [path.join(this.root, 'tools/maxbot/train.py'), '--dataset', dataset, '--output', path.join(directory, 'model'), '--epochs', String(epochs)]
      } else throw new Error('Unknown job')
    }
    if (!this.stopped() || this.child) throw new Error('Stopped snapshot changed')
    const job = { id, operation, status: 'running', directory, log: '', started_at: new Date().toISOString() }; this.jobs.set(id, job)
    const localPython=path.join(this.root,'.tools/yolo-export/bin/python')
    const child = spawn(process.env.MAXBOT_PYTHON || (require('node:fs').existsSync(localPython)?localPython:'python3'), commandArgs, { cwd: this.root, stdio: ['ignore', 'pipe', 'pipe'],detached:process.platform!=='win32' }); this.child = child
    this.terminate=()=>{try{if(process.platform==='win32')child.kill();else process.kill(-child.pid,'SIGTERM')}catch{child.kill()}}
    const log = b => { if (job.log.length < 256 * 1024) job.log += b.toString().slice(0, 256 * 1024 - job.log.length) }
    child.stdout.on('data', log); child.stderr.on('data', log)
    const timer = setTimeout(() => { job.reason = 'job_time_limit'; this.terminate() }, operation === 'train_detector' ? 24 * 3600000 : 120000); timer.unref()
    const finish = async (code, error) => { clearTimeout(timer); if (this.child === child) this.child = null; job.status = code === 0 && !job.reason && !error ? 'complete' : 'failed'; job.error = error?.message || job.reason || (code ? `Process exited ${code}` : null); job.ended_at = new Date().toISOString(); await fs.writeFile(path.join(directory, 'job.json'), JSON.stringify(job, null, 2), { mode: 0o600 }).catch(() => {}) }
    child.once('error', e => finish(-1, e)); child.once('close', code => { if (job.status === 'running') finish(code) }); return { id, status: job.status, directory }
  }
  observe () { if (this.child && !this.stopped()) { const job = [...this.jobs.values()].find(j => j.status === 'running'); if (job) job.reason = 'vehicle_state_changed'; this.terminate() } }
  async read (id) { if (!/^[a-f0-9-]{36}$/.test(id)) throw new Error('Invalid job ID'); if (this.jobs.has(id)) return this.jobs.get(id); return JSON.parse(await fs.readFile(path.join(this.root, 'runs/automation-jobs', id, 'job.json'), 'utf8')) }
  close () { if (this.child) { const job = [...this.jobs.values()].find(j => j.status === 'running'); if (job) job.reason = 'server_shutdown'; this.terminate() } }
}
module.exports = { AutomationJobs }
