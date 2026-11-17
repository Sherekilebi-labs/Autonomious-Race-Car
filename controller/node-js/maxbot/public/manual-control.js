'use strict'
const MANUAL_PROFILE = Object.freeze({ inputMs: 300, linkMs: 500, clockBoundMs: 75, driveMinimumPwm: 200 })

// Signed, normalized axes keep ramp timing independent of PWM/steering calibration.
class ManualRamp {
  constructor () { this.drive = 0; this.steering = 0 }
  reset () { this.drive = this.steering = 0 }
  step (keys, seconds, driveRate = 100, steeringRate = 250, driveMinimum = 0) {
    if (!Number.isFinite(seconds) || seconds < 0 || seconds > MANUAL_PROFILE.inputMs / 1000 ||
        !Number.isFinite(driveMinimum) || driveMinimum < 0 || driveMinimum > 1 ||
        ![driveRate, steeringRate].every(rate => Number.isFinite(rate) && rate >= 1 && rate <= 1000)) {
      this.reset(); return false
    }
    const approach = (value, target, delta) => Math.abs(target - value) <= delta + 1e-12 ? target : value + Math.sign(target - value) * delta
    const direction = Number(keys.has('KeyW')) - Number(keys.has('KeyS'))
    if (direction && this.drive === 0 && driveMinimum > 0) this.drive = direction * driveMinimum
    else {
      const next = approach(this.drive, direction, seconds * driveRate / 100)
      // Never send the non-moving PWM band. Opposite input crosses one neutral frame first.
      this.drive = driveMinimum > 0 && (Math.abs(next) < driveMinimum || (this.drive && Math.sign(next) !== Math.sign(this.drive))) ? 0 : next
    }
    // Vehicle calibration defines positive steering as left.
    this.steering = approach(this.steering, Number(keys.has('KeyA')) - Number(keys.has('KeyD')), seconds * steeringRate / 100)
    return true
  }
}
if (typeof module !== 'undefined') module.exports = { ManualRamp, MANUAL_PROFILE }
