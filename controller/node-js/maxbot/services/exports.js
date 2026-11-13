'use strict'
const fs = require('node:fs/promises'), path = require('node:path'), crypto = require('node:crypto')
/** Sequential, bounded Wi-Fi export onto the Mac. Interrupted files remain visibly .part. */
class Exports {
  constructor (directory) { this.directory = directory; this.active = null }
  async receive (m) {
    if (!/^[a-f0-9]{32}$/.test(m.id)) throw new Error('Invalid export ID')
    if (m.operation === 'begin') {
      if (this.active || !Number.isSafeInteger(m.size) || m.size < 1 || m.size > 1024 * 1024 * 1024 || !/^[a-f0-9]{64}$/.test(m.sha256)) throw new Error('Export size/hash or active transfer')
      await fs.mkdir(this.directory, { recursive: true }); const partial = path.join(this.directory, `${m.id}.zip.part`)
      const handle = await fs.open(partial, 'wx', 0o600); this.active = { id: m.id, handle, partial, size: m.size, hash: m.sha256, offset: 0, digest: crypto.createHash('sha256') }; return { status: 'receiving' }
    }
    const a = this.active; if (!a || a.id !== m.id) throw new Error('Unknown export')
    if (m.operation === 'chunk') {
      if (m.offset !== a.offset || typeof m.data !== 'string' || m.data.length > 44000 || !/^[A-Za-z0-9+/]*={0,2}$/.test(m.data)) throw new Error('Export chunk offset/encoding')
      const bytes = Buffer.from(m.data, 'base64'); if (a.offset + bytes.length > a.size) throw new Error('Export size exceeded')
      await a.handle.write(bytes, 0, bytes.length, a.offset); a.digest.update(bytes); a.offset += bytes.length; return { status: 'receiving', offset: a.offset }
    }
    if (m.operation !== 'end' || a.offset !== a.size || a.digest.digest('hex') !== a.hash) throw new Error('Incomplete/corrupt export')
    await a.handle.sync(); await a.handle.close(); const target = a.partial.slice(0, -5); await fs.rename(a.partial, target); this.active = null; return { status: 'saved', path: target }
  }
  async close () { if (this.active) { await this.active.handle.close(); this.active = null } }
}
module.exports = { Exports }
