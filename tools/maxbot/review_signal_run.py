#!/usr/bin/env python3
"""Independent timing audit of an exported MaxBot signal session; never touches hardware."""
import argparse
import collections
import hashlib
import json
import math
from pathlib import Path
import statistics


def stats(values):
    values = sorted(values)
    if not values:
        return {'n': 0}
    return dict(n=len(values), min=values[0], p50=values[math.ceil(.5 * len(values)) - 1],
                p95=values[math.ceil(.95 * len(values)) - 1], p99=values[math.ceil(.99 * len(values)) - 1],
                max=values[-1], mean=statistics.mean(values))


def delta(event, end, start):
    return (int(event[end]) - int(event[start])) / 1_000_000


def audit(file):
    data = json.loads(file.read_text())
    records = data['records']
    phone = [r['event'] for r in records if r['source'] == 'phone']
    browser = [r['event'] for r in records if r['source'] == 'browser']
    frames = [e for e in phone if e['type'] == 'vision_frame']
    steady = {e['frame_id']: e for e in frames[5:]}
    manual = {e['packet']['session_id'] + '|' + str(e['packet']['event_id']): e for e in phone if e['type'] == 'manual_input'}
    inputs = {e['input_id']: e for e in browser if e['type'] == 'raw_input'}
    presented = [e for e in browser if e['type'] == 'browser_warning_presented']
    first = {}
    for event in presented:
        key = event['frame_id']
        if key not in first or event['presented_perf_ms'] < first[key]['presented_perf_ms']:
            first[key] = event
    corrected_mac = {}
    for warnings_only in [False, True]:
        pairs = [(f, first[k]) for k, f in steady.items() if k in first and
                 isinstance(f.get('host_clock', {}).get('host_mapped_decision_ms'), (int, float)) and
                 isinstance(f['host_clock'].get('host_clock_uncertainty_ms'), (int, float)) and
                 0 <= f['host_clock']['host_clock_uncertainty_ms'] + 1 <= 150 and (not warnings_only or f['warning'])]
        corrected_mac['warnings_only' if warnings_only else 'all_updates'] = {
            'decision_to_first_opportunity_ms': stats([b['presented_wall_ms'] - f['host_clock']['host_mapped_decision_ms'] for f, b in pairs]),
            'image_available_to_first_opportunity_ms': stats([b['presented_wall_ms'] - f['host_clock']['host_mapped_decision_ms'] + delta(f, 'decision_ns', 'image_available_ns') for f, b in pairs]),
            'clock_bound_ms': stats([f['host_clock']['host_clock_uncertainty_ms'] + 1 for f, _ in pairs])}
    groups = collections.defaultdict(list)
    for sample in data['summary']['samples']['input_to_output']:
        event = manual[sample['id']]
        raw = inputs.get(event['packet']['input_id'])
        if raw:
            groups['key_down' if raw['down'] else 'key_up'].append(sample['ms'])
            groups[raw['code'] + ('_down' if raw['down'] else '_up')].append(sample['ms'])
    matching = [manual[s['id']] for s in data['summary']['samples']['phone_handling']]
    source = next((e for e in phone if e['type'] == 'monitor_started'), {})
    classes = source.get('metadata', {}).get('baseline', {}).get('classes', [])
    return dict(
        schemaVersion=1, artifactType='maxbot_signal_session_audit', source=file.name,
        sourceSha256=hashlib.sha256(file.read_bytes()).hexdigest(), session=data['id'],
        units='milliseconds unless otherwise named',
        camera_frame_minus_cpu_image_ms=stats([delta(f, 'camera_timestamp_ns', 'sensor_timestamp_ns') for f in frames]),
        sensor_to_image_available_unvalidated_ms=stats([delta(f, 'image_available_ns', 'sensor_timestamp_ns') for f in frames]),
        eligible_sensor_frames=sum(bool(f['sensor_clock_comparable']) for f in frames),
        decoder_only_ms=stats([delta(f, 'decoded_ns', 'inferred_ns') for f in frames[5:]]),
        rule_and_box_construction_ms=stats([delta(f, 'decision_ns', 'decoded_ns') for f in frames[5:]]),
        phone_receipt_to_post_tick_marker_matched_ms=stats([(int(e['phone_decided_ns']) - int(e['packet']['phone_received_ns'])) / 1e6 for e in matching]),
        input_transition_to_pwm_by_event={k: stats(v) for k, v in groups.items()},
        input_to_pwm_clock_bounds_ms=stats([s['bound'] for s in data['summary']['samples']['input_to_output']]),
        browser_presentations=dict(total=len(presented), distinct_frame_ids=len(first), repeated_frame_presentations=len(presented) - len(first)),
        corrected_mac_first_presentations=corrected_mac,
        thermal_status_counts=dict(collections.Counter(f['thermal_status'] for f in frames)),
        manual_transports=dict(collections.Counter(e['packet']['input_transport'] for e in manual.values())),
        unsuccessful_manual_calls=[dict(deadman=k[0], fault=k[1], n=v) for k, v in collections.Counter((e['packet']['deadman'], e.get('fault', '')) for e in manual.values() if not e['accepted']).items()],
        warning_classes=dict(collections.Counter(classes[b['class_id']] for f in frames for b in f['detections'] if b['warning'])),
        scope='Software/trace audit. Sensor-clock diagnostics are unvalidated. No physical traction-onset result is inferred.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('session', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    out = args.output or args.session.with_name(args.session.stem + '-audit.json')
    result = audit(args.session)
    out.write_text(json.dumps(result, indent=2) + '\n')
    print(out)


if __name__ == '__main__':
    main()
