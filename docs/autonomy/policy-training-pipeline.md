# Cone-slalom policy training pipeline

Status: experimental design only, revised **2026-11-13** after final user decisions. Implements physics-informed initial parameters followed by improvement from repeated real runs. Basic dynamics measurements, runtime control and Mac reference planning belong to task 1; automated model fitting, detector fine-tuning and policy search tooling belong to the lower-priority task 2. See [the system plan](implementation-plan.md) for authoritative execution, timestamps, geometry and configuration contracts, and [the Sol handoff](sol-implementation-handoff.md) for implementation order.

## 1. What is learned and where

The phone performs inference, estimation and feedback at an initial 50 Hz using the normal rear 1× camera. The Mac stores run artifacts, reviews outcomes, identifies dynamics, proposes the next parameters and computes the complete race path/speed profile while stopped. The phone follows that validated reference over Wi-Fi and is the sole sender of motion commands. Training is between runs, not in the phone's control thread. Own-footage training means using the robot's recordings as data; it does not require training YOLO on the Pixel.

Initially optimize a small parameter vector for a geometric planner/tracker/speed controller. Keep YOLO weights fixed while comparing policy candidates, and keep policy parameters fixed while evaluating a detector change. Combine independently validated versions only after each has evidence. This makes changes attributable and avoids treating a perception regression as a steering-gain problem.

Use a stochastic optimizer to propose parameter vectors between runs. Record the random seed and freeze the selected vector during each run. A fixed candidate is reproducible but the policy family improves across experiments. Random motor perturbations at every control tick add collision risk and make useful comparisons harder; they are not needed to obtain an adaptive policy.

## 2. Course and validity contract

Four 200 mm diameter, 150 mm high bright-orange cones stand in a straight row with 500 mm edge gaps, or 700 mm center spacing. Start dead-center on the row, front bumper 1 m from the first near edge. Pass the car right, left, right, left. Use one-sided scout → stop/freeze map → Mac plans → manual reposition → localization verification → timed run, as commanded on the Mac. Preserve the camera mount and localization session; a reset or unexplained map shift invalidates the reference.

Score from the phone's accepted timed-run start to full-body clearance of cone 4, including launch delay. Save physical-motion start, every cone crossing, finish and braking completion separately. Crossing timestamps are interpolated estimates with uncertainty; do not confuse delayed detection arrival with the instant of passage.

A scored run requires correct order/side, all four cones cleared, no contact, valid localization, and no takeover/fault. Any cone contact discards the **race score**, but the recording and failure labels remain in the dataset. A stopped, aborted or unfinished attempt remains an unsuccessful attempt. Without a contact sensor and full external visibility, contact-free status requires operator review; uncertain attempts do not become approved clean runs automatically.

## 3. Run artifact contract

Each immutable run directory contains:

```text
run-<id>/
  manifest.json              policy/model/config/firmware/app hashes, seed, device and course metadata
  course.json                observed map, priors, map epoch/frame, geometry and empirical uncertainty
  reference.json             complete path/speed/braking tail, dependency hashes and feasibility report
  vehicle-model.json         measured dynamics revision, validity range and fit evidence
  video/                     clean camera recording or sampled frames with source timestamps
  imu.jsonl                  gyro, accelerometer, timestamps, bias/gravity calibration
  observations.jsonl         image observations, metric-center estimates, confidence and error bounds
  state.jsonl                VIO/predicted pose, velocity, steering and uncertainty
  commands.jsonl             policy outputs, source IDs, command/motion epoch/intent IDs and expiry
  actuator.jsonl             MCU actual steering, logical PWM, applied timestamps and faults
  timing.jsonl               clock sync, pipeline traces, thermal/network/deadline metrics
  review.json                outcome, contact/near-miss labels, event intervals and reviewer
  summary.json               validity, time, minimum estimated clearance and experiment metrics
```

Choose a more compact binary storage format if profiling requires it; preserve the same schema/meaning and export JSON for inspection. Keep full-rate scalar telemetry and a bounded image/encoding workload. Count dropped frames/events and capture-time alignment. Missing telemetry must not silently interpolate into confident training targets.

Save actual course coordinates, floor/lighting, mount/calibration identity, tire state, manually measured pre-run pack voltage when available, and phone thermal state. Battery monitoring remains disabled. Do not use the target 12 V as an observed battery measurement. Separate environment metadata from optimizer-controlled parameters.

Record actual lens/stream/crop/intrinsics identity, map-producing detector version, reference hash and all source clock domains. Distinguish measured pose error bounds from fitted or assumed uncertainty. Do not manufacture ARCore covariance or let duplicate observations masquerade as independent geometric evidence.

Contact review is on the Mac: clean, contact, wrong side, abort, DNF, or uncertain, with time intervals and notes. IMU impulses, cone movement, abrupt optical flow and unexplained speed loss can nominate review intervals. Mild touches may leave none of those signals, and drivetrain vibration can create false positives. External slow motion is useful for initial validation and ambiguous runs, not mandatory for every successful collection session.

## 4. Stage A: identify the vehicle model

Use the working actuator calibration as the starting point. Do not relearn pin direction or replace the seven-point steering table with an arbitrary network. Task 1 needs a reviewed, conservative initial speed/PWM table and steering/braking measurements; it must not wait for the task-2 automated fitter described here. This stage improves those initial tables and their uncertainty using repeated maneuvers.

1. Log stationary camera/IMU and steering noise, sensor alignment, gravity and gyro bias. Verify VIO distance against the measured cone spacing and a measured straight drive.
2. Apply bounded steering steps while supported, then representative low-speed floor steps. Fit onset delay, rate limits, first-order lag, asymmetry/backlash and residual steering error. Record the loaded response separately from the saved 60 ms traversal prior.
3. With sufficient clear floor, measure forward PWM steps and ramps using VIO speed and aligned acceleration. Fit starting versus sustaining deadband, steady-speed feedforward and acceleration response. A starting threshold of 120 does not determine speed at PWM 150.
4. Measure stop/coast/verified-brake behavior and stopping distance at controlled speeds. Confirm reverse-neutral behavior in manual mode, without adding reversing to the forward slalom.
5. Fit curvature and lateral response from broad low-speed turns: compare gyro yaw rate to `v*tan(delta)/L`, identify steering bias and slip, then increase speed only within measured grip.

A useful initial model is:

```text
x_dot = v cos(psi)
y_dot = v sin(psi)
psi_dot = v tan(delta) / L + slip_residual
v_dot = f_forward_pwm(u, v, conditions) or f_brake(v)
delta_dot = limited((delta_command_delayed - delta) / tau_steer)
```

Fit parameters with bounded robust regression and retain residual distributions and uncertainty. Validate on whole held-out maneuvers. Account for VIO velocity filtering and observation delay when fitting actuator lag; estimator lag must not silently become motor lag. Incorporate measured command latency/jitter and missing observations; a zero-delay simulator is an inappropriate training environment for this system.

## 5. Stage B: establish a feasible conservative baseline

Solve the observed course on the Mac using full vehicle/cone geometry, calibrated wheel limits and measured steering rate. Optimize approach heading and longitudinal pass location, then retime for acceleration, braking and steering response. Validate the continuous swept path and phone-compatible reference artifact. The current zero-heading quintic waypoint family fails the nominal 700 mm layout with its existing offset/margin, so do not optimize controller gains around that infeasible reference. Failure of this family or a local optimizer does not establish that the course itself is impossible.

Use exact oriented-footprint/swept-path clearance checks and a validated uncertainty margin. Keep actuator limits, contact constraints, operator supervision and stop logic outside the tunable vector. Unknown grip or stopping measurements are not high-speed defaults.

Check metric cone centers against measured geometry and independent viewpoints. A bounding-box bottom is often a visible rim, not the ground center; fit a calibrated base model and distinguish observed from inferred points. Reject grazing/clipped floor projections. The 99.24 mm camera height makes distant ground-range estimates sensitive to pitch. One-sided scouting supplies closer views without requiring an already feasible racing weave. Keep the initial 150 mm clearance explicit until changed through reviewed geometric evidence; never reduce it silently to obtain a solution.

Begin with a conservative speed profile and produce a repeatable correct-order baseline. The existing repository's 20-clean-run gate may be retained initially as commissioning evidence, while recognizing that 20 clean trials do not establish a near-zero collision probability. Report observed failure rate with uncertainty, and obtain further evidence before increasing speed.

## 6. Stage C: optimize offline

Use recorded observations for detector/localization analysis and exact controller reproduction. Replaying the same video can reveal tracking, timing and numerical defects. It cannot establish how a changed policy would have moved the car: different commands change the subsequent observations. Evaluate changed actions in a simulator based on the identified dynamics, then confirm on the physical course.

Run closed-loop simulated trials with randomized steering lag, drive deadband, grip/slip, launch position/heading, cone placement, VIO drift, missed/occluded cones, image/communication delay and measured clock uncertainty. Center randomization on observed ranges, not arbitrary large distributions. Reject any parameter candidate violating steering, speed, stopping, collision or timing constraints before it reaches the car.

Initial tunable groups:

| Group | Parameters |
|---|---|
| Reference path | Lateral clearance above the fixed minimum, longitudinal pass phase, entry/exit shape and curvature smoothness |
| Tracking | Pure-pursuit base/speed-dependent lookahead and gyro yaw-rate damping |
| Timing / dynamics | Identified feedforward compensation for actuator lag and expected command-application delay |
| Speed | Straight/curvature speed scale, acceleration profile, braking onset and limited speed-feedback gains |

Reduce the first search to about 5–8 influential parameters. Start with the system plan's explicit pure-pursuit equation; do not double-count reference curvature by adding a second steering feedforward term. Identify model parameters from data rather than letting race-time optimization freely distort physical measurements. Begin with bounded CMA-ES simulation search, then a smaller constrained Bayesian search for expensive real runs when sufficient feasibility data exists. Other optimizers are optional comparisons, not parallel implementation requirements. A neural residual is a later experiment after the baseline and data quality are established.

## 7. Loss, constraints and invalid runs

The task objective is **minimum time subject to no contact and correct completion**. A finite “collision penalty” added to time can reward a fast crash, so collision and completion are separate constraints.

For parameter vector `theta`, estimate clean completion probability and the distribution of valid completion times. Select among candidates meeting the experiment's approved conservative reliability criterion, then minimize time. Keep that reliability criterion explicit and revise confidence using actual trials; no finite sample can prove zero future collisions.

For valid trials, a useful training surrogate is:

```text
J_valid = T / T_baseline
          + lambda_track * mean((cross_track_error / e_scale)^2)
          + lambda_margin * mean(softplus((margin_target - clearance) / d_scale)^2)
          + lambda_steer * mean((steering_rate / rate_scale)^2)
          + lambda_pwm * mean((pwm_change / pwm_scale)^2)
```

Use physical units and normalizing scales, then select small secondary weights so smoothness does not dominate race time. The hard minimum clearance, steering/rate bounds, stopping requirements, validity and deadlines remain constraints, not terms the optimizer can trade away. Estimated clearance includes state/map uncertainty; human contact labels override optimistic model estimates.

Train the feasibility/failure surrogate on **every attempt**, including contacts, DNF, takeover and uncertainty. Completion-time regression uses valid completed trials; DNF may supply censored progress/time information, never a fake fast completion. Keep a separate final leaderboard based on actual clean elapsed time, independent of the shaping loss. Track manual takeover and abort rates so failed trials cannot disappear through selective review.

An initial constrained Bayesian optimizer can maximize expected improvement in clean time weighted by conservative feasible-completion probability, inside a bounded neighborhood of the best validated parameters. If all candidates lack credible feasibility evidence, gather conservative baseline/model data instead of exploring faster parameters blindly.

## 8. Stage D: real-run improvement loop

1. Pin the baseline detector, course and hardware calibration. Scout/freeze a new map if the current one is invalid. The Mac proposes a parameter vector and seed from the offline-vetted neighborhood.
2. Recompute and validate the complete reference/speed profile for the candidate and map. Show the parameter diff and constraint margins; stage the compatible artifact set, activate only stopped/disarmed, and confirm the phone's applied hashes.
3. Reposition manually, verify launch pose/heading, map/reference identity and visible-cone agreement, then start from the Mac. A configuration or localization change returns to validation/planning instead of reusing a stale reference.
4. Execute with parameters frozen. Log the complete pipeline; takeover, stale-state and stop behavior remain independent of optimizer decisions.
5. Review contact/completion on the Mac. Annotate failure intervals even when the run is discarded from racing results.
6. Update dynamics/feasibility models and propose the next candidate. Change a small number of parameters at first. Revert to the last validated version after unexplained instability.
7. Interleave baseline and candidate trials, randomizing order. Repeat candidates across pack state, phone temperature, and representative indoor light to separate improvement from environmental drift.

No candidate is promoted solely because it has one exceptional time. Report repeated clean-run times, contact/abort rate, near-clearance estimates and uncertainty, plus full-load latency. Increasing speed must not hide degraded deadline performance. Persist candidate lineage and unsuccessful trials.

## 9. Stage E: detector data and later fine-tuning

Collect clean onboard footage across scout, launch, close passes, all four cones, empty hallway, motion blur, partial occlusion and indoor lighting changes. Include orange reflections and other orange objects as negatives. Label cone bounding boxes, visibility/clipping and visible base features. Distinguish directly visible rim points from inferred metric center labels derived from calibrated geometry/multiple views; retain their provenance and uncertainty. Do not label a hidden center as an exactly observed pixel or assume a box bottom supplies one. Base-feature/keypoint prediction is a later measured option if boxes plus calibrated geometry are insufficient.

Bootstrap annotations using a prompted/open-vocabulary teacher on the Mac or training host, orange-color proposals, multi-view consistency and a manually labeled seed set. Use these as proposals with confidence and provenance, not ground truth. A generic detector not showing a cone class is not evidence that cones are absent. Retain manual labels for validation and audit difficult samples before accepting pseudo-labels. Avoid using the student model's own errors as its only training target.

Split by complete recording session/day/course placement, not neighboring video frames. Separate train, development and locked test sessions before optimization. Save independent test data across mount, blur, distance and light conditions. Select model/export choices on development data; use the locked test set for final promotion checks so repeated model selection does not train indirectly on the test set.

Establish available YOLOv5n adapter/weights as the compatibility baseline, then fine-tune the one-class YOLO26n 320 candidate. Do not presume either already contains trained cone weights. Check recall by distance/occlusion, false positives and metric-center error, not only mAP. Test GPU FP16/float-I/O first; add CPU INT8 when contention/delegation measurements warrant it, calibrating INT8 on representative training-only frames. Investigate YOLO11n only if the challenger fails compatibility, accuracy or sustained timing. Validate the **exported** model, decoder, thresholds and Pixel pipeline. Quantization-aware training is a later option if post-training quantization loses important cone geometry.

Use uncertainty sampling and disagreement to select new human-review frames. Temporal tracking can reduce label effort but does not eliminate review. If a detection model remains fixed during policy experiments, record that explicitly. Promote a new detector independently, then repeat the policy baseline to detect changes in geometry or timing.

## 10. Optional learned policy residual and promotion

After collecting enough diverse, labeled closed-loop runs, consider a small residual that adjusts bounded feedforward/tracker parameters from fused state and cone geometry. Start from the physics controller. Supervise against a validated offline optimizer or expert corrections; do not treat every logged action from a failed run as a desirable target. A sequence model needs deliberate handling of delayed observations and distribution shift.

Keep residual output bounded, pass it through the same authority/limit layer, and benchmark its actual inference cost. Shadow evaluation and closed-loop simulation precede conservative real-run trials. Compare the residual with the tuned heuristic on clean completion rate and time; retain the simpler policy unless improvement is supported. End-to-end image-to-PWM learning is unnecessary for the first working slalom system.

Promotion artifacts are versioned policy JSON, compatible map/reference and vehicle-model versions, model manifest/weights when changed, dataset/model provenance, training/search configuration, candidate seed and lineage, offline evaluation, repeated real-run results and sustained Pixel timing. Deploy from the Mac while stopped, retain rollback, and never resume a run merely because an improved policy was uploaded. A detector or geometry change requires map/reference revalidation before another scored run.
