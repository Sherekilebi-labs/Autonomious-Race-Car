'use strict'
const dgram = require('node:dgram')
const crypto = require('node:crypto')
const FIELDS = ['kp_milli', 'ki_milli', 'kd_milli', 'deadband_mdeg', 'min_pwm', 'max_pwm', 'filter_millihz']
const TRACE = ['drive_pwm', 'target_mdeg', 'actual_mdeg', 'steering_pwm', 'p_milli', 'i_milli', 'd_milli', 'pot_adc']
const sign = (key, text) => crypto.createHmac('sha256', key).update(text).digest('hex')
const requestText = m => ['ST', m.boot, m.request_id, m.operation, m.issued_ms, m.expires_ms, ...FIELDS.map(k => m[k])].join('|')
const responseText = m => ['STA', m.boot, m.request_id, m.status, m.time_ms, ...FIELDS.map(k => m[k]), Number(m.saved), Number(m.armed), ...TRACE.map(k => m[k]), ...(m.diagnostics_version === 2 ? [2, Number(m.pid_active), Number(m.feedback_valid), m.feedback_min_mdeg, m.feedback_max_mdeg, m.feedback_rejects, m.command_min_mdeg, m.command_max_mdeg] : [])].join('|')
function validSettings (m) {
  return m && FIELDS.every(k => Number.isSafeInteger(m[k])) && m.kp_milli > 0 && m.kp_milli <= 500000 && m.ki_milli >= 0 && m.ki_milli <= 200000 &&
    m.kd_milli >= 0 && m.kd_milli <= 20000 && m.deadband_mdeg >= 100 && m.deadband_mdeg <= 5000 && m.min_pwm >= 0 &&
    m.min_pwm <= m.max_pwm && m.max_pwm >= 1 && m.max_pwm <= 255 && m.filter_millihz >= 1000 && m.filter_millihz <= 50000
}
/** Mac-to-ESP settings only. No arm/drive operations; replies require the pairing key. */
class SteeringTuning {
  constructor ({ key, host = '192.168.4.1', port = 4245, clock = Date.now }) {
    this.key = key; this.host = host; this.port = port; this.clock = clock; this.pending = null; this.closed = false
    this.socket = dgram.createSocket('udp4'); this.socket.unref()
    this.socket.on('error', error => this.pending?.finish(error))
    this.socket.on('message', (bytes, remote) => {
      const pending = this.pending
      if (!pending || remote.address !== this.host || remote.port !== this.port || bytes.length > 2048) return
      try {
        const m = JSON.parse(bytes)
        if (m.v !== 1 || m.type !== 'steering_tuning_reply' || m.request_id !== pending.id || !/^[a-f0-9]{1,16}$/.test(m.boot) ||
            !validSettings(m) || !Number.isSafeInteger(m.time_ms) || m.time_ms < 0 || typeof m.saved !== 'boolean' || typeof m.armed !== 'boolean' ||
            !TRACE.every(k => Number.isSafeInteger(m[k])) || !['ok', 'applied', 'saved', 'save_failed', 'disarm_required', 'busy', 'invalid'].includes(m.status) || !/^[a-f0-9]{64}$/.test(m.mac)) return
        if (m.diagnostics_version !== undefined && (m.diagnostics_version !== 2 || typeof m.pid_active !== 'boolean' || typeof m.feedback_valid !== 'boolean' ||
            !['feedback_min_mdeg','feedback_max_mdeg','feedback_rejects','command_min_mdeg','command_max_mdeg'].every(k => Number.isSafeInteger(m[k])) || m.feedback_rejects < 0 || m.feedback_min_mdeg >= m.feedback_max_mdeg || m.command_min_mdeg >= m.command_max_mdeg || m.command_min_mdeg < m.feedback_min_mdeg || m.command_max_mdeg > m.feedback_max_mdeg ||
            (m.pid_active && (!m.armed || !m.feedback_valid)))) return
        if (!crypto.timingSafeEqual(Buffer.from(sign(this.key, responseText(m)), 'hex'), Buffer.from(m.mac, 'hex'))) return
        if (pending.operation !== 'get' && pending.boot !== m.boot) return
        if (['applied', 'saved'].includes(m.status) && (!FIELDS.every(k => m[k] === pending.settings[k]) || (m.status === 'saved' && !m.saved))) return
        const received = this.clock(), rtt = received - pending.started
        this.device = { ...m }; delete this.device.mac
        this.offset = m.time_ms - (received + pending.started) / 2; this.uncertainty = rtt / 2 + 1; this.received = received
        pending.finish(null, { ...this.device, round_trip_ms: rtt })
      } catch { /* Malformed and unsigned replies never acknowledge a change. */ }
    })
  }
  exchange (operation, settings) {
    if (this.closed) return Promise.reject(new Error('Steering connection closed'))
    if (this.pending) return Promise.reject(new Error('Another steering request is pending'))
    const id = crypto.randomBytes(16).toString('hex'), now = this.clock()
    const issued = operation === 'get' ? 0 : Math.max(1, Math.floor(now + this.offset - this.uncertainty))
    const m = { v: 1, type: 'steering_tuning', request_id: id, operation, boot: operation === 'get' ? '' : this.device.boot,
      issued_ms: issued, expires_ms: operation === 'get' ? 0 : issued + 1500, ...Object.fromEntries(FIELDS.map(k => [k, operation === 'get' ? 0 : settings[k]])) }
    m.mac = sign(this.key, requestText(m)); const bytes = Buffer.from(JSON.stringify(m))
    return new Promise((resolve, reject) => {
      const finish = (error, result) => { if (this.pending?.id !== id) return; clearInterval(retry); clearTimeout(timeout); this.pending = null; error ? reject(error) : resolve(result) }
      const send = () => { if (!this.closed) this.socket.send(bytes, this.port, this.host, error => { if (error) finish(error) }) }
      const retry = setInterval(send, 180), timeout = setTimeout(() => finish(new Error('ESP32 steering settings unavailable. Join ESP Wi-Fi and install the tuning firmware.')), 1200)
      this.pending = { id, operation, boot: m.boot, settings, started: now, finish }; send()
    })
  }
  async request (operation, settings) {
    if (!['get', 'apply', 'save'].includes(operation)) throw new Error('Unknown steering operation')
    if (operation === 'get') return this.exchange('get')
    if (!validSettings(settings)) throw new Error('Check PID values, deadband, filter, and PWM limits')
    await this.exchange('get')
    if (this.device.armed || this.device.drive_pwm !== 0) throw new Error('Stop / disarm before applying or saving steering settings')
    if (this.uncertainty > 500) throw new Error('ESP32 round-trip time too high; retry with a stronger Wi-Fi signal')
    return this.exchange(operation, settings)
  }
  close () { this.closed = true; this.pending?.finish(new Error('Steering connection closed')); try { this.socket.close() } catch {} }
}
module.exports = { SteeringTuning, FIELDS, TRACE, requestText, responseText, validSettings, sign }
