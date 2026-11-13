'use strict'
const MANUAL_PROFILE = Object.freeze({ inputMs: 300, linkMs: 500, clockBoundMs: 75 })

// Signed, normalized axes keep ramp timing independent of PWM/steering calibration.
class ManualRamp {
  constructor () { this.drive = 0; this.steering = 0 }
  reset () { this.drive = this.steering = 0 }
  step (keys, seconds, driveRate = 100, steeringRate = 250) {
    if (!Number.isFinite(seconds) || seconds < 0 || seconds > MANUAL_PROFILE.inputMs / 1000 ||
        ![driveRate, steeringRate].every(rate => Number.isFinite(rate) && rate >= 1 && rate <= 1000)) {
      this.reset(); return false
    }
    const approach = (value, target, delta) => Math.abs(target - value) <= delta + 1e-12 ? target : value + Math.sign(target - value) * delta
    this.drive = approach(this.drive, Number(keys.has('KeyW')) - Number(keys.has('KeyS')), seconds * driveRate / 100)
    // Vehicle calibration defines positive steering as left.
    this.steering = approach(this.steering, Number(keys.has('KeyA')) - Number(keys.has('KeyD')), seconds * steeringRate / 100)
    return true
  }
}
if (typeof module !== 'undefined') module.exports = { ManualRamp, MANUAL_PROFILE }
