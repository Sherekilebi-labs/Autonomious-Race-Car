'use strict'

// Raw nanosecond timestamps stay decimal strings. Subtract before converting to double.
const ns = value => typeof value === 'string' && /^\d{1,20}$/.test(value) ? BigInt(value) : null
function delta (end, start) { const a = ns(end), b = ns(start); return a === null || b === null ? null : Number(a - b) / 1e6 }
const finite = v => typeof v === 'number' && Number.isFinite(v)
const commandKey = e => `${e.boot}|${e.session}|${e.seq}`
const inputKey = e => `${e.session_id}|${e.event_id}`

const definitions = {
  sensor_to_acquired: 'Sensor timestamp → CPU image acquired (clock eligibility checked)',
  image_to_decision: 'Image available → vision decision',
  acquisition_and_video: 'Image available → YUV packing starts (includes video staging)',
  yuv_pack: 'YUV planes → packed I420',
  inference_queue: 'Packed image → inference starts',
  preprocess: 'Letterbox / YUV → RGB / normalization',
  inference: 'YOLOv5n interpreter invocation',
  postprocess: 'Decode / confidence / class-aware NMS / warning rule',
  decision_to_phone_draw: 'Decision → Android warning draw callback',
  image_to_phone_draw: 'Image available → Android indicator draw',
  warning_capture_to_phone_draw: 'Sensor timestamp → Android collision-warning draw (warning frames only)',
  warning_image_to_phone_draw: 'Image available → Android collision-warning draw (warning frames only)',
  decision_to_browser_opportunity: 'Decision → Mac DOM presentation opportunity (clock estimate)',
  warning_image_to_browser_opportunity: 'Image available → first Mac warning presentation opportunity (clock estimate)',
  raw_input_to_packet: 'Key / pointer transition → manual packet creation',
  input_to_phone: 'Key / pointer transition → phone receipt (clock estimate)',
  phone_handling: 'Phone receipt → post-supervisor completion marker',
  phone_tx_queue: 'Consumed output tick entry → transmit preparation (includes producer work)',
  phone_sign_and_send: 'Transmit preparation / HMAC / socket submission',
  phone_to_esp: 'Phone socket submission → ESP parsed receipt (clock estimate)',
  esp_validate: 'ESP parsed receipt → authenticated / validated',
  esp_command_queue: 'ESP validated → command consumed by motor task',
  esp_output: 'ESP command consumed → PWM API write completed',
  packet_to_output: 'Manual packet creation → ESP PWM API write (clock estimate)',
  input_to_output: 'Key / pointer transition → first corresponding PWM API write (clock estimate)',
  output_to_phone_ack: 'PWM write → acknowledgement received on phone (clock estimate)'
}

function stats (samples) {
  if (!samples.length) return { n: 0, min: null, p50: null, p95: null, p99: null, max: null, mean: null, stddev: null, max_clock_bound_ms: null }
  const values = samples.map(s => s.ms).sort((a, b) => a - b)
  const mean = values.reduce((a, b) => a + b, 0) / values.length
  const q = p => values[Math.max(0, Math.ceil(p * values.length) - 1)]
  return { n: values.length, min: values[0], p50: q(.5), p95: q(.95), p99: q(.99), max: values.at(-1), mean,
    stddev: Math.sqrt(values.reduce((a, b) => a + (b - mean) ** 2, 0) / values.length), max_clock_bound_ms: Math.max(...samples.map(s => s.bound || 0)) }
}

function analyze (records) {
  const events = records.filter(r => r.source === 'phone' || r.source === 'browser').map(r => r.event)
  const frames = new Map(), draws = new Map(), inputs = new Map(), commands = new Map(), applied = new Map(), outputs = new Map()
  const samples = Object.fromEntries(Object.keys(definitions).map(k => [k, []]))
  let invalid = 0, clockCensored = 0
  const add = (name, ms, id, bound = 0) => {
    if (ms === null) return
    if (!finite(ms) || !finite(bound) || bound < 0 || bound > 150) { invalid++; return }
    // Negative cross-clock estimates remain in raw records, never silently clamped into results.
    if (ms < 0) { if (bound && ms >= -bound) clockCensored++; else invalid++; return }
    samples[name].push({ id, ms, bound, lower_ms: Math.max(0, ms - bound), upper_ms: ms + bound })
  }
  for (const e of events) {
    if (e.type === 'vision_frame') frames.set(e.frame_id, e)
    if (e.type === 'phone_warning_drawn' && !draws.has(e.frame_id)) draws.set(e.frame_id, e)
    if (e.type === 'manual_input') inputs.set(e.source_state, e)
    if (e.type === 'command_sent') commands.set(commandKey(e), e)
    if (e.type === 'applied') applied.set(commandKey(e), e)
    if (e.type === 'output_written' && e.authenticated && !outputs.has(commandKey(e))) outputs.set(commandKey(e), e)
  }
  let warm = 0
  for (const [id, f] of frames) {
    // Five analyzed frames after Start form a declared per-session warm-up window.
    if (warm++ < 5) continue
    if (f.sensor_clock_comparable) add('sensor_to_acquired', delta(f.image_acquired_ns, f.sensor_timestamp_ns), id)
    for (const [name, end, start] of [
      ['image_to_decision', 'decision_ns', 'image_available_ns'], ['acquisition_and_video', 'pack_start_ns', 'image_available_ns'],
      ['yuv_pack', 'published_ns', 'pack_start_ns'], ['inference_queue', 'inference_start_ns', 'published_ns'],
      ['preprocess', 'prepared_ns', 'inference_start_ns'], ['inference', 'inferred_ns', 'prepared_ns'], ['postprocess', 'decision_ns', 'inferred_ns']
    ]) add(name, delta(f[end], f[start]), id)
    const d = draws.get(id)
    if (d) {
      add('decision_to_phone_draw', delta(d.draw_ns, f.decision_ns), id)
      add('image_to_phone_draw', delta(d.draw_ns, f.image_available_ns), id)
      if (f.warning) {
        add('warning_image_to_phone_draw', delta(d.draw_ns, f.image_available_ns), id)
        if (f.sensor_clock_comparable) add('warning_capture_to_phone_draw', delta(d.draw_ns, f.sensor_timestamp_ns), id)
      }
    }
  }
  const firstInputs = new Map(), matchedPackets = new Set()
  for (const [key, output] of outputs) {
    const sent = commands.get(key), ack = applied.get(key)
    if (!sent || !['DRIVE_MANUAL', 'STOP'].includes(sent.mode) || sent.pwm !== output.pwm) continue
    const input = inputs.get(sent.source_state)
    if (!input) continue
    if (!input.accepted && input.packet?.deadman) continue
    const packet = input.packet, id = inputKey(packet)
    if (matchedPackets.has(id)) continue
    matchedPackets.add(id)
    const espBound = output.clock_uncertainty_ms, hostBound = packet.host_clock_uncertainty_ms
    add('phone_handling', delta(input.phone_decided_ns, packet.phone_received_ns), id)
    add('phone_tx_queue', delta(sent.phone_send_ns, sent.policy_output_ns), id)
    add('phone_sign_and_send', delta(sent.phone_socket_sent_ns, sent.phone_send_ns), id)
    if (finite(espBound)) {
      add('output_to_phone_ack', delta(output.phone_received_ns, output.mapped_phone_output_ns), id, espBound)
      if (finite(hostBound)) add('packet_to_output', delta(output.mapped_phone_output_ns, packet.mapped_event_ns), id, espBound + hostBound)
      if (ack) add('phone_to_esp', delta(output.mapped_phone_output_ns, sent.phone_socket_sent_ns) === null ? null : delta(output.mapped_phone_output_ns, sent.phone_socket_sent_ns) - (output.output_ms - ack.rx_ms), id, espBound)
    }
    if (ack) {
      add('esp_validate', ack.validated_ms - ack.rx_ms, id)
      add('esp_command_queue', ack.applied_ms - ack.validated_ms, id)
      add('esp_output', output.output_ms - ack.applied_ms, id)
    }
    if (Number.isSafeInteger(packet.input_id) && finite(hostBound) && finite(espBound)) {
      const origin = `${packet.session_id}|${packet.input_id}`
      const time = ns(output.mapped_phone_output_ns)
      if (time !== null && (!firstInputs.has(origin) || time < firstInputs.get(origin).time)) firstInputs.set(origin, { time, packet, output, id })
    }
  }
  for (const { packet, output, id } of firstInputs.values()) {
    add('input_to_output', delta(output.mapped_phone_output_ns, packet.mapped_input_ns), id, output.clock_uncertainty_ms + packet.host_clock_uncertainty_ms)
    add('input_to_phone', delta(packet.phone_received_ns, packet.mapped_input_ns), id, packet.host_clock_uncertainty_ms)
    add('raw_input_to_packet', finite(packet.packet_perf_ms) && finite(packet.input_perf_ms) ? packet.packet_perf_ms - packet.input_perf_ms : null, id)
  }
  const firstPresentations = new Map(), warmFrames = new Set([...frames.keys()].slice(0, 5))
  for (const e of events) if (e.type === 'browser_warning_presented') {
    const previous = firstPresentations.get(e.frame_id)
    if (!previous || e.presented_perf_ms < previous.presented_perf_ms) firstPresentations.set(e.frame_id, e)
  }
  for (const e of firstPresentations.values()) {
    if (warmFrames.has(e.frame_id)) continue
    const f = frames.get(e.frame_id), clock = f?.host_clock
    if (clock && finite(clock.host_mapped_decision_ms) && finite(clock.host_clock_uncertainty_ms)) {
      const elapsed = e.presented_wall_ms - clock.host_mapped_decision_ms
      add('decision_to_browser_opportunity', elapsed, e.frame_id, clock.host_clock_uncertainty_ms + 1)
      const vision = delta(f.decision_ns, f.image_available_ns)
      if (f.warning && vision !== null) add('warning_image_to_browser_opportunity', elapsed + vision, e.frame_id, clock.host_clock_uncertainty_ms + 1)
    }
  }
  const rawInputs = events.filter(e => e.type === 'manual_input')
  const trace = records.filter(r => r.source === 'phone_batch').map(r => r.event)
  return { analysis_revision: 2, quantiles: 'nearest rank: x[ceil(p*n)-1]; five initial vision frames excluded; first Mac presentation per frame',
    metrics: Object.fromEntries(Object.entries(samples).map(([k, v]) => [k, { label: definitions[k], ...stats(v) }])), samples,
    counts: { records: records.length, vision_frames: frames.size, warning_frames: [...frames.values()].filter(f => f.warning).length,
      vision_warmup_frames: Math.min(5, frames.size), phone_draws: draws.size, manual_packets: rawInputs.length,
      rejected_manual_packets: rawInputs.filter(e => !e.accepted).length, command_sends: commands.size,
      command_applied: applied.size, pwm_writes: outputs.size, matched_packets: matchedPackets.size,
      input_transitions_with_output: firstInputs.size, commands_without_output_evidence: [...commands.keys()].filter(k => !outputs.has(k)).length,
      phone_trace_dropped: Math.max(0, ...trace.map(e => e.dropped || 0)), invalid_intervals: invalid, clock_censored_intervals: clockCensored },
    actual_traction_motion: { measured: false, reason: 'No encoder / current / external video onset measurement supplied. PWM API completion is not physical motor movement.' },
    limitations: ['Sensor timestamp comparison requires REALTIME source, identical CPU/AR timestamps and plausible age; exposure and display scanout have no external validation.',
      'Mac presentation opportunities and Android draw callbacks exclude physical display scanout.',
      'No-warning frames do not demonstrate a clear path. COCO has no dedicated traffic-cone class.',
      'Missing output evidence may mean supersession, rejection, event loss or session boundaries; it is not automatically packet loss.',
      'Cross-device estimates include the exported clock bounds; negative or >150ms-bound estimates are censored and retained in the raw data.'] }
}

function markdown (report) {
  const s = report.summary, fmt = v => finite(v) ? v.toFixed(3) : '—'
  return `# MaxBot signal propagation results\n\nSession: ${report.id}\n\nStarted: ${report.started_at}; ended: ${report.ended_at}. Status: **${report.status}**.\n\n` +
    `Raw records and metadata are in the matching JSON export. These are software endpoints measured on the connected devices; physical traction onset remains unmeasured.\n\n` +
    `## Results\n\n| Stage | n | p50 ms | p95 ms | p99 ms | Max ms | Max clock bound ±ms |\n| --- | ---: | ---: | ---: | ---: | ---: | ---: |\n` +
    Object.values(s.metrics).map(m => `| ${m.label} | ${m.n} | ${fmt(m.p50)} | ${fmt(m.p95)} | ${fmt(m.p99)} | ${fmt(m.max)} | ${fmt(m.max_clock_bound_ms)} |`).join('\n') +
    `\n\nA dash means no eligible measurement. ${s.quantiles}. Percentiles of stages must not be added to obtain an end-to-end percentile.\n\n` +
    `## Completeness\n\n\`\`\`json\n${JSON.stringify({ ...s.counts, ...report.completeness }, null, 2)}\n\`\`\`\n\n` +
    `## Calculations\n\nSame clock: Δms = (end_ns − start_ns) / 1,000,000. Subtract integer nanoseconds before conversion.\n\nFour-timestamp mapping: offset = ((t2 − t1) + (t3 − t4)) / 2; uncertainty = ((t4 − t1) − (t3 − t2)) / 2 + 1 ms, with the runtime's age/drift allowance. Cross-device intervals carry the sum of relevant clock bounds.\n\nCapture → draw = sensor → image available + acquisition/video staging + YUV pack + inference queue + preprocess + inference + postprocess + UI dispatch/draw.\n\nInput → PWM write = browser scheduling/ramp + input transport + phone validation/supervisor + transmit queue + signing/socket + Wi-Fi/ESP parsing + ESP validation + motor-task queue + PWM API write.\n\nInput → actual motor onset = input → PWM write + PWM phase / H-bridge / electrical current rise / static friction / mechanical load. The last terms require external evidence.\n\n` +
    `## Limits and next measurements\n\n${s.limitations.map(t => '- ' + t).join('\n')}\n- ${s.actual_traction_motion.reason}\n- Completion means all received traces were flushed; compare gaps and losses before interpreting results.\n\n` +
    `## Optimization experiments\n\nCompare matched sessions with video enabled/disabled, collection disabled/enabled, CPU versus a validated GPU delegate, lower model resolution, and steady phone temperature. Keep model, thresholds, Wi-Fi, drive ramp, supply and mechanical load fixed for each comparison. Inspect queue delay before optimizing arithmetic. Preserve manual freshness and watchdog limits. See docs/autonomy/signal-propagation.md for the full procedure and the physical-onset protocol.\n`
}

module.exports = { analyze, markdown, delta, stats }
