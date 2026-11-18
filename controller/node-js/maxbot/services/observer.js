'use strict'
const fs = require('node:fs/promises'), path = require('node:path'), crypto = require('node:crypto')
const NAMES = new Set(['video.mp4', 'frames.jsonl', 'samples.jsonl', 'observer.json', 'preview.jpg'])
/** Observer uploads are session-bound, ordered, bounded and acknowledged after disk writes. */
class Observer {
  constructor ({ send, publish = () => {} }) { this.send = send; this.publish = publish; this.status = null; this.pending = new Map(); this.queue = Promise.resolve(); this.closed = false }
  bind (id, directory) { if (this.file) throw new Error('Observer upload in progress'); this.id = id; this.directory = directory; this.uploaded = []; this.bytes = 0; this.uploadError = null }
  command (operation, values = {}) {
    if (this.closed) return Promise.reject(new Error('Observer disconnected'))
    const request_id = crypto.randomUUID()
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => { this.pending.delete(request_id); reject(new Error(`Observer ${operation} timed out`)) }, operation === 'upload' ? 120000 : 15000)
      this.pending.set(request_id, { resolve, reject, timer }); this.send({ type: 'observer_command', operation, request_id, experiment_id: this.id, ...values })
    })
  }
  receive (m) {
    this.queue = this.queue.then(async () => {
      if (m.type === 'observer_clock_probe' && typeof m.t1==='string' && /^[0-9]{1,20}$/.test(m.t1)) { const t2=process.hrtime.bigint().toString();this.send({type:'observer_clock',t1:m.t1,t2,t3:process.hrtime.bigint().toString(),server_wall_ms:Date.now()});return }
      if (m.type === 'observer_status') { this.status = { ...m, received_ms: Date.now() }; this.publish({ type: 'observer_status', ...this.status }); return }
      if (m.type === 'observer_result') {
        const p = this.pending.get(m.request_id)
        if (p) { clearTimeout(p.timer); this.pending.delete(m.request_id); m.error ? p.reject(new Error(m.error)) : p.resolve(m) }
        return
      }
      if (m.type === 'observer_frame' && typeof m.jpeg === 'string' && m.jpeg.length < 60000) { this.preview = m; return }
      if (m.type !== 'observer_upload' || m.experiment_id !== this.id || !this.directory) throw new Error('Observer session mismatch')
      if (m.operation === 'begin') {
        if (this.file || !NAMES.has(m.name) || this.uploaded.some(f => f.name === m.name) || !Number.isSafeInteger(m.bytes) || m.bytes < 1 || this.bytes + m.bytes > 512 * 1024 * 1024 || !/^[a-f0-9]{64}$/.test(m.sha256)) throw new Error('Invalid observer file')
        await fs.mkdir(this.directory, { recursive: true }); const handle = await fs.open(path.join(this.directory, m.name), 'wx', 0o600)
        this.file = { handle, name: m.name, expected: m.bytes, sha256: m.sha256, offset: 0, hash: crypto.createHash('sha256') }
      } else if (m.operation === 'chunk') {
        const f = this.file
        if (!f || m.offset !== f.offset || typeof m.data !== 'string' || m.data.length > 44000 || !/^[A-Za-z0-9+/]*={0,2}$/.test(m.data)) throw new Error('Invalid observer chunk')
        const b = Buffer.from(m.data, 'base64'); if (!b.length || b.length > 32768 || f.offset + b.length > f.expected) throw new Error('Observer size mismatch')
        let offset = 0; while (offset < b.length) { const result = await f.handle.write(b, offset, b.length - offset); if (!result.bytesWritten) throw new Error('Observer disk write stalled'); offset += result.bytesWritten }
        f.hash.update(b); f.offset += b.length
      } else if (m.operation === 'end') {
        const f = this.file
        if (!f || f.offset !== f.expected || f.hash.digest('hex') !== f.sha256) throw new Error('Observer hash/length mismatch')
        await f.handle.sync(); await f.handle.close(); this.uploaded.push({ name: f.name, bytes: f.expected, sha256: f.sha256 }); this.bytes += f.expected; this.file = null
      } else throw new Error('Unknown observer upload operation')
      this.send({ type: 'observer_upload_ack', request_id: m.request_id, experiment_id: this.id, offset: this.file?.offset || 0 })
    }).catch(async error => {
      this.uploadError = error.message
      if (this.file) { await this.file.handle.close().catch(() => {}); this.file = null }
      this.send({ type: 'observer_upload_ack', request_id: m.request_id, error: error.message }); this.publish({ type: 'observer_status', error: error.message })
    })
    return this.queue
  }
  async disconnect () { this.closed = true; for (const p of this.pending.values()) { clearTimeout(p.timer); p.reject(new Error('Observer disconnected')) }; this.pending.clear(); await this.queue; if (this.file) { await this.file.handle.close().catch(() => {}); this.file = null } }
}
module.exports = { Observer }
