# MaxBot first hardware timing session — review and next experiments

## Assessment

**The data is sufficient for a preliminary software timing baseline under the recorded conditions. It does not yet establish exposure-to-warning latency, object-appearance-to-warning latency, or input-to-physical-motor-onset latency.**

The raw dataset was preserved. Derived audit statistics are in [the companion audit JSON](2026-11-16-signal-propagation-audit.json), reproduced with `python3 tools/maxbot/review_signal_run.py SESSION.json --output AUDIT.json`. Its generated summary reproduces exactly using the original revision-1 analyzer. This review corrects interpretation and identifies analysis/recording limitations; it does not replace the original measurements with simulated values.

| Item | Recorded condition / evidence |
| --- | --- |
| Session | `6cf0c96f820f82d7c6c9b975ff23181c` |
| Input files | `maxbot-signals-2026-11-16T19-23-59.705Z-6cf0c96f.json` and matching `.md`, repository root |
| Time | 16 November 2026, 23:23:59.712–23:24:28.096 Asia/Dubai; 28.384 seconds |
| Operator clarification | Car moving on the floor; multiple objects, including a chair leg; reported battery voltage 12 V |
| Phone / app / model | Pixel 5, Android 14, MaxBot 0.6.0; YOLOv5n v7.0 COCO, 320-square input, FP16 weights/FLOAT32 interface, CPU two threads |
| Video / collection | Video enabled; optional training collection disabled |
| Drive / steering ramp | 100%/s and 250%/s |
| Thermal state | Status 2 throughout all 176 analyzed camera records; moderate thermal throttling, not a temperature in °C |
| Recording | 12,864 records; start and stop/drain received; zero reported phone/browser/server trace drops or ordinal/batch gaps |
| Identity | Recorded model hash and authenticated ESP firmware source ID match the intended baseline deployment |

Thermal status 2 is Android's moderate throttling category. This establishes the operating condition; a single session cannot quantify how much delay throttling caused. [Android thermal status definitions](https://source.android.com/docs/core/power/thermal-mitigation).

## Usable measurements

All times below are milliseconds. Phone-only measurements use one monotonic clock. Mac/ESP estimates carry clock bounds. Draw callbacks and browser rendering opportunities exclude physical display scanout.

| Endpoint | n | Median | p95 | p99 | Interpretation |
| --- | ---: | ---: | ---: | ---: | --- |
| Image available → vision decision | 171 | 176.372 | 212.529 | 308.511 | All analyzed frames after five-frame exclusion |
| Image available → Pixel indicator draw | 171 | 188.730 | 227.622 | 340.397 | Both warning and no-warning updates |
| Image available → Pixel warning draw | 31 | 185.016 | 219.247 | 243.859 | Positive frames only; nine contiguous warning episodes |
| Decision → Pixel indicator draw | 171 | 13.934 | 25.772 | 32.594 | UI dispatch/draw interval |
| Input transition → first corresponding ESP PWM API write | 62 | 63.000 | 130.000 | 146.000 | Mixed key-down/key-up and axes; clock estimate; not motor onset |
| Key-down → first corresponding PWM API write | 32 | 57.000 | 137.000 | 146.000 | Includes overlapping control inputs; not 32 isolated traction starts |
| Key-up → first corresponding PWM API write | 30 | 63.000 | 130.000 | 142.000 | First response to ramp-down, not necessarily zero output or stopped wheels |
| Manual packet creation → PWM API write | 765 | 46.000 | 107.000 | 140.000 | Packet-level samples are repeated within held inputs; maximum 260 ms |
| ESP parsed receipt → validation | 760 | 1.000 | 2.000 | 2.000 | One ESP clock |
| ESP validation → command consumption | 760 | 2.000 | 4.000 | 5.000 | Motor-task scheduling |
| Command consumption → PWM API completion | 760 | 0.000 | 0.000 | 0.000 | Maximum 1 ms; zero means below millisecond timestamp resolution |

For the 62 input-transition estimates, the median per-sample clock bound is **±23.488 ms**, and the maximum bound is **±49.090 ms**. For example, the 146 ms sample has bound ±23.660 ms, giving an interval of approximately **122.340–169.660 ms**. These are uncertainty intervals, not statistical confidence intervals. Do not describe the 63 ms median as a precise ±1 ms result or attach one sample's bound to the whole distribution.

The 62 timed input transitions comprise 32 key-down and 30 key-up events. The two unmatched transitions were W releases. Forward W key-down alone has n=12, median 57 ms; reverse S key-down has n=12, median 48 ms. These small, mixed-condition groups do not establish a direction effect. The sample includes 911 direct-WebRTC and 102 WebSocket phone-received manual packets; transport comparisons are confounded by fallback conditions and small groups.

## Where vision time goes

These seven stages use the same 171 frames and their matching phone draws. Means can be added for these matched samples; individual stage percentiles cannot.

| Stage | Median ms | Mean ms | p95 ms |
| --- | ---: | ---: | ---: |
| Image availability → packing starts, including acquisition/video staging | 5.641 | 4.988 | 10.199 |
| I420 packing / publication | 7.453 | 8.805 | 17.816 |
| Packed frame → inference starts | 5.793 | 9.021 | 25.609 |
| RGB / letterbox / normalization | 8.236 | 11.167 | 33.057 |
| YOLOv5n interpreter | 87.788 | 90.880 | 103.660 |
| Decode / NMS / rule / diagnostic box construction | 53.601 | 54.097 | 59.184 |
| Decision → Android draw | 13.934 | 14.378 | 25.772 |
| **Matched total** | **188.730** | **193.336** | **227.622** |

Calculation using unrounded means:

```text
4.988037 + 8.805452 + 9.020818 + 11.167300
  + 90.880288 + 54.096621 + 14.377591 = 193.336108 ms

(90.880288 + 54.096621) / 178.958517 = 81.01%
```

Most of the postprocessing time is inside the decoder: median **53.560 ms** from interpreter return to decoder return. The subsequent rule/diagnostic construction interval is only **0.024 ms** median. Optimizing the simple collision-zone rule is therefore a low priority. The current decoder visits 6,300 × 80 class scores per frame; proposal/objectness filtering and more efficient buffer access/native decoding are concrete profiling candidates. Preserve class scores, letterbox coordinates, NMS behavior and warning outputs when testing changes.

The vision worker produced approximately **6.24 decisions/second** over its first-to-last decision interval. Camera publication counters increased by 831 while replacement counters increased by 657: about 79.1% of those camera publications were replaced before consumption. This is the intended latest-frame policy, not trace loss. It avoids an accumulating queue but means a newly appearing obstacle can wait for a later analyzed frame. Consequently, latency for an already selected warning-positive frame does not measure latency from the instant an object first appears.

## Gaps and necessary corrections

### 1. Exposure/capture timing is unavailable

All 176 frames have `sensor_clock_comparable: false`. The recorder requires exact equality between the CPU-image timestamp and ARCore `Frame.getTimestamp()`. In this run their differences range from **−1.474 to +1.882 ms**; none satisfy equality.

ARCore documents an unspecified timebase for `getTimestamp()` and provides `getAndroidCameraTimestamp()` for correlation with Android camera metadata. The next recorder should retain that Android camera timestamp and validate its relationship to the CPU image and camera timestamp source. Simply allowing a two-millisecond tolerance would not establish clock equivalence. [ARCore frame timestamp APIs](https://developers.google.com/ar/reference/java/com/google/ar/core/Frame), [Android Image timestamp semantics](https://developer.android.com/reference/android/media/Image#getTimestamp()).

The raw sensor-to-image-available counter difference has median about 61.896 ms, but is **diagnostic only** because clock eligibility failed. It is excluded from accepted results and must not be added to a median warning latency. More runs using the current check will not fill this endpoint; the clock-correlation instrumentation needs correction first.

### 2. Mac warning presentations must be deduplicated

The browser recorded **215 presentations of 177 distinct frame IDs**, including one frame preceding the recorded vision window and **38 repeated presentations**. The current live message/snapshot handling can revisit an older frame, and the original analyzer counts repeated presentations as latency samples. Its 145 ms median decision-to-Mac statistic is therefore not a clean first-display measurement.

Taking the earliest presentation per frame ID, joining only recorded frames after warm-up, and requiring a usable host clock gives:

| Corrected first-presentation endpoint | n | Median ms | p95 ms | p99 ms | Maximum ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| Decision → Mac presentation opportunity, all updates | 170 | 97.000 | 524.000 | 630.000 | 639.000 |
| Image available → Mac presentation opportunity, all updates | 170 | 280.080 | 703.910 | 809.162 | 817.983 |
| Decision → Mac presentation opportunity, warnings only | 31 | 111.000 | 428.000 | 639.000 | 639.000 |
| Image available → Mac presentation opportunity, warnings only | 31 | 289.314 | 612.497 | 809.162 | 809.162 |

Clock bounds for the warning-only group have median ±14.742 ms and maximum ±37.664 ms. This retrospective deduplication improves the endpoint definition; it does not eliminate actual re-display/freshness problems or validate physical screen presentation. The live UI should reject older frame IDs, and the analyzer should count each first presentation once.

Mac tails are substantial even after deduplication. Browser video jitter-buffer residence had median **128.347 ms** and p95 **173.858 ms**, and its cumulative freeze counter rose from 10 to 13 across the observed browser samples. These are clues about the shared workload/network, not a causal explanation: warning messages do not traverse the video jitter buffer, so do not add that buffer value to the warning path.

### 3. Phone processing labels need refinement

The original `Phone receipt → supervisor output` result uses the tick-entry `output_produced_ns`, rather than the recorded post-tick marker. For the same 767 matched packets, receipt → `phone_decided_ns` gives **median 1.064 ms, p95 3.379 ms, p99 8.021 ms**, rather than the original median 0.606 ms. The receive marker itself is millisecond-quantized.

Likewise, the original “transmit queue” result spans from the manual tick-entry marker to transmit preparation. It can include producer work and replacement by a later periodic output; it is not an isolated executor-queue benchmark. Input-to-PWM endpoint estimates remain usable with their clock bounds. A refined collector should distinguish producer entry, producer completion, enqueue and actual transmit entry for the exact output consumed.

### 4. Physical movement onset is not recorded

There is no traction encoder or synchronized external onset video in this dataset. The operator confirms actual floor movement, which establishes that the car moved during the test, but not the timestamp of first motor/wheel movement for each input.

The file contains 2,637 raw IMU events. Mounted sensor-axis/gravity/lever-arm calibration is absent, and there are no `visual_state` events. Vibration/acceleration alone cannot establish rotor onset. Raw potentiometer readings are useful for a separate steering response experiment, but their received sample gaps reach 110 ms. Existing steering-summary counters are lifetime values: onset count rose 8→38 and censored count 10→54. Do not treat the end-of-run aggregate percentiles as a clean 30-trial session distribution.

### 5. Detection accuracy is not established

The 31 warning-triggering boxes were labelled **airplane 19, suitcase 5, TV 3, laptop 2, truck 1, person 1**. The operator reports ordinary floor obstacles including a chair leg. Without recorded source images, these labels cannot be matched reliably to real objects, and false positives/false negatives cannot be scored. A wrong class can still coincide with a real obstacle, so this is not proof that every such warning was spurious.

The next vision experiment should use one identifiable object with saved screenshots/video. Check raw image orientation, colors, letterboxing, boxes and class output against the visible scene before treating timing as evidence of reliable collision detection. COCO class detection of a whole chair is also a different task from detecting a small chair leg at the car's low camera height.

### 6. Complete recording does not mean perfect command delivery

- The 1,844 command sends and 1,551 distinct PWM-write sequences include stationary, repeated, superseded and session-boundary commands. The 294 sends without matching write evidence cannot be labelled 294 lost radio packets.
- The 16 unsuccessful manual calls comprise **10 legitimate release-to-STOP calls** and **6 expired-input calls**. The generated label “rejected_manual_packets” overstates abnormal rejection if read without this distinction.
- The MCU deadline-rejection counter increased **0→6**. MCU application-event loss remained zero and observed motor overruns remained zero. This does not establish the cause of every unmatched command.
- The general run-recorder drop counter stayed **68→68**: those recorded drops predated this session. The dedicated signal-trace drop counters are zero.
- The control-loop maximum-gap counter stayed 46.027 ms throughout; it is a lifetime maximum and does not identify an in-session 46 ms stall.

## Recommended next runs, in priority order

### A. Physical traction onset — the essential missing measurement

Use a second camera at a known high frame rate, ideally 120 or 240 fps. Keep the car on the same floor and note measured pack voltage before/after. Film a marked wheel/shaft and an identifiable input witness in the same recording.

Perform **10 isolated forward starts and 10 isolated reverse starts in separate blocks**. Use familiar short 0.3–0.5 s commands, then allow a full stop and at least three seconds before the next trial. Avoid simultaneous A/D steering inputs in these blocks. Reposition the car between blocks as needed. Keep the existing ramps, PWM limits and stop behavior fixed.

For each trial, annotate the input witness, last stationary frame and first moving frame. At 240 fps one frame is 4.167 ms; at 120 fps it is 8.333 ms. State the witness boundary accurately:

- A filmed physical key press measures physical-press → visible wheel motion, including input-device/OS handling.
- A highlighted dashboard key measures displayed-indicator → wheel motion and needs display-delay qualification.
- An electrical event witness plus optical encoder supports a more precise software-command/rotor-onset experiment.

Preserve the original video and its frame-rate metadata, plus matching session JSON and trial order. A session JSON by itself cannot supply this missing physical endpoint.

### B. Controlled vision baseline — after the timestamp/presentation corrections

Leave the car stationary and disarmed. Start with a cooled phone; note the exported thermal status, lighting and object. Use one clearly visible object, such as a whole chair or backpack, and keep its category and orientation consistent.

Make **20 separate object-entry/exit trials**, allowing about one second in the warning zone and two seconds clear between appearances. Keep the Mac dashboard visible and retain video/screenshots showing the source image, box and warning. Record false/missed warnings too. This separates vision timing/recognition from vehicle movement and supplies distinct events rather than counting adjacent positive frames as independent trials.

This requires the camera clock-correlation update for true capture timing. Source-scene appearance → warning additionally needs an observable appearance boundary; frame-to-draw timing alone will remain a different endpoint.

### C. Repeatable steering response

With traction at zero and the car's normal floor load, do **10 separate A inputs and 10 D inputs**, holding long enough to see response and returning toward center between trials. Preserve the current steering ramp; these are ramp-response trials, not instantaneous target steps. Record input IDs/trial order and use the raw ESP potentiometer series to bracket onset, while censoring substantial sensor gaps. Treat settling and initial movement as separate measurements.

### D. Optimization comparisons

First fix the measurement issues; then compare a cooled baseline against a warmed repeat with the same scene and control protocol. Report thermal state for both. Next profile a decoder optimization while keeping the model, input size, scores, NMS and warning decisions identical. After that, benchmark inference delegate/thread/quantization variants as separate configurations.

A video-load comparison is useful later, but the current video-disable path also removes the direct WebRTC input channel. Hold input transport constant across comparison legs or explicitly report that confound. Do not change video bitrate, model settings, drive ramp and cooling simultaneously.

**The highest-value next addition is synchronized physical-onset evidence. The highest-value software work is correcting clock/presentation attribution and reducing the measured decoder cost.** A longer mixed free-drive recording alone will not resolve those gaps.

## Reporting rules

Use nearest-rank quantiles and retain n, operating condition and clock bounds. The first five recorded vision frames are excluded from steady-state results; one recorded model load/warm-up interval is approximately 141.696 ms, not a measurement of complete cold app startup. Adjacent frames/packets are correlated, so the 31 warning frames and 765 packet samples are not that many independent physical trials. For this short run, p99 should be treated as an exploratory tail observation.

Preserve the JSON and original generated Markdown alongside this review. Future results should distinguish: sensor capture, CPU image availability, first warning decision, first UI draw, physical display appearance, PWM API completion, shaft rotation, wheel movement and chassis motion.

## Prepared collector correction

After this audit, collector **0.6.1-timing-fix** was prepared with the Android-camera timestamp correlation, ordered live warning frames, first-presentation analysis and refined phone-stage definitions described above. See [release instructions](../signal-propagation-v0.6.1.md). Software checks pass; capture-clock eligibility on the Pixel remains to be confirmed by a short stationary recording. The original session's exported summary remains revision 1 and has not been overwritten. No ESP reflash is needed for the collector correction.
