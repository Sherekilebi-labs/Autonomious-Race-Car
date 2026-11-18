#!/usr/bin/env node
'use strict'
// STDIO is JSON-RPC only. The dashboard remains the single robot/recording owner.
const fs = require('node:fs'), path = require('node:path'), readline = require('node:readline')
const { tokenFor } = require('./services/automation-api')
const root = path.resolve(__dirname, '../../..')
const config = process.env.MAXBOT_CONFIG || path.join(root, 'maxbot.runtime.json')
const key = process.env.MAXBOT_PAIRING_KEY || JSON.parse(fs.readFileSync(config, 'utf8')).network.pairingKey
if (typeof key !== 'string' || key.length < 32) throw new Error('Configure the private MaxBot pairing key')
const port = Number(process.env.MAXBOT_PORT || 8081)
if (!Number.isInteger(port) || port < 1024 || port > 65535) throw new Error('Invalid local dashboard port')
const object = properties => ({ type: 'object', properties, additionalProperties: false })
const textField = { type: 'string' }
const definitions = [
  ['status', 'Read connections, telemetry, experiment status and background jobs.', object({})],
  ['prepare', 'Prepare one bounded attended trial. Does not move the car.', { ...object({ recipe: { type: 'string', enum: ['camera_clock', 'warning', 'video', 'forward_onset', 'reverse_onset', 'steering', 'speed_stop', 'turn', 'slalom'] }, parameters: object({pwm:{type:'integer',minimum:1,maximum:140},pulse_ms:{type:'integer',minimum:20,maximum:1000},allow_severe_thermal:{type:'boolean'}}), metadata: object({ notes: textField, battery_v: { type: 'number' } }) }), required: ['recipe'] }],
  ['start', 'Execute the prepared trial. Requires an enabled visible attended dashboard, stopped USB vehicle, and recording observer.', { ...object({ prepared_id: textField }), required: ['prepared_id'] }],
  ['abort', 'Cancel a trial immediately and request stop/disarm.', object({})],
  ['list', 'List saved local experiment IDs.', object({})],
  ['read', 'Read a saved experiment report or manifest.', { ...object({ id: textField, name: { type: 'string', enum: ['summary.json', 'manifest.json', 'report.md', 'analysis.json', 'analysis.md'] } }), required: ['id'] }],
  ['preview', 'Get the latest observer snapshot with its source timestamp; this is not continuous video.', object({})],
  ['analyse', 'Analyse one saved bundle; optionally extract observer tracks with a measured camera calibration.', { ...object({ id: textField, calibration: { type: 'object' } }), required: ['id'] }],
  ['policy_search', 'Fit reviewed dynamics and simulate bounded cone-slalom controller candidates on held-out trajectories. Never deploys automatically.', { ...object({ dataset: textField, seed: { type: 'integer' }, candidates: { type: 'integer', minimum: 1, maximum: 100 } }), required: ['dataset'] }],
  ['train_detector', 'Train/export the YOLOv5n compatibility detector from a reviewed session-split dataset while stopped.', { ...object({ dataset: textField, epochs: { type: 'integer', minimum: 1, maximum: 100 } }), required: ['dataset'] }],
  ['job', 'Read a background analysis/training job and its bounded log.', { ...object({ id: textField }), required: ['id'] }],
  ['plan', 'Plan the frozen course using the existing validated stopped-state planner.', { ...object({ request: { type: 'object' } }), required: ['request'] }],
  ['stage', 'Stage a compatible artifact-set begin/chunk packet while stopped. Verify acknowledgement on dashboard.', { ...object({ packet: { type: 'object' } }), required: ['packet'] }],
  ['activate', 'Activate a staged artifact set while stopped. Never arms or starts a run; verify applied hashes.', { ...object({ packet: { type: 'object' } }), required: ['packet'] }]
].map(([operation, description, inputSchema]) => ({ name: `maxbot_${operation}`, description, inputSchema }))
async function dispatch (m) {
  if (m.method === 'initialize') return { protocolVersion: '2024-11-05', capabilities: { tools: { listChanged: false } }, serverInfo: { name: 'maxbot-experiments', version: '0.7.0' } }
  if (m.method === 'ping') return {}
  if (m.method === 'tools/list') return { tools: definitions }
  if (m.method === 'tools/call') {
    const tool = definitions.find(t => t.name === m.params?.name)
    if (!tool) throw new Error('Unknown tool')
    try {
      const response = await fetch(`http://127.0.0.1:${port}/automation`, { method: 'POST', headers: { Authorization: `Bearer ${tokenFor(key)}`, 'Content-Type': 'application/json' }, body: JSON.stringify({ operation: tool.name.slice(7), args: m.params.arguments || {} }), signal: AbortSignal.timeout(120000) })
      const body = await response.json(); if (!response.ok || body.error) throw new Error(body.error || 'Dashboard request failed')
      const result = body.result
      if (tool.name === 'maxbot_preview' && result?.jpeg) return { content: [{ type: 'text', text: JSON.stringify({ ...result, jpeg: undefined }) }, { type: 'image', data: result.jpeg, mimeType: 'image/jpeg' }] }
      return { content: [{ type: 'text', text: typeof result === 'string' ? result : JSON.stringify(result) }] }
    } catch (e) { return { isError: true, content: [{ type: 'text', text: e.message==='fetch failed'?`Dashboard unavailable on localhost:${port}. Start the 0.7 dashboard with node tools/maxbot/dashboard.cjs start.`:e.message }] } }
  }
  const error = new Error('Method not found'); error.code = -32601; throw error
}
function start () {
  const input = readline.createInterface({ input: process.stdin, crlfDelay: Infinity })
  input.on('line', async line => {
    let m
    try { if (Buffer.byteLength(line) > 256 * 1024) throw new Error('Request too large'); m = JSON.parse(line); if (m.jsonrpc !== '2.0') throw new Error('Invalid JSON-RPC'); if (m.id === undefined) return; const result = await dispatch(m); process.stdout.write(JSON.stringify({ jsonrpc: '2.0', id: m.id, result }) + '\n') }
    catch (e) { process.stdout.write(JSON.stringify({ jsonrpc: '2.0', id: m?.id ?? null, error: { code: e.code || -32600, message: e.message } }) + '\n') }
  })
}
if (require.main === module) start()
module.exports = { dispatch, definitions }
