#!/usr/bin/env node
'use strict'
const fs = require('node:fs'), path = require('node:path'), os = require('node:os'), { spawn } = require('node:child_process')
const root = path.resolve(__dirname, '../..'), directory = path.join(root, '.tools/automation-dashboard'), stateFile = path.join(directory, 'process.json')
const config = JSON.parse(fs.readFileSync(path.join(root, 'maxbot.runtime.json'), 'utf8'))
const port = Number(process.env.MAXBOT_PORT || 8081)
if (!Number.isInteger(port) || port < 1024 || port > 65535) throw new Error('Invalid MAXBOT_PORT')
const { tokenFor } = require(path.join(root, 'controller/node-js/maxbot/services/automation-api'))
async function request (operation) {
  const r = await fetch(`http://127.0.0.1:${port}/automation`, { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${tokenFor(config.network.pairingKey)}` }, body: JSON.stringify({ operation }), signal: AbortSignal.timeout(2000) })
  if (!r.ok) throw new Error('Another or older dashboard owns this port; it was not stopped')
  const m = await r.json();if (m.error) throw new Error(m.error);return m.result
}
function addresses () { return Object.entries(os.networkInterfaces()).flatMap(([name, rows]) => rows.filter(r => r.family === 'IPv4' && !r.internal).map(r => `${name}: ws://${r.address}:${port}/robot (Pixel), ws://${r.address}:${port}/observer (observer)`)) }
async function main () {
  const operation = process.argv[2] || 'start'
  if (operation === 'serve') {
    const app = require(path.join(root, 'controller/node-js/maxbot/server')).start({ key: config.network.pairingKey, host: '0.0.0.0', port, espHost: config.network.transport === 'wifi' ? config.network.espHost : undefined, discover: config.network.transport === 'wifi', signalDirectory: path.join(root, 'runs/signal-propagation') })
    app.server.once('listening', () => { fs.mkdirSync(directory, { recursive: true, mode: 0o700 });fs.writeFileSync(stateFile, JSON.stringify({ pid: process.pid, port }), { mode: 0o600 });console.log(`Dashboard: http://localhost:${port}\n${addresses().join('\n')}\nReports: ${path.join(root, 'runs/experiments')}`) })
    app.server.on('error', e => { console.error(e.code === 'EADDRINUSE' ? `Port ${port} is already used. Stop the existing dashboard first.` : e.message);process.exitCode = 1 })
    app.server.on('close', () => { try { if (JSON.parse(fs.readFileSync(stateFile)).pid === process.pid) fs.unlinkSync(stateFile) } catch {} })
    process.on('SIGINT', () => app.shutdown());process.on('SIGTERM', () => app.shutdown());return
  }
  if (operation === 'status') { try { const r = await request('status');console.log(JSON.stringify({ running: true, pid: r.pid, trial: r.status, phone: Boolean(r.telemetry), observer: Boolean(r.observer) }, null, 2)) } catch (e) { console.log('Dashboard unavailable: ' + e.message) };return }
  if (operation === 'stop') { try { const s = await request('status');if (!Number.isSafeInteger(s.pid)) throw new Error('Dashboard identity unavailable');await request('shutdown');console.log('Stop requested. Motion authority revoked; current evidence is saved before exit.') } catch (e) { console.error(e.message);process.exitCode = 1 };return }
  if (operation !== 'start') throw new Error('Usage: node tools/maxbot/dashboard.cjs start|stop|status|serve')
  try { const s = await request('status');console.log(`Dashboard already running (PID ${s.pid}): http://localhost:${port}`);return } catch (e) { if (e.message.startsWith('Another')) throw e }
  fs.mkdirSync(directory, { recursive: true, mode: 0o700 });const logFile = path.join(directory, 'dashboard.log'), fd = fs.openSync(logFile, 'a', 0o600)
  const child = spawn(process.execPath, [__filename, 'serve'], { detached: true, cwd: root, env: process.env, stdio: ['ignore', fd, fd] });fs.closeSync(fd);child.unref()
  for (let n = 0; n < 40; n++) { await new Promise(resolve => setTimeout(resolve, 100));try { const s = await request('status');if (s.pid !== child.pid) throw new Error('Port owned by another dashboard');console.log(`Dashboard: http://localhost:${port}\n${addresses().join('\n')}\nLogs: ${logFile}`);return } catch {} }
  throw new Error(`Dashboard did not start; inspect ${logFile}`)
}
main().catch(e => { console.error(e.message);process.exitCode = 1 })
