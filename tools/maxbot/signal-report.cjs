#!/usr/bin/env node
'use strict'

// Rebuild analysis from a final JSON or recover all complete lines of a crash journal.
const fs = require('node:fs'), path = require('node:path')
const { analyze, markdown } = require('../../controller/node-js/maxbot/services/signal-analysis')
const input = process.argv[2], destination = process.argv[3]
if (!input) { console.error('Usage: node tools/maxbot/signal-report.cjs SESSION.json[l] [OUTPUT.md]'); process.exit(2) }
const raw = fs.readFileSync(input, 'utf8')
let report
if (input.endsWith('.jsonl')) {
  const lines = raw.split('\n'), records = []
  for (let i = 0; i < lines.length; i++) {
    if (!lines[i].trim()) continue
    try { records.push(JSON.parse(lines[i])) } catch (error) { if (i !== lines.length - 1) throw error }
  }
  const start = records.find(r => r.source === 'session' && r.event.type === 'start')?.event
  report = { schemaVersion: 1, artifactType: 'maxbot_signal_propagation', id: start?.id || 'recovered', status: 'incomplete',
    started_at: start?.started_at || null, ended_at: new Date().toISOString(), metadata: start?.metadata || {}, records,
    completeness: { recovery: 'Recovered complete journal lines; phone drain and browser continuity are unverified' } }
} else { report = JSON.parse(raw); if (report.artifactType !== 'maxbot_signal_propagation' || !Array.isArray(report.records)) throw new Error('Expected a signal propagation session export') }
report.summary = analyze(report.records)
const output = destination || path.join(path.dirname(input), path.basename(input).replace(/\.jsonl?$/, '') + '-results.md')
fs.writeFileSync(output, markdown(report), { mode: 0o600 })
if (input.endsWith('.jsonl')) fs.writeFileSync(output.replace(/\.md$/, '') + '-recovered.json', JSON.stringify(report), { mode: 0o600 })
console.log(output)
