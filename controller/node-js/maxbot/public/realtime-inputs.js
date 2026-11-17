'use strict'

// Deadline-bearing inputs may be lost; they must never queue behind stale inputs.
// Arming, stops, pairing and heartbeats continue over the reliable connection.
class RealtimeInputs {
  constructor ({ session, sign, fallback, now = () => performance.now() }) {
    this.session = session; this.sign = sign; this.fallback = fallback; this.now = now
    this.channel = null; this.ready = false; this.response = -Infinity
    this.pending = new Map(); this.sent = 0; this.fallbacks = 0; this.rtt = null
  }
  bind (channel) {
    this.close(); this.channel = channel
    channel.onmessage = event => this.receive(event.data, channel)
    channel.onclose = channel.onerror = () => { if (this.channel === channel) this.ready = false }
    this.timer = setInterval(() => this.ping(), 100)
  }
  raw (message) {
    if (this.channel?.readyState !== 'open' || this.channel.bufferedAmount > 1024) return false
    try { this.channel.send(JSON.stringify(message)); return true } catch { return false }
  }
  async receive (data, channel) {
    if (this.channel !== channel || typeof data !== 'string' || data.length > 4096) return
    try {
      const m = JSON.parse(data)
      if (m.type === 'control_challenge' && m.session_id === this.session && /^[a-f0-9]{32}$/.test(m.nonce)) {
        this.nonce = m.nonce
        const signature = await this.sign(`RTC|${this.session}|${m.nonce}`)
        if (this.channel === channel) this.raw({ type: 'control_auth', session_id: this.session, mac: signature })
      } else if (m.type === 'control_ready' && m.session_id === this.session && m.nonce === this.nonce) {
        this.ready = true; this.response = this.now()
      } else if (this.ready && m.type === 'control_clock' && m.session_id === this.session && Number.isSafeInteger(m.t1)) {
        const t2 = Date.now()
        this.raw({ type: 'clock_sample', session_id: this.session, t1: m.t1, t2, t3: Date.now() })
      } else if (this.ready && m.type === 'control_ack' && this.pending.has(m.event_id)) {
        this.rtt = Math.max(0, this.now() - this.pending.get(m.event_id)); this.response = this.now()
        for (const id of this.pending.keys()) if (id <= m.event_id) this.pending.delete(id)
      } else if (this.ready && m.type === 'control_pong' && m.id === this.pingId) {
        this.response = this.now(); this.rtt = Math.max(0, this.response - m.id)
      }
    } catch { /* Invalid channel messages do not grant control. */ }
  }
  ping () {
    if (this.ready) { this.pingId = this.now(); this.raw({ type: 'control_ping', session_id: this.session, id: this.pingId }) }
  }
  healthy () {
    const now = this.now()
    for (const [id, stamp] of this.pending) if (now - stamp > 300) this.pending.delete(id)
    return this.ready && this.channel?.readyState === 'open' && this.channel.bufferedAmount <= 1024 &&
      now - this.response < 200 && [...this.pending.values()].every(stamp => now - stamp < 120)
  }
  send (packet) {
    const direct = this.healthy() && this.raw(packet)
    if (direct) { this.pending.set(packet.event_id, this.now()); this.sent++ }
    if (!direct || !packet.deadman) { this.fallback(packet); this.fallbacks++ }
    return direct
  }
  stats () { return { transport: this.healthy() ? 'Direct WebRTC' : 'WebSocket fallback', round_trip_ms: this.rtt, direct_inputs: this.sent, fallback_inputs: this.fallbacks, buffered_bytes: this.channel?.bufferedAmount || 0 } }
  close () {
    clearInterval(this.timer); this.ready = false; this.pending.clear(); this.nonce = null
    if (this.channel) { this.channel.onmessage = this.channel.onclose = this.channel.onerror = null; this.channel.close() }
    this.channel = null
  }
}
if (typeof module !== 'undefined') module.exports = { RealtimeInputs }
