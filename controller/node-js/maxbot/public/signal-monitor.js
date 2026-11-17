'use strict'

class SignalPanel {
  constructor (send, settings) {
    this.send = send; this.settings = settings; this.state = 'idle'; this.queue = []; this.browserDropped = 0; this.ordinal = 0
    this.get = id => document.getElementById(id)
    this.get('signal-start').addEventListener('click', () => {
      if (['starting', 'recording', 'saving'].includes(this.state)) return
      this.state = 'starting'; this.queue = []; this.browserDropped = 0
      this.ordinal = 0; this.get('signal-start').disabled = true
      this.get('signal-status').textContent = 'Starting monitoring on the Pixel…'
      this.send({ type: 'signal_monitor', operation: 'start', options: { ...this.settings(), notes: this.get('signal-notes').value,
        user_agent: navigator.userAgent, browser_clock: { wall_ms: Date.now(), perf_ms: performance.now(), time_origin_ms: performance.timeOrigin } } })
    })
    this.get('signal-stop').addEventListener('click', () => {
      if (!['starting', 'recording'].includes(this.state)) return
      this.record({ type: 'browser_final', settings: this.settings(), browser_dropped: this.browserDropped })
      this.flush(); this.send({ type: 'signal_monitor', operation: 'stop' }); this.state = 'saving'
      this.get('signal-status').textContent = 'Saving report and draining phone traces…'
    })
    this.get('signal-download').addEventListener('click', () => this.download(this.saved?.download))
    this.get('signal-markdown').addEventListener('click', () => this.download(this.saved?.markdown_download))
    this.timer = setInterval(() => {
      if (this.state === 'recording') this.record({ type: 'browser_status', settings: this.settings(), browser_dropped: this.browserDropped })
      this.flush()
    }, 1000)
    this.flushTimer = setInterval(() => this.flush(), 100)
  }
  record (event) {
    if (!['starting', 'recording', 'saving'].includes(this.state)) return
    if (this.queue.length >= 512) { this.browserDropped++; return }
    this.queue.push({ ...event, browser_ordinal: ++this.ordinal, browser_wall_ms: Date.now(), browser_perf_ms: performance.now() })
  }
  flush () {
    if (!this.queue.length) return
    this.send({ type: 'signal_monitor', operation: 'events', events: this.queue.splice(0, 32) })
  }
  receive (m) {
    if (m.type === 'collision_warning') { this.warning(m); return }
    if (m.type !== 'signal_monitor_status') return
    this.state = m.status
    if (m.status === 'recording') this.get('signal-status').textContent = 'Monitoring · saving continuously on this Mac.'
    else if (m.status === 'saving') this.get('signal-status').textContent = 'Saving report and draining phone traces…'
    else if (m.status === 'saved') {
      this.saved = m
      this.get('signal-status').textContent = `${m.result === 'complete' ? 'Saved' : 'Saved with gaps'} · ${m.records} records. Send the JSON file for analysis. Mac copy: ${m.file}`
      this.get('signal-download').disabled = this.get('signal-markdown').disabled = false
      if (this.downloaded !== m.monitor_id) { this.downloaded = m.monitor_id; this.download(m.download) }
    } else if (m.status === 'error') { if (m.active) this.state = 'recording'; this.get('signal-status').textContent = m.error }
  }
  download (url) { if (!url || !/^\/signal-reports\/[a-f0-9]{32}\.(json|md)$/.test(url)) return; const a = document.createElement('a'); a.href = url; a.download = ''; a.click() }
  availability (paired, fresh, telemetry) {
    const busy = ['starting', 'recording', 'saving'].includes(this.state)
    this.get('signal-start').disabled = !paired || !fresh || !telemetry?.signal_monitor_available || ['RUN', 'SCOUT'].includes(telemetry?.mode) || busy
    this.get('signal-stop').disabled = !paired || !['starting', 'recording'].includes(this.state)
    if (paired && fresh && telemetry?.signal_monitor_available && this.state === 'idle') this.get('signal-status').textContent = 'Ready. Start monitoring before the first input or obstacle trial.'
    if (!fresh || !telemetry?.signal_monitor_available) {
      this.warning({ status: 'unavailable_or_stale' })
      if (!busy && this.state !== 'saved' && this.state !== 'error') this.get('signal-status').textContent = 'Connect the updated Pixel APK to start monitoring.'
    }
    if (this.warningAt && Date.now() - this.warningAt > 750) this.warning({ status: 'unavailable_or_stale' })
  }
  warning (m) {
    const clock = m.host_clock
    if (typeof clock?.host_mapped_decision_ms === 'number' && Date.now() - clock.host_mapped_decision_ms > 750 + (clock.host_clock_uncertainty_ms || 0)) m = { status: 'unavailable_or_stale' }
    const fresh = ['warning', 'no_warning_detected'].includes(m.status)
    if (!fresh) m = { status: m.status }
    if (m.frame_id && (m.frame_id === this.lastFrame ||
      (/^\d+$/.test(m.frame_id) && /^\d+$/.test(this.lastFrame || '') && BigInt(m.frame_id) <= BigInt(this.lastFrame)))) return
    const band = this.get('collision-warning'), active = m.status === 'warning'
    band.dataset.state = active ? 'warning' : m.status === 'no_warning_detected' ? 'quiet' : 'unavailable'
    this.get('warning-title').textContent = active ? 'Collision warning · object ahead' : m.status === 'no_warning_detected' ? 'No warning detected' : m.status === 'inactive' ? 'Manual vision warning inactive' : 'Vision warning unavailable or stale'
    this.get('warning-detail').textContent = 'YOLOv5n baseline · image-zone estimate · advisory'
    if (!m.frame_id || m.frame_id === this.lastFrame) return
    this.lastFrame = m.frame_id; this.warningAt = Date.now()
    this.record({ type: 'browser_warning_received', frame_id: m.frame_id, status: m.status, received_wall_ms: Date.now() })
    // A second animation callback offers the changed DOM one rendering opportunity.
    // This is not a hardware display or photon timestamp.
    requestAnimationFrame(() => requestAnimationFrame(() => this.record({ type: 'browser_warning_presented', frame_id: m.frame_id, status: m.status, presented_wall_ms: Date.now(), presented_perf_ms: performance.now() })))
  }
  resetFrames () { this.lastFrame = null; this.warningAt = null }
  disconnected () { this.flush(); this.resetFrames(); this.warning({ status: 'unavailable_or_stale' }); if (['starting', 'recording', 'saving'].includes(this.state)) this.get('signal-status').textContent = 'Disconnected. The Mac saves the received traces as an incomplete report. Reconnect to download it.'; this.state = 'idle' }
}
if (typeof module !== 'undefined') module.exports = { SignalPanel }
