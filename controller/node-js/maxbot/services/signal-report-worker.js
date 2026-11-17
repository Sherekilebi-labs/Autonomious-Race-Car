'use strict'

// Final analysis can parse tens of MB. Keep it off the control/heartbeat event loop.
const { parentPort, workerData: a } = require('node:worker_threads')
const fs = require('node:fs'), crypto = require('node:crypto')
const { analyze, markdown } = require('./signal-analysis')
try {
  const records = fs.readFileSync(a.journal, 'utf8').trim().split('\n').filter(Boolean).map(line => JSON.parse(line))
  const summary = analyze(records), c = a.completeness
  const complete = c.phone_started && c.phone_drained && !c.phone_batch_gaps && !c.phone_ordinal_gaps && !c.browser_ordinal_gaps && !c.browser_dropped && !c.server_dropped && !summary.counts.phone_trace_dropped && !c.phone_error && ['operator_stop', 'duration_limit', 'phone_stopped'].includes(c.end_reason)
  const report = { schemaVersion: 1, artifactType: 'maxbot_signal_propagation', id: a.id, status: complete ? 'complete' : 'incomplete',
    started_at: a.started_at, ended_at: a.ended_at, metadata: a.metadata, completeness: c, summary, records }
  const data = JSON.stringify(report)
  fs.writeFileSync(a.file + '.part', data, { mode: 0o600 }); fs.renameSync(a.file + '.part', a.file)
  fs.writeFileSync(a.md, markdown(report), { mode: 0o600 })
  parentPort.postMessage({ status: report.status, counts: summary.counts, sha256: crypto.createHash('sha256').update(data).digest('hex') })
} catch (error) { parentPort.postMessage({ error: error.message }) }
