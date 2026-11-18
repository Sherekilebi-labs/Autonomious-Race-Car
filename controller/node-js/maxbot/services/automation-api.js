'use strict'
const crypto = require('node:crypto')
const tokenFor = key => crypto.createHmac('sha256', key).update('MAXBOT_AUTOMATION_LOCAL_V1').digest('hex')
const safeEqual = (a, b) => typeof a === 'string' && a.length === b.length && crypto.timingSafeEqual(Buffer.from(a), Buffer.from(b))
function automationApi ({ key, experiments, signals, hub, planner, jobs, shutdown }) {
  const token = tokenFor(key)
  return async (req, res) => {
    if (req.url?.split('?')[0] !== '/automation') return false
    if (req.method !== 'POST' || !['127.0.0.1', '::1', '::ffff:127.0.0.1'].includes(req.socket.remoteAddress) || req.headers.origin || !safeEqual(req.headers.authorization, `Bearer ${token}`)) { res.writeHead(403); res.end('Forbidden'); return true }
    try {
      let body = ''; for await (const b of req) { body += b; if (Buffer.byteLength(body) > 128 * 1024) throw new Error('Automation request too large') }
      const { operation, args = {} } = JSON.parse(body); let result
      switch (operation) {
        case 'shutdown': setTimeout(()=>shutdown(),10);result={stopping:true};break
        case 'status': result = { ...experiments.current(), pid:process.pid, telemetry: hub.lastTelemetry, signal: signals.current(), jobs: jobs.current() }; break
        case 'prepare': result = experiments.prepare(args.recipe, args.metadata, args.parameters); break
        case 'start': result = await experiments.start(args.prepared_id); break
        case 'abort': await experiments.abort('mcp_abort'); result = experiments.current(); break
        case 'list': result = await experiments.list(); break
        case 'read': result = await experiments.read(args.id, args.name); break
        case 'preview': result = experiments.observer.preview || { unavailable: true, reason: 'No observer preview received' }; break
        case 'plan': result = await planner.run(args.request); break
        case 'stage': case 'activate': {
          const t = hub.lastTelemetry
          if (!t || Date.now() - t.received_ms > 350 || t.armed || ['RUN', 'SCOUT', 'MANUAL'].includes(t.mode) || experiments.active) throw new Error('Stopped/disarmed phone required')
          const packet = args.packet
          if (!packet || !['begin', 'chunk', 'activate'].includes(packet.operation) || (operation === 'activate') !== (packet.operation === 'activate')) throw new Error('Invalid artifact operation')
          hub.onArtifact({ ...packet, type: 'artifact', request_id: crypto.randomUUID() }, hub.operator); result = { requested: true, acknowledgement: 'Inspect artifact_result on dashboard; verify applied hashes in status' }; break
        }
        case 'analyse': case 'policy_search': case 'train_detector': result = await jobs.start(operation, args); break
        case 'job': result = await jobs.read(args.id); break
        default: throw new Error('Unknown automation operation')
      }
      res.writeHead(200, { 'Content-Type': 'application/json' }); res.end(JSON.stringify({ result }))
    } catch (e) { res.writeHead(400, { 'Content-Type': 'application/json' }); res.end(JSON.stringify({ error: e.message })) }
    return true
  }
}
module.exports = { automationApi, tokenFor }
