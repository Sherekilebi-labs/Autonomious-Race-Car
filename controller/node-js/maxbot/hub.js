'use strict'

const crypto = require('node:crypto')
const { MANUAL_PROFILE } = require('./public/manual-control')
const sign = (key, message) => crypto.createHmac('sha256', key).update(message).digest('hex')
const equal = (a, b) => typeof b === 'string' && /^[a-f0-9]{64}$/.test(b) && crypto.timingSafeEqual(Buffer.from(a, 'hex'), Buffer.from(b, 'hex'))

/** Single robot / single operator. Connection authentication and heartbeats are distinct. */
class Hub {
  constructor (key, clock = Date.now) {
    if (typeof key !== 'string' || key.length < 32) throw new Error('Set MAXBOT_PAIRING_KEY to at least 32 random characters')
    this.key = key; this.clock = clock; this.robot = null; this.operator = null; this.observer = null; this.peers = new Map(); this.lastTelemetry = null; this.directStop = null; this.onPlan = null; this.onArtifact = null
  }

  attach (peer, role) {
    const nonce = crypto.randomBytes(16).toString('hex')
    this.peers.set(peer, { role, nonce, joined: this.clock(), authenticated: false, signed:false, outbound:0, inbound:-1, seq: -1, heartbeat: 0, eventId: -1, sessionId: crypto.randomBytes(16).toString("hex") })
    peer.send(JSON.stringify({ type: 'challenge', nonce }))
  }

  send (peer, message) {
    if (peer && peer.readyState === 1 && peer.bufferedAmount < 256 * 1024) { const state=this.peers.get(peer);if(state?.authenticated&&state.signed&&!['challenge','authenticated'].includes(message.type)){const payload=JSON.stringify(message),sequence=++state.outbound;message={type:'signed_message',session_id:state.sessionId,sequence,payload,mac:sign(this.key,`S|${state.sessionId}|${sequence}|${payload}`)}}peer.send(JSON.stringify(message)) }
  }

  receive (peer, raw) {
    const state = this.peers.get(peer)
    if (!state || raw.length > 65536) return peer.close(1009, 'Message too large')
    let m
    try { m = JSON.parse(raw) } catch { return peer.close(1007, 'Invalid JSON') }
    if (!state.authenticated) {
      if (this.clock() - state.joined > 10000 || m.type !== 'auth' || m.role !== state.role || !equal(sign(this.key, `AUTH|${state.role}|${state.nonce}${m.control_signing===1?'|signed-v1':''}`), m.mac)) return peer.close(1008, 'Pairing failed')
      if ((state.role === 'robot' && this.robot) || (state.role === 'operator' && this.operator) || (state.role === 'observer' && this.observer)) return peer.close(1008, 'Role already connected')
      state.signed=m.control_signing===1;state.authenticated = true; state.nonce = null; state.heartbeat = this.clock()
      if (state.role === 'robot') this.robot = peer
      else if (state.role === 'observer') {this.observer = peer;if(this.onObserverConnected)this.onObserverConnected()}
      else { this.operator = peer; state.heartbeat = this.clock() }
      this.send(peer, { type: 'authenticated', role: state.role, control_signing:state.signed?1:0, robot: Boolean(this.robot), session_id: state.sessionId, server_ms: this.clock() })
      if (this.robot && this.operator) { this.send(this.robot, { type: 'operator_joined', session_id: this.peers.get(this.operator).sessionId }); this.send(this.operator, { type: 'robot_connected' }) }
      return
    }
    if(state.signed){
      if(m.type!=='signed_message'||m.session_id!==state.sessionId||!Number.isSafeInteger(m.sequence)||m.sequence<=state.inbound||typeof m.payload!=='string'||!equal(sign(this.key,`C|${state.sessionId}|${m.sequence}|${m.payload}`),m.mac))return
      state.inbound=m.sequence;try{m=JSON.parse(m.payload)}catch{return}
    }
    if (state.role === 'observer') { state.heartbeat = this.clock(); if(this.onObserver)this.onObserver(m); return }
    if (state.role === 'robot') {
      if (m.type === 'telemetry') { state.heartbeat=this.clock(); this.lastTelemetry = { ...m, received_ms: this.clock() }; if (!this.operator || this.operator.bufferedAmount < 16 * 1024) this.send(this.operator, this.lastTelemetry); if(this.onTelemetry)this.onTelemetry() }
      else if (m.type === 'clock_probe' && Number.isSafeInteger(m.t1)) { const t2 = this.clock(); this.send(peer, { type: 'clock_sample', t1: m.t1, t2, t3: this.clock() }) }
      else if(m.type==='export_transfer'&&this.onExport)this.onExport(m)
      else if (['signal_trace','signal_monitor_status'].includes(m.type) && this.onRobotSignal) this.onRobotSignal(m)
      else if (m.type === 'experiment_status' && this.onExperimentPhone) this.onExperimentPhone(m)
      else if (m.type === 'vehicle_rpc_reply' && this.onVehicleRpc) this.onVehicleRpc(m)
      else if (m.type === 'collision_warning') this.send(this.operator, m)
      else if (['artifact_result','export','capabilities'].includes(m.type)) this.send(this.operator,m)
      else if (m.type === 'rtc') this.send(this.operator, m)
      return
    }
    if (!Number.isSafeInteger(m.seq) || m.seq <= state.seq) return
    state.seq = m.seq
    if (m.type === 'heartbeat') {
      state.heartbeat = this.clock(); this.send(this.robot, { type: 'heartbeat' })
    } else if (m.type === 'command') {
      if (!['arm', 'arm_manual', 'scout', 'run', 'stop', 'estop', 'reset', 'manual', 'collection', 'freeze', 'verify', 'benchmark', 'export', 'review'].includes(m.action)) return
      if (m.action === 'manual' && (!Number.isFinite(m.steering_deg) || !Number.isInteger(m.pwm) || Math.abs(m.pwm) > 255 || typeof m.deadman !== 'boolean')) return
      if (m.action === 'manual') {
        if (m.session_id !== state.sessionId || !Number.isSafeInteger(m.event_id) || m.event_id <= state.eventId || !Number.isSafeInteger(m.event_ms) || !Number.isSafeInteger(m.deadline_ms) || m.deadline_ms <= this.clock() || m.deadline_ms - m.event_ms > MANUAL_PROFILE.inputMs || m.deadline_ms <= m.event_ms || m.event_ms > this.clock() + 20) return
        state.eventId = m.event_id
      }
      if (m.action==='estop' && this.directStop) this.directStop.request()
      if (this.clock() - state.heartbeat > 500 && m.action !== 'estop' && m.action !== 'stop') return
      if(this.onTakeover)this.onTakeover(m.action)
      this.send(this.robot, m)
    } else if (m.type === 'experiment' && this.onExperiment) {
      if(this.clock()-state.heartbeat<=350)this.onExperiment(m,peer)
    } else if (m.type === 'signal_monitor' && this.onMonitor) {
      if (this.clock() - state.heartbeat <= 500 && ['start','stop','events','status'].includes(m.operation)) this.onMonitor(m, peer)
    } else if (m.type === 'steering_tuning' && this.onTuning) {
      if(this.clock()-state.heartbeat<=500 && ['get','apply','save'].includes(m.operation) && typeof m.request_id==='string' && m.request_id.length<=80)this.onTuning(m,peer)
    } else if (m.type === 'plan' && this.onPlan) { this.onPlan(m.request).catch(e => this.send(peer, { type: 'planning', status: 'failed', reason: e.message }))
    } else if (m.type === 'artifact' && this.onArtifact) { this.onArtifact(m, peer)
    } else if (m.type === 'rtc') this.send(this.robot, m)
  }

  detach (peer) {
    if (this.observer === peer) { this.observer = null; if(this.onObserverDisconnected)this.onObserverDisconnected() }
    if (this.onDisconnected && (this.operator === peer || this.robot === peer)) this.onDisconnected(this.operator === peer ? 'operator' : 'phone')
    if (this.operator === peer) { this.send(this.robot, { type: 'operator_left' }); this.operator = null }
    if (this.robot === peer) { this.robot = null; this.lastTelemetry = null; this.send(this.operator, { type: 'robot_disconnected' }) }
    this.peers.delete(peer)
  }

  tick () {
    for (const [peer, state] of this.peers) {
      if(state.authenticated&&state.role==='observer'&&this.clock()-state.heartbeat>2000){this.detach(peer);peer.close(1008,'Observer heartbeat expired');continue;}
      if(state.authenticated&&state.role==='robot'&&this.clock()-state.heartbeat>1500){this.detach(peer);peer.close(1008,'Phone registration expired');continue;}
      if ((!state.authenticated && this.clock() - state.joined > 10000) || (state.authenticated && state.role === 'operator' && this.clock() - state.heartbeat > 500)) {
        this.detach(peer); peer.close(1008, 'Operator heartbeat expired')
      }
    }
  }
}

module.exports = { Hub, sign }
