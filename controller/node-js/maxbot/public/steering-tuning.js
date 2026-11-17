'use strict'
class SteeringPanel {
  constructor (send) {
    this.send = send; this.paired = false; this.state = null; this.samples = []; this.serial = 0; this.dirty = false
    this.fields = { kp_milli: ['pid-p', 1000], ki_milli: ['pid-i', 1000], kd_milli: ['pid-d', 1000], deadband_mdeg: ['pid-deadband', 1000], min_pwm: ['pid-min', 1], max_pwm: ['pid-max', 1], filter_millihz: ['pid-filter', 1000] }
    for (const [id] of Object.values(this.fields)) document.getElementById(id).addEventListener('input', () => { this.dirty = true; this.updateButtons() })
    document.getElementById('pid-read').addEventListener('click', () => { this.dirty = false; this.request('get') })
    document.getElementById('pid-apply').addEventListener('click', () => this.request('apply'))
    document.getElementById('pid-save').addEventListener('click', () => this.request('save'))
    document.getElementById('pid-preset').addEventListener('click', () => {
      this.fill({ kp_milli: 50000, ki_milli: 0, kd_milli: 150, deadband_mdeg: 750, min_pwm: 0, max_pwm: 255, filter_millihz: 12000 })
      this.dirty = true; this.message('Starting values loaded into these fields. Apply while disarmed, then test under load.'); this.updateButtons()
    })
    document.getElementById('steering-tuning').addEventListener('toggle', () => { if (this.open()) this.request('get') })
    this.timer = setInterval(() => { if (this.open() && this.paired && !this.pending) this.request('get', true); this.updateButtons() }, 100)
    this.updateButtons()
  }
  open () { return document.getElementById('steering-tuning').open && !document.hidden }
  message (text) { document.getElementById('pid-status').textContent = text }
  clearFeedback (text) {
    document.getElementById('pid-feedback').textContent = text
    document.getElementById('pid-feedback-range').textContent = ''
    this.samples = []; this.draw()
  }
  fill (settings) { for (const [key, [id, scale]] of Object.entries(this.fields)) document.getElementById(id).value = String(settings[key] / scale) }
  values () {
    const settings = {}; let valid = true
    for (const [key, [id, scale]] of Object.entries(this.fields)) {
      const field = document.getElementById(id), number = field.value.trim() === '' ? NaN : Number(field.value), scaled = Math.round(number * scale)
      const good = Number.isFinite(number) && field.checkValidity() && Math.abs(scaled - number * scale) < 1e-6
      field.setAttribute('aria-invalid', String(!good)); valid &&= good; settings[key] = scaled
    }
    if (settings.min_pwm > settings.max_pwm) { document.getElementById('pid-min').setAttribute('aria-invalid', 'true'); valid = false }
    return valid ? settings : null
  }
  availability (paired) {
    this.paired = paired
    if (!paired) { if(this.state || this.samples.length)this.clearFeedback('ESP32 disconnected. No live PID target.'); clearTimeout(this.timeout); this.pending = null; this.state = null; this.dirty = false; this.nextOperation = null }
    this.updateButtons()
  }
  updateButtons () {
    const fresh = this.state && Date.now() - this.received < 1200
    if(this.state && !fresh){this.state=null;this.clearFeedback('ESP32 feedback expired. No live PID target.')}
    const disarmed = fresh && !this.state.armed && this.state.drive_pwm === 0
    const ready = this.paired && (!this.pending || this.pending.operation === 'get')
    document.getElementById('pid-read').disabled = !ready
    document.getElementById('pid-preset').disabled = !ready
    for (const id of ['pid-apply', 'pid-save']) document.getElementById(id).disabled = !ready || !disarmed || !this.values()
    document.getElementById('pid-gate').textContent = !this.paired ? 'Pair the console to read steering settings.' : !fresh ? 'Read the ESP32 settings to connect.' : !disarmed ? 'Stop / disarm to apply or save. Live feedback remains available while driving.' : 'Disarmed. Apply uses these values temporarily; Save to ESP32 keeps them after power-off.'
  }
  request (operation, quiet = false) {
    if (!this.paired) return
    if (this.pending) { if (operation !== 'get' && this.pending.operation === 'get') this.nextOperation = operation; return }
    const settings = operation === 'get' ? null : this.values()
    if (operation !== 'get' && !settings) { this.message('Check the highlighted fields. Minimum PWM must not exceed maximum PWM.'); return }
    const id = 'steer-' + Date.now() + '-' + (++this.serial)
    this.pending = { id, operation, quiet }
    if (!quiet) this.message(operation === 'get' ? 'Reading ESP32…' : operation === 'save' ? 'Saving and verifying ESP32 flash…' : 'Applying steering settings…')
    this.send({ type: 'steering_tuning', request_id: id, operation, settings })
    this.timeout = setTimeout(() => { if (this.pending?.id !== id) return; this.pending = null; this.state = null; this.clearFeedback('ESP32 feedback unavailable. No live PID target.'); this.message('No ESP32 acknowledgement. Check ESP Wi-Fi and tuning firmware.'); this.updateButtons() }, 2500)
    this.updateButtons()
  }
  receive (m) {
    if (!this.pending || this.pending.id !== m.request_id) return
    const request = this.pending; clearTimeout(this.timeout); this.pending = null
    if (m.error) { this.state = null; this.clearFeedback('ESP32 feedback unavailable. No live PID target.'); this.message(m.error); this.updateButtons(); return }
    const s = m.device
    if (!s) return
    this.state = s; this.received = Date.now()
    if (['applied', 'saved'].includes(s.status)) { this.dirty = false; this.fill(s) }
    else if (!this.dirty) this.fill(s)
    if (s.status === 'saved' && s.saved) this.message('Saved to ESP32 flash and read back successfully. These settings survive power-off.')
    else if (s.status === 'applied') this.message('Applied on ESP32. Test under load, then Save to ESP32 to keep these values.')
    else if (s.status !== 'ok') this.message({ disarm_required: 'ESP32 rejected the change: stop / disarm first.', busy: 'ESP32 is finishing another change. Retry.', save_failed: 'ESP32 flash verification failed. Settings were not confirmed saved.', invalid: 'ESP32 rejected these parameter values.' }[s.status] || s.status)
    else if (!request.quiet) this.message(s.saved ? 'Read settings saved in ESP32 flash.' : 'Read active settings. Current values are not saved to flash.')
    document.getElementById('pid-active').textContent = `ESP32 active: P ${s.kp_milli / 1000} · I ${s.ki_milli / 1000} · D ${s.kd_milli / 1000} · deadband ${s.deadband_mdeg / 1000}° · PWM ${s.min_pwm}–${s.max_pwm} · ${s.saved ? 'saved' : 'temporary / firmware defaults'}`
    const target = s.target_mdeg / 1000, actual = s.actual_mdeg / 1000
    const active = s.pid_active === true, known = typeof s.pid_active === 'boolean'
    const terms = `P ${(s.p_milli / 1000).toFixed(1)} · I ${(s.i_milli / 1000).toFixed(1)} · D ${(s.d_milli / 1000).toFixed(1)} · Pot ${s.pot_adc} ADC`
    document.getElementById('pid-feedback').textContent = s.feedback_valid === false
      ? `Feedback temporarily invalid. Outputs neutral. Last valid angle ${actual.toFixed(1)}°. Pot ${s.pot_adc} ADC`
      : active ? `PID active · ESP target ${target.toFixed(1)}° · Actual ${actual.toFixed(1)}° · Error ${(target - actual).toFixed(1)}° · Steering ${s.steering_pwm} PWM\n${terms}`
        : known || !s.armed ? `PID inactive · No applied target · Actual ${actual.toFixed(1)}° · Steering ${s.steering_pwm} PWM\n${terms}`
          : `PID activity not reported by this firmware. Last ESP target ${target.toFixed(1)}° · Actual ${actual.toFixed(1)}° · Steering ${s.steering_pwm} PWM\n${terms}`
    document.getElementById('pid-feedback-range').textContent = s.diagnostics_version === 2
      ? `Accepted feedback ${s.feedback_min_mdeg / 1000}° to ${s.feedback_max_mdeg / 1000}° · Commands ${s.command_min_mdeg / 1000}° to ${s.command_max_mdeg / 1000}° · Rejected samples ${s.feedback_rejects}${actual < s.command_min_mdeg / 1000 || actual > s.command_max_mdeg / 1000 ? ' · Angle beyond the calibrated endpoints is extrapolated.' : ''}`
      : 'Update the ESP32 firmware for PID activity and feedback-range diagnostics.'
    this.samples.push({ time: this.received, target: active ? target : null, actual: s.feedback_valid === false ? null : actual }); this.samples = this.samples.filter(p => this.received - p.time <= 10000)
    this.draw(); this.updateButtons();
    if (this.nextOperation) {const next=this.nextOperation;this.nextOperation=null;this.request(next)}
  }
  draw () {
    const canvas = document.getElementById('pid-chart'), ctx = canvas.getContext('2d'), w = canvas.width, h = canvas.height
    ctx.clearRect(0, 0, w, h); ctx.strokeStyle = '#cbd4dc'; ctx.beginPath(); ctx.moveTo(0, h / 2); ctx.lineTo(w, h / 2); ctx.stroke()
    for (const [field, color] of [['target', '#0064b2'], ['actual', '#182530']]) {
      ctx.strokeStyle = color; ctx.lineWidth = 3; ctx.beginPath()
      let connected = false
      const span = Math.max(30, ...this.samples.map(p => Math.max(Math.abs(p.target || 0), Math.abs(p.actual || 0))))
      this.samples.forEach(p => { if(p[field] === null){connected=false;return} const x = w * (1 - (this.received - p.time) / 10000), y = h / 2 - p[field] / span * (h / 2 - 10); if (connected) ctx.lineTo(x, y); else ctx.moveTo(x, y); connected=true }); ctx.stroke()
    }
  }
}
