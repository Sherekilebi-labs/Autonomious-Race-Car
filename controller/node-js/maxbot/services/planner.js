'use strict'
const { spawn } = require('node:child_process')
const fs = require('node:fs/promises')
const path = require('node:path')
const os = require('node:os')
const crypto = require('node:crypto')
const hash = value => crypto.createHash('sha256').update(JSON.stringify(value)).digest('hex')
class Planner {
  constructor ({ root, publish, snapshot }) { this.root = root; this.publish = publish; this.snapshot = snapshot; this.generation = 0 }
  observe () { if(this.token && hash(this.snapshot())!==this.token) this.cancel() }
  cancel () { this.generation++; this.token=null; if (this.child) this.child.kill(); this.child = null }
  async run (request) {
    this.cancel(); const generation = this.generation; const before = this.snapshot(); const token = hash(before); this.token=token
    if (!before || !before.stopped || request.schemaVersion !== 1 || !request.stopped) throw new Error('Stopped phone snapshot required')
    if(request.course?.mapEpoch!==before.mapEpoch||request.calibration?.firmwareCalibrationId!==before.calibrationId||request.vehicleModelId!==before.vehicleModelId)throw new Error('Planner request dependencies do not match phone snapshot')
    const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'maxbot-plan-')); const input = path.join(dir, 'request.json'); await fs.writeFile(input, JSON.stringify(request), { mode: 0o600 })
    try {
      if(generation!==this.generation)throw new Error("Planner snapshot invalidated")
      const result = await new Promise((resolve, reject) => {
        const child = spawn(process.env.MAXBOT_PYTHON || 'python3', [path.join(this.root, 'tools/maxbot/planner.py'), input], { stdio: ['ignore', 'pipe', 'pipe'] }); this.child = child
        let stdout = '', stderr = ''; const timer = setTimeout(() => { child.kill(); reject(new Error('Planner time limit')) }, 120000)
        child.stdout.on('data', b => { stdout += b; if (stdout.length > 4 * 1024 * 1024) { child.kill(); reject(new Error('Planner output bound')) } }); child.stderr.on('data', b => { if (stderr.length < 4096) stderr += b })
        child.on('error', reject); child.on('close', () => { clearTimeout(timer); try { resolve(JSON.parse(stdout)) } catch { reject(new Error(stderr || 'Planner failed')) } })
      })
      if (generation !== this.generation || hash(this.snapshot()) !== token || !this.snapshot()?.stopped) return this.publish({ type: 'planning', status: 'discarded', reason: 'Stopped state or dependencies changed' })
      this.publish({ type: 'planning', status: result.ok ? 'complete' : 'failed', result }); return result
    } finally { if (generation === this.generation) this.child = null; await fs.rm(dir, { recursive: true, force: true }) }
  }
}
module.exports = { Planner }
