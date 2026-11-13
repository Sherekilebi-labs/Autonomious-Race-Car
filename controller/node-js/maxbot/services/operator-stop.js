'use strict'
const dgram = require('node:dgram')
const crypto = require('node:crypto')
const { performance } = require('node:perf_hooks')
const sign = (key, message) => crypto.createHmac('sha256', key).update(message).digest('hex')
const valid = (key, message, sig) => typeof sig === 'string' && /^[a-f0-9]{64}$/.test(sig) && crypto.timingSafeEqual(Buffer.from(sign(key, message), 'hex'), Buffer.from(sig, 'hex'))
/** Stop-only capability. Never sends a command, arm, reset, or telemetry registration. */
class OperatorStop {
  constructor ({ key, host = '192.168.4.1', socket = dgram.createSocket('udp4'), clock = () => Math.floor(performance.now()), onStatus = () => {} }) {
    this.key = key; this.roleKey = sign(key, 'operator-stop-key-v1'); this.host = host; this.socket = socket; this.clock = clock; this.onStatus = onStatus; this.boot = ''; this.pending = null
    socket.on('message', (bytes, peer) => this.receive(bytes, peer)); socket.on('error', e => onStatus({ type: 'direct_stop', status: 'unavailable', reason: e.message }))
    this.timer = setInterval(() => { this.hello(); if (this.pending) this.transmit() }, 1000); this.timer.unref()
    this.hello()
  }
  hello () { this.nonce = crypto.randomBytes(16).toString('hex'); this.sender = this.clock(); this.socket.send(Buffer.from(JSON.stringify({ v: 1, type: 'hello', nonce: this.nonce, sender_ms: this.sender, mac: sign(this.key, `H|${this.nonce}`) })), 4242, this.host) }
  receive (bytes, peer) {
    if (peer.address !== this.host || bytes.length > 2048) return
    let m; try { m = JSON.parse(bytes) } catch { return }
    if (m.type === 'hello' && peer.port === 4242 && m.nonce === this.nonce && m.sender_ms === this.sender && this.clock() - this.sender < 2000 && valid(this.key, `H|${m.boot}|${m.time_ms}|${m.nonce}|${m.calibration_id}|${m.rx_ms}|${m.tx_ms}|${m.sender_ms}|${m.owner_epoch}|${m.owner_seq}|${m.firmware_source_id}`, m.mac)) {
      this.boot = m.boot; this.remoteTime = m.time_ms; this.lastHello = this.clock(); this.onStatus({ type: 'direct_stop', status: this.pending ? 'requested' : 'available', boot: this.boot }); return
    }
    if (m.type === 'operator_stop_applied' && peer.port === 4243 && this.pending && m.boot === this.pending.boot && m.request_id === this.pending.request_id && Number.isSafeInteger(m.applied_ms) && valid(this.roleKey, `SA|${m.boot}|${m.request_id}|${m.applied_ms}`, m.mac)) {
      this.pending = null; this.onStatus({ type: 'direct_stop', status: 'applied', applied_ms: String(m.applied_ms), boot: m.boot, request_id: m.request_id })
    }
  }
  request () {
    if (!this.boot || this.clock() - this.lastHello > 5000) { this.hello(); this.onStatus({ type: 'direct_stop', status: 'unavailable', reason: 'No fresh authenticated ESP boot' }); return false }
    const remote = Math.max(0,Math.floor(this.remoteTime+this.clock()-this.lastHello)); const id = remote.toString(16).padStart(16,'0')+crypto.randomBytes(8).toString('hex'); this.pending = { v: 1, type: 'operator_stop', boot: this.boot, request_id: id, mac: sign(this.roleKey, `S|${this.boot}|${id}`) }
    this.transmit(); this.onStatus({ type: 'direct_stop', status: 'requested', request_id: id }); return true
  }
  transmit () { this.socket.send(Buffer.from(JSON.stringify(this.pending)), 4243, this.host) }
  close () { clearInterval(this.timer); this.socket.close() }
}
module.exports = { OperatorStop, sign, valid }
