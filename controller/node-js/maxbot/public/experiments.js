'use strict'
class ExperimentPanel {
  constructor (send) {
    this.send = send; this.state = null; this.$ = id => document.getElementById('experiment-' + id)
    this.$('attend').addEventListener('change', () => send({ type: 'experiment', operation: 'attend', options: { enabled: this.$('attend').checked, usb_power_verified: this.$('attend').checked, area_clear: this.$('attend').checked, observer_framed: this.$('attend').checked } }))
    this.$('prepare').addEventListener('click', () => send({ type: 'experiment', operation: 'prepare', recipe: this.$('recipe').value, metadata: { notes: this.$('notes').value, battery_v: this.$('battery').value === '' ? null : Number(this.$('battery').value) } }))
    this.$('start').addEventListener('click', () => send({ type: 'experiment', operation: 'start', prepared_id: this.state?.prepared?.id }))
    this.$('abort').addEventListener('click', () => send({ type: 'experiment', operation: 'abort' }))
  }
  telemetry (m) { this.$('links').textContent = `Pixel ↔ ESP: ${m.vehicle_connected ? m.vehicle_transport || 'connected' : 'disconnected'}. Pixel ↔ Mac: authenticated. Observer: ${this.observer?.camera_ready ? (this.observer.recording ? 'recording' : 'camera ready') : 'waiting'}.`; this.$('attend').disabled = false }
  receive (m) {
    if (m.type === 'observer_status') { this.observer = m; return }
    if (m.recipes && !this.$('recipe').options.length) for (const r of m.recipes) { const option = document.createElement('option'); option.value = r.id; option.textContent = `${r.title} · ${(r.duration_ms / 1000).toFixed(1)} s`; this.$('recipe').append(option) }
    if (m.error) { this.$('status').textContent = m.error; return }
    this.state = m; if (m.observer) this.observer = m.observer
    this.$('attend').checked = Boolean(m.attended)
    this.$('prepare').disabled = Boolean(m.active)
    this.$('start').disabled = !m.prepared || !m.attended || m.active
    this.$('abort').disabled = !m.active
    this.$('status').textContent = m.status === 'saved' ? `Saved ${m.directory}. ${m.summary?.complete ? 'Evidence collected; review required.' : 'Evidence incomplete; inspect summary.'}` : m.prepared ? `Prepared: ${m.prepared.title}. Ready for Start or Codex.` : `${m.status || 'idle'}${m.experiment_id ? ' · ' + m.experiment_id : ''}`
  }
  disconnected () { this.state = null; for (const name of ['attend', 'prepare', 'start', 'abort']) this.$(name).disabled = true; this.$('attend').checked = false; this.$('status').textContent = 'Dashboard disconnected. Experiment authority revoked.' }
}
