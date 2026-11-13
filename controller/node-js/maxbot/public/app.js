'use strict'

const $ = id => document.getElementById(id)
let socket, paired = false, robot = false, seq = 0, latest = null, peer = null, candidates = [], held = false, heartbeatTimer, manualTimer, lastTelemetry = 0, sessionId = "", eventId = 0
const encoder = new TextEncoder()
const send = m => { if (paired && socket?.readyState === WebSocket.OPEN) socket.send(JSON.stringify({ ...m, seq: ++seq })) }
const command = (action, values = {}) => send({ type: 'command', action, ...values })
const modeMoving = () => ['SCOUT', 'RUN', 'MANUAL'].includes(latest?.mode)

function availability () {
  const fresh = paired && robot && Date.now() - lastTelemetry < 600
  document.querySelectorAll('[data-action]').forEach(button => {
    const action = button.dataset.action
    const ready = action === 'arm_manual' ? latest?.manual_ready : latest?.calibrated
    button.disabled = !fresh || (!['stop', 'reset'].includes(action) && (!ready || latest?.mode === 'ESTOP')) || (action === 'run' && latest?.mode !== 'READY')
  })
  $('deadman').disabled = !fresh || !latest?.manual_ready || !latest?.armed || !latest?.manual_armed || latest?.mode === 'ESTOP'
  $('steer').disabled = $('throttle').disabled = $('deadman').disabled
  $('collect').disabled = !fresh
  const stopped = fresh && !modeMoving() && !latest?.armed
  for(const id of ['upload','plan','freeze-map','export','review','benchmark-start','benchmark-stop']) $(id).disabled = !stopped
  $('plan').disabled = !stopped || !latest?.course_map?.frozen
  $('freeze-map').disabled = !stopped || latest?.course_map?.cones?.length!==4
  $('upload').disabled = !stopped || $('upload').dataset.busy==='yes'
  $('activate').disabled = !stopped || !$('activate').dataset.ready || $('upload').dataset.busy==='yes'
  $('connection').textContent = !paired ? 'Disconnected' : !robot ? 'Waiting for Pixel' : fresh ? 'Pixel connected' : 'Telemetry stale'
  if (!fresh) { release(); if (latest) $('fault').textContent = 'Telemetry expired. Motion must remain stopped.' }
}

async function mac (key, message) {
  if (!crypto.subtle) throw new Error('Use localhost or HTTPS for browser pairing')
  const imported = await crypto.subtle.importKey('raw', encoder.encode(key), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign'])
  return Array.from(new Uint8Array(await crypto.subtle.sign('HMAC', imported, encoder.encode(message))), b => b.toString(16).padStart(2, '0')).join('')
}

$('pair').addEventListener('submit', event => {
  event.preventDefault(); cleanup(); seq = 0
  const key = $('key').value
  socket = new WebSocket(`${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/operator`)
  $('pair-status').textContent = 'Pairing…'
  socket.onmessage = async event => {
    try {
      const m = JSON.parse(event.data)
      if (m.type === 'challenge') socket.send(JSON.stringify({ type: 'auth', role: 'operator', mac: await mac(key, `AUTH|operator|${m.nonce}`) }))
      else if (m.type === 'authenticated') { paired = true; robot = m.robot; sessionId=m.session_id; eventId=0; $('key').value = ''; $('pair-status').textContent = 'Paired. Connect the phone to this computer’s LAN address.'; heartbeatTimer = setInterval(() => { if (!document.hidden) send({ type: 'heartbeat' }) }, 100); send({ type: 'heartbeat' }) }
      else if (m.type === 'robot_connected') robot = true
      else if (m.type === 'robot_disconnected') { robot = false; release(); closeVideo() }
      else if (m.type === 'telemetry') { latest = m; lastTelemetry = Date.now(); render(m) }
      else if(m.type === 'export') $('notice').textContent = m.status==='saved' ? `Run saved on Mac: ${m.path}` : `Export: ${m.status}`
      else if (m.type === 'direct_stop') $('direct-stop-status').textContent = `Direct ESP stop: ${m.status}${m.reason ? ' · '+m.reason : ''}`
      else if (m.type === 'planning') { $('planning-status').textContent = m.reason || JSON.stringify(m.result?.attempts || m.result || m.status); plannedReference=m.result?.referenceCanonical || null; $('save-reference').disabled=!plannedReference }
      else if (m.type === 'artifact_result') {
        $('artifact-status').textContent = m.message || m.status
        const pending = artifactWaiters.get(m.request_id); if (pending) { clearTimeout(pending.timer); artifactWaiters.delete(m.request_id); if (m.status === 'ok') pending.resolve(m); else pending.reject(new Error(m.message || 'Artifact rejected')) }
      }
      else if (m.type === 'rtc') await signal(m)
      availability()
    } catch (error) { $('pair-status').textContent = error.message }
  }
  socket.onclose = event => { cleanup(); $('pair-status').textContent = `Disconnected: ${event.reason || 'connection closed'}` }
  socket.onerror = () => { $('pair-status').textContent = 'Connection failed. Check server and pairing key.' }
})

function cleanup () {
  for(const waiter of artifactWaiters.values()){clearTimeout(waiter.timer);waiter.reject(new Error('Dashboard disconnected; stage a fresh set'))}artifactWaiters.clear();stagedTransaction=null;stagedManifest=null;delete $('activate').dataset.ready
  release(); clearInterval(heartbeatTimer); paired = false; robot = false
  if (socket) { socket.onclose = null; socket.close(); socket = null }
  closeVideo(); availability()
}
function closeVideo () { if (peer) peer.close(); peer = null; candidates = []; $('video').srcObject = null; $('video-empty').hidden = false; $('video-empty').style.display = 'flex'; $('screenshot').disabled = true }
async function signal (m) {
  if (m.kind === 'offer') {
    if (peer) peer.close()
    peer = new RTCPeerConnection({ iceServers: [] })
    peer.onicecandidate = event => { if (event.candidate) send({ type: 'rtc', kind: 'candidate', ...event.candidate.toJSON() }) }
    peer.ontrack = event => { $('video').srcObject = event.streams[0] || new MediaStream([event.track]); $('video-empty').style.display = 'none'; $('screenshot').disabled = false }
    await peer.setRemoteDescription({ type: 'offer', sdp: m.sdp })
    for (const ice of candidates) await peer.addIceCandidate(ice)
    candidates = []
    const answer = await peer.createAnswer(); await peer.setLocalDescription(answer); send({ type: 'rtc', kind: 'answer', sdp: answer.sdp })
  } else if (m.kind === 'candidate') {
    const ice = { candidate: m.candidate, sdpMid: m.sdpMid, sdpMLineIndex: m.sdpMLineIndex }
    if (peer?.remoteDescription) await peer.addIceCandidate(ice)
    else if (candidates.length < 64) candidates.push(ice)
  }
}

document.querySelectorAll('[data-action]').forEach(button => button.addEventListener('click', () => { release(); command(button.dataset.action) }))
$('estop').addEventListener('click', () => { release(); command('estop'); $('notice').textContent = paired ? 'Emergency stop requested; verify ESP32 acknowledgement.' : 'Disconnected: software stop cannot reach the car. Disconnect motor power.' })
document.addEventListener('keydown', event => { if (event.key === 'Escape') { event.preventDefault(); $('estop').click() } })
for (const id of ['steer', 'throttle']) $(id).addEventListener('input', () => { $('steer-value').value = `${$('steer').value}°`; $('throttle-value').value = `${$('throttle').value} PWM` })
function manual () { if (held) command('manual', { steering_deg: Number($('steer').value), pwm: Number($('throttle').value), deadman: true, session_id: sessionId, event_id: ++eventId, event_ms: Date.now(), deadline_ms: Date.now()+100 }) }
function release () {
  if (held) { held = false; command('manual', { steering_deg: 0, pwm: 0, deadman: false, session_id: sessionId, event_id: ++eventId, event_ms: Date.now(), deadline_ms: Date.now()+100 }) }
  clearInterval(manualTimer); $('deadman').classList.remove('held')
}
$('deadman').addEventListener('pointerdown', event => { if ($('deadman').disabled) return; event.preventDefault(); held = true; $('deadman').setPointerCapture(event.pointerId); $('deadman').classList.add('held'); manual(); manualTimer = setInterval(manual, 50) })
for (const type of ['pointerup', 'pointercancel', 'lostpointercapture']) $('deadman').addEventListener(type, release)
$('deadman').addEventListener('keydown', event => { if ((event.key === ' ' || event.key === 'Enter') && !event.repeat && !$('deadman').disabled) { event.preventDefault(); held = true; manual(); manualTimer = setInterval(manual, 50); $('deadman').classList.add('held') } })
$('deadman').addEventListener('keyup', release)
$('deadman').addEventListener('blur',release)
window.addEventListener('blur', () => { release(); command('stop') })
document.addEventListener('visibilitychange', () => { if (document.hidden) { release(); command('stop') } })
window.addEventListener('pagehide', cleanup)
$('collect').addEventListener('change', () => command('collection', { enabled: $('collect').checked }))

const fixed = (value, places = 2) => typeof value === 'number' && Number.isFinite(value) ? value.toFixed(places) : '—'
function render (m) {
  $('mode').textContent = m.mode || 'UNKNOWN'
  $('speed').textContent = `${fixed(m.speed_mps)} m/s`
  $('steering').textContent = `${fixed(m.steering_deg, 1)}° / ${fixed(m.target_steering_deg, 1)}°`
  $('battery').textContent = m.battery_monitoring === false ? 'Not monitored · manual pre-run check' : `${fixed(m.battery_v)} V · ${fixed(m.battery_percent, 0)}%`
  $('battery-method').textContent = m.battery_monitoring === false ? 'manual check' : 'voltage estimate'
  $('progress').textContent = `${m.passed ?? 0} / 4`; $('elapsed').textContent = `${fixed(m.elapsed_s)} s`
  $('latency').textContent = `${fixed(m.frame_age_ms, 0)} / ${fixed(m.inference_ms, 0)} ms`
  $('tracking').textContent = m.tracking || 'Tracking unavailable'; $('model').textContent = m.model || 'Model unavailable'
  $('fault').textContent = m.fault || 'Supervising. Emergency stop remains available.'
  $('calibration-summary').textContent = m.calibrated ? 'Autonomy calibration complete' : `Autonomy: ${m.missing?.length ?? '?'} requirements remaining`
  $('manual-status').textContent = m.manual_ready ? (m.manual_armed ? 'Manual commissioning armed. Hold the deadman to move.' : 'Manual commissioning ready. Raise wheels for initial direction and stop tests, then arm.') : `Manual unavailable: ${(m.manual_missing || ['Waiting for configuration']).join('; ')}`
  $('stop-behavior').textContent = `Zero throttle / stop: ${m.stop_behavior || 'Not reported'}. Neutral pause required before reversing.`
  $('missing').replaceChildren(...(m.missing || []).map(text => { const li = document.createElement('li'); li.textContent = text; return li }))
  $('collect').checked = Boolean(m.collection)
  if (Array.isArray(m.steering_limits_deg) && m.steering_limits_deg.length === 2 && !held) { $('steer').min = String(m.steering_limits_deg[0]); $('steer').max = String(m.steering_limits_deg[1]) }
  if (Number.isInteger(m.manual_max_pwm) && m.manual_max_pwm >= 1 && m.manual_max_pwm <= 255 && !held) {
    $('throttle').min = String(-m.manual_max_pwm); $('throttle').max = String(m.manual_max_pwm)
    $('throttle').value = String(Math.max(-m.manual_max_pwm, Math.min(m.manual_max_pwm, Number($('throttle').value))))
  }
  $('notice').textContent = m.thermal_limited ? 'Phone warm: optional workload reduced.' : `${m.recording_status || 'Events recording'} · optional drops: ${m.recording_drops || 0}`
  $('timing').textContent = `Capture → PWM: ${m.capture_pwm_ms ? m.capture_pwm_ms.map(v=>fixed(v,1)).join(' / ')+' ms' : 'Not measured'} · Image available → PWM p50/p95/p99: ${(m.image_available_pwm_ms || []).map(v=>fixed(v,1)).join(' / ') || 'Not measured'} · Policy → PWM: ${(m.policy_pwm_ms || []).map(v=>fixed(v,1)).join(' / ') || 'Not measured'} · Window: ${m.timing_window_samples ?? 0} commands / ${m.image_timing_samples ?? 0} images over ${fixed(m.timing_window_ms,0)} ms · Last application age: ${fixed(m.timing_application_age_ms,0)} ms · Last image timing age: ${fixed(m.image_timing_age_ms,0)} ms · Steering onset p50/p95/p99: ${(m.steering_onset_ms || []).map(v=>fixed(v,1)).join(' / ') || 'Not measured'} ms (n=${m.steering_onset_samples ?? 0}) · Settling: ${(m.steering_settling_ms || []).map(v=>fixed(v,1)).join(' / ') || 'Not measured'} ms (n=${m.steering_settling_samples ?? 0}) · Total samples: ${m.timing_samples ?? 0} · Missing: ${m.timing_missing ?? '—'} · Pending: ${m.timing_pending ?? '—'} · MCU rejected deadlines: ${m.mcu_deadline_rejected ?? '—'} · MCU event loss: ${m.mcu_application_loss ?? '—'} · Applied after deadline: ${m.failed_deadlines ?? '—'} · Clock uncertainty: ${fixed(m.clock_uncertainty_ms,1)} ms`;
  drawMap(m); drawBoxes(m.boxes || [])
}
function drawBoxes (boxes) {
  const canvas = $('overlay'), ctx = canvas.getContext('2d'); ctx.clearRect(0, 0, 640, 480)
  // Video uses contain; keep overlay in the same 4:3 content box at every viewport ratio.
  const video = $('video'), rect = video.getBoundingClientRect(), scale = Math.min(rect.width / 640, rect.height / 480)
  canvas.style.width = `${640 * scale}px`; canvas.style.height = `${480 * scale}px`; canvas.style.left = `${(rect.width - 640 * scale) / 2}px`; canvas.style.top = `${(rect.height - 480 * scale) / 2}px`
  ctx.strokeStyle = '#ffb23f'; ctx.fillStyle = '#ffb23f'; ctx.lineWidth = 2; ctx.font = '14px sans-serif'
  for (const [l, t, r, b, score] of boxes) { ctx.strokeRect(l, t, r - l, b - t); ctx.fillText(`cone ${fixed(score * 100, 0)}%`, l, Math.max(16, t - 5)) }
}
function drawMap (m) {
  const canvas = $('map'), ctx = canvas.getContext('2d'), points = [...(m.cones || []), ...(m.path || [])]; if (m.pose) points.push(m.pose)
  ctx.clearRect(0, 0, canvas.width, canvas.height)
  if (!points.length) { ctx.fillStyle = '#4c606e'; ctx.font = '18px sans-serif'; ctx.fillText('A mapped course will appear after scouting.', 28, 48); return }
  const xs = points.map(p => p[0]), ys = points.map(p => p[1]); const minX = Math.min(...xs) - .5, maxX = Math.max(...xs) + .5, minY = Math.min(...ys) - .5, maxY = Math.max(...ys) + .5
  const scale = Math.min(820 / (maxX - minX), 200 / (maxY - minY)), project = p => [40 + (p[0] - minX) * scale, 240 - (p[1] - minY) * scale]
  ctx.strokeStyle = '#0064b2'; ctx.lineWidth = 3; ctx.beginPath(); (m.path || []).forEach((p, i) => { const [x, y] = project(p); if (i === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y) }); ctx.stroke()
  ctx.font = '16px sans-serif'; (m.cones || []).forEach((p, i) => { const [x, y] = project(p); ctx.fillStyle = '#ca5400'; ctx.beginPath(); ctx.arc(x, y, 7, 0, Math.PI * 2); ctx.fill(); ctx.fillStyle = '#182530'; ctx.fillText(String(i + 1), x + 12, y - 10) })
  if (m.pose) { const [x, y] = project(m.pose); ctx.save(); ctx.translate(x, y); ctx.rotate(-m.pose[2]); ctx.fillStyle = '#182530'; ctx.fillRect(-10, -5, 20, 10); ctx.fillStyle = '#0064b2'; ctx.fillRect(5, -5, 5, 10); ctx.restore() }
}
function download (blob, name) { const link = document.createElement('a'); link.href = URL.createObjectURL(blob); link.download = name; link.click(); setTimeout(() => URL.revokeObjectURL(link.href), 1000) }
$('screenshot').addEventListener('click', () => {
  const video = $('video'); if (!video.videoWidth) return
  const canvas = document.createElement('canvas'); canvas.width = video.videoWidth; canvas.height = video.videoHeight; const ctx = canvas.getContext('2d'); ctx.drawImage(video, 0, 0); ctx.drawImage($('overlay'), 0, 0, canvas.width, canvas.height)
  const name = `maxbot-${new Date().toISOString().replaceAll(':', '-')}`
  canvas.toBlob(blob => { if (blob) download(blob, `${name}.png`) }); download(new Blob([JSON.stringify({ captured_at: new Date().toISOString(), telemetry: latest }, null, 2)], { type: 'application/json' }), `${name}.json`)
})
setInterval(availability, 100)
drawMap({}); availability()

let plannedReference = null, stagedTransaction = null, stagedManifest = null
const artifactWaiters = new Map()
function artifactRequest (packet) {
  return new Promise((resolve, reject) => {
    const request_id = `${Date.now()}-${++eventId}`
    const timer = setTimeout(() => { artifactWaiters.delete(request_id); reject(new Error('Phone acknowledgement timed out')) }, 10000)
    artifactWaiters.set(request_id, { resolve, reject, timer }); send({ type: 'artifact', ...packet, request_id })
  })
}
async function selectedJson (id) { const file = $(id).files?.[0]; if (!file || file.size > 2 * 1024 * 1024) throw new Error('Select a JSON file under 2 MiB'); return JSON.parse(await file.text()) }
$('freeze-map').addEventListener('click', () => command('freeze', { error_bound_m: Number($('scene-error').value), independent_views: Number($('scene-views').value) }))
$('plan').addEventListener('click', async () => { try { const request = $('plan-request').files?.[0] ? await selectedJson('plan-request') : latest?.planner_request; if(!request)throw new Error('A frozen map and reviewed dynamics must be received from the phone'); $('planning-status').textContent = 'Planning… stop remains available.'; send({ type: 'plan', request }) } catch (e) { $('planning-status').textContent = e.message } })
$('upload').addEventListener('click', async () => {
  if($('upload').dataset.busy==='yes')return
  $('upload').dataset.busy='yes';$('upload').disabled=true
  stagedTransaction=null;stagedManifest=null;delete $('activate').dataset.ready;$('activate').disabled=true
  try {
    if (modeMoving() || latest?.armed) throw new Error('Stop and disarm before uploading')
    const manifest = await selectedJson('set-manifest'); const files = Array.from($('artifact-files').files || [])
    if (!Array.isArray(manifest.files) || manifest.files.length < 1 || manifest.files.length > 8) throw new Error('Manifest needs 1–8 files')
    const bytes = new Uint8Array(16); crypto.getRandomValues(bytes); const transaction = Array.from(bytes, b => b.toString(16).padStart(2, '0')).join('')
    const diff = manifest.files.map(entry => `${entry.kind}: ${entry.name} · ${entry.sha256}`).join('\n')
    $('artifact-status').textContent = `Staging revision ${manifest.revision}\n${diff}`
    await artifactRequest({ operation: 'begin', transaction, manifest })
    for (const entry of manifest.files) {
      const file = files.find(f => f.name === entry.name); if (!file || file.size !== entry.size || file.size > 32 * 1024 * 1024) throw new Error(`Missing/size mismatch: ${entry.name}`)
      for (let offset = 0; offset < file.size; offset += 24576) {
        if (modeMoving() || latest?.armed) throw new Error('Upload paused: car state changed; stage a fresh set when stopped')
        const chunk = new Uint8Array(await file.slice(offset, offset + 24576).arrayBuffer()); let raw = ''; for (const b of chunk) raw += String.fromCharCode(b)
        await artifactRequest({ operation: 'chunk', transaction, name: entry.name, offset, data: btoa(raw) })
      }
    }
    stagedTransaction = transaction; stagedManifest = manifest; $('activate').dataset.ready='yes'; $('activate').disabled = false; $('artifact-status').textContent = `Staged revision ${manifest.revision}. Review files before activation.\n${diff}`
  } catch (e) { $('artifact-status').textContent = e.message } finally {delete $('upload').dataset.busy;availability()}
})
$('activate').addEventListener('click', async () => { if($('upload').dataset.busy==='yes')return; $('upload').dataset.busy='yes'; availability(); try { if (modeMoving() || latest?.armed) throw new Error('Stop/disarm first'); await artifactRequest({ operation: 'activate', transaction: stagedTransaction }); delete $('activate').dataset.ready; $('activate').disabled = true; $('artifact-status').textContent = `Applied revision ${stagedManifest.revision}; car remains disarmed.` } catch (e) { $('artifact-status').textContent = e.message } finally {delete $('upload').dataset.busy;availability()} })
$('benchmark-start').addEventListener('click', () => command('benchmark', { event: 'start', video: Boolean($('video').srcObject), duration_s: 900 }))
$('benchmark-stop').addEventListener('click', () => command('benchmark', { event: 'end' }))
$('export').addEventListener('click', () => command('export'))
$('review').addEventListener('click', () => command('review', { outcome: $('review-outcome').value, notes: $('review-notes').value, reviewed_at: new Date().toISOString() }))

$('save-reference').addEventListener('click',()=>{if(plannedReference)download(new Blob([plannedReference],{type:'application/json'}),'reference.json')})
