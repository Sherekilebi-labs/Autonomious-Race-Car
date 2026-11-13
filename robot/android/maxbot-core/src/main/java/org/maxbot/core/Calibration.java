package org.maxbot.core;

import java.util.ArrayList;
import java.util.List;

/** SI units; null means unmeasured. No guessed geometry may arm the vehicle. */
public final class Calibration {
  public int schemaVersion = 1;
  public String firmwareCalibrationId = "", geometryId = "";
  public Car car = new Car();
  public Steering steering = new Steering();
  public Camera camera = new Camera();
  public Cones cones = new Cones();
  public Power power = new Power();
  public Limits limits = new Limits();
  public Network network = new Network();
  public Commissioning commissioning = new Commissioning();
  public Manual manual = new Manual();
  public Hardware hardware = new Hardware();

  public static final class Manual {
    public boolean enabled;
    public int maxPwm = 255, reversePauseMs = 300;
  }

  public static final class Hardware {
    public boolean driveBrakeVerified;
  }

  public static final class Car {
    public Double wheelbaseM, widthM, frontOverhangM, rearOverhangM;
    // Optional tire-center spacing. The bicycle model uses wheelbase; collision
    // checks need widthM to enclose the entire mounted car, not this spacing.
    public Double trackWidthM;
  }

  public static final class Steering {
    public Double leftDeg, rightDeg, travelTimeS, deadbandDeg;
    public int[] adc;
    public double[] degrees;
    public Double kp, ki, kd;
    public Integer maxPwm;
  }

  public static final class Camera {
    public String streamId="";
    public double[] sensorToCarRotation,gyroBiasRadS,imuLeverArmM;
    public Double positionErrorM,headingErrorRad;
    // Phone camera position relative to rear axle: forward, left, up.
    public Double forwardM, leftM, heightM, pitchDownDeg, yawLeftDeg;
  }

  public static final class Cones {
    public Double heightM, baseDiameterM;
    public int count = 4;
  }

  public static final class Power {
    public boolean batteryMonitoring = true;
    // Operator's target for a manual pre-run measurement, not live telemetry.
    public Double manualCheckTargetV;
    public String senseSource = "battery";
    public Double dividerRatio, adcReferenceMv, adcOffsetMv, fullV, warningV, stopV;
  }

  public static final class Limits {
    public double[] floorBoundsM;
    public double scoutSpeedMps = 0.2;
    public Double maxSpeedMps, accelerationMps2, brakingMps2, lateralMps2, pwmPerMps;
    public Integer tractionMinPwm;
    public Double launchClearM, finishClearM, lateralSpaceM;
    public double clearanceM = 0.15;
    public int commandTtlMs = 100, watchdogMs = 150, supervisorMs = 500;
  }

  public static final class Network {
    public String espHost = "", dashboardUrl = "", pairingKey = "", transport = "wifi";
    public int espPort = 4242, baud = 460800;
  }

  public static final class Commissioning {
    public boolean steeringMeasured, brakingTested, powerVerified, floorVerified;
    public int cleanBaselineRuns;
  }

  public List<String> missing() {
    return missing(true);
  }

  public List<String> manualMissing() {
    return missing(false);
  }

  private List<String> missing(boolean autonomous) {
    List<String> m = new ArrayList<>();
    if (schemaVersion != 1) m.add("Unsupported calibration schema");
    if (firmwareCalibrationId == null || !firmwareCalibrationId.matches("[a-f0-9]{64}"))
      m.add("Generate configuration with tools/maxbot/configure.py");
    if (autonomous) {
      positive(m, "car.wheelbaseM", car.wheelbaseM);
      positive(m, "car.widthM", car.widthM);
      nonnegative(m, "car.frontOverhangM", car.frontOverhangM);
      nonnegative(m, "car.rearOverhangM", car.rearOverhangM);
      if(camera.streamId==null||!camera.streamId.matches("[a-f0-9]{64}"))m.add("Measured camera lens/stream identity required");
      if(!validRotation(camera.sensorToCarRotation)||!vector(camera.gyroBiasRadS)||!vector(camera.imuLeverArmM))m.add("Measured IMU axes, bias and lever arm required");
      positive(m,"camera.positionErrorM",camera.positionErrorM);positive(m,"camera.headingErrorRad",camera.headingErrorRad);
      positive(m, "camera.heightM", camera.heightM);
      finite(m, "camera.forwardM", camera.forwardM);
      finite(m, "camera.leftM", camera.leftM);
      finite(m, "camera.pitchDownDeg", camera.pitchDownDeg);
      finite(m, "camera.yawLeftDeg", camera.yawLeftDeg);
      positive(m, "cones.heightM", cones.heightM);
      positive(m, "cones.baseDiameterM", cones.baseDiameterM);
      if (cones.count != 4) m.add("Exactly four cones are required");
    }
    if (steering.leftDeg == null
        || !Double.isFinite(steering.leftDeg)
        || steering.leftDeg <= 0
        || steering.leftDeg >= 65
        || steering.rightDeg == null
        || !Double.isFinite(steering.rightDeg)
        || steering.rightDeg >= 0
        || steering.rightDeg <= -65)
      m.add("MEASURE STEERING LIMITS: left positive, right negative, magnitude <65 degrees");
    if (!validSteeringTable())
      m.add("steering ADC/angle table: >=3 monotonic points including center and both limits");
    positive(m, "steering.travelTimeS", steering.travelTimeS);
    positive(m, "steering.kp", steering.kp);
    nonnegative(m, "steering.ki", steering.ki);
    nonnegative(m, "steering.kd", steering.kd);
    positive(m, "steering.deadbandDeg", steering.deadbandDeg);
    if (steering.maxPwm == null || steering.maxPwm < 1 || steering.maxPwm > 255)
      m.add("steering.maxPwm");
    if (power.batteryMonitoring) {
      if (!"battery".equals(power.senseSource))
        m.add("Battery voltage must be sensed before the buck converter, not its regulated output");
      positive(m, "power.dividerRatio", power.dividerRatio);
      positive(m, "power.adcReferenceMv", power.adcReferenceMv);
      finite(m, "power.adcOffsetMv", power.adcOffsetMv);
      if (power.fullV == null
          || power.warningV == null
          || power.stopV == null
          || !Double.isFinite(power.fullV)
          || !Double.isFinite(power.warningV)
          || !Double.isFinite(power.stopV)
          || !(power.fullV > power.warningV && power.warningV > power.stopV && power.stopV > 0))
        m.add("power voltage thresholds");
    }
    if (autonomous) {
      positive(m, "limits.maxSpeedMps", limits.maxSpeedMps);
      positive(m, "limits.accelerationMps2", limits.accelerationMps2);
      positive(m, "limits.brakingMps2", limits.brakingMps2);
      positive(m, "limits.lateralMps2", limits.lateralMps2);

      positive(m, "limits.launchClearM", limits.launchClearM);
      positive(m, "limits.finishClearM", limits.finishClearM);
      positive(m, "limits.lateralSpaceM", limits.lateralSpaceM);
      if (!Double.isFinite(limits.scoutSpeedMps)
          || limits.scoutSpeedMps <= 0
          || limits.scoutSpeedMps > .3
          || !Double.isFinite(limits.clearanceM)
          || limits.clearanceM < .05) m.add("Conservative scout speed/clearance");
    }
    if (limits.commandTtlMs != 100 || limits.watchdogMs != 150 || limits.supervisorMs != 500)
      m.add("Protocol timing must be 100/150/500 ms");
    if (!"wifi".equals(network.transport) && !"usb".equals(network.transport))
      m.add("network.transport");
    if (network.baud != 230400 && network.baud != 460800 && network.baud != 921600)
      m.add("USB baud must be 230400, 460800 or 921600 for 50 Hz authenticated JSON");
    if (network.pairingKey == null || network.pairingKey.length() < 32)
      m.add("network.pairingKey (>=32 characters, private override)");
    if (autonomous) {
      if (!commissioning.steeringMeasured) m.add("Commissioning: steering limits not confirmed");
      if (!commissioning.brakingTested) m.add("Commissioning: braking not tested");
      if (!commissioning.powerVerified) m.add("Commissioning: power/driver wiring not verified");
      if (!commissioning.floorVerified) m.add("Commissioning: clear floor/corridor not verified");
      if (!hardware.driveBrakeVerified)
        m.add("Commissioning: electrical brake behavior not verified");
    } else {
      if (!manual.enabled) m.add("Manual commissioning is disabled");
      if (manual.maxPwm < 1 || manual.maxPwm > 255) m.add("manual.maxPwm (1..255)");
      if (manual.reversePauseMs < 100 || manual.reversePauseMs > 2000)
        m.add("manual.reversePauseMs (100..2000)");
    }
    return m;
  }

  public boolean validSteeringTable() {
    int[] a = steering.adc;
    double[] d = steering.degrees;
    if (a == null || d == null || a.length != d.length || a.length < 3) return false;
    int direction = a[1] > a[0] ? 1 : -1;
    boolean center = false;
    for (int i = 0; i < a.length; i++) {
      if (a[i] < 32 || a[i] > 4063 || !Double.isFinite(d[i])) return false;
      if (Math.abs(d[i]) < .01) center = true;
      if (i > 0 && ((a[i] - a[i - 1]) * direction <= 0 || d[i] <= d[i - 1])) return false;
    }
    return center
        && steering.leftDeg != null
        && steering.rightDeg != null
        && Math.abs(d[0] - steering.rightDeg) < .01
        && Math.abs(d[d.length - 1] - steering.leftDeg) < .01;
  }

  public double stoppingDistance(double v, double ageS) {
    if (limits.brakingMps2 == null || limits.brakingMps2 <= 0) return Double.POSITIVE_INFINITY;
    return v * Math.max(0, ageS) + v * v / (2 * limits.brakingMps2) + limits.clearanceM;
  }

  public static boolean vector(double[] a){if(a==null||a.length!=3)return false;for(double x:a)if(!Double.isFinite(x))return false;return true;}
  public static boolean validRotation(double[] a){
    if(a==null||a.length!=9)return false;for(double x:a)if(!Double.isFinite(x))return false;
    for(int i=0;i<3;i++)for(int j=0;j<3;j++){double dot=0;for(int k=0;k<3;k++)dot+=a[3*i+k]*a[3*j+k];if(Math.abs(dot-(i==j?1:0))>.01)return false;}
    double determinant=a[0]*(a[4]*a[8]-a[5]*a[7])-a[1]*(a[3]*a[8]-a[5]*a[6])+a[2]*(a[3]*a[7]-a[4]*a[6]);return Math.abs(determinant-1)<.01;
  }
  private static void finite(List<String> m, String n, Double v) {
    if (v == null || !Double.isFinite(v)) m.add(n);
  }

  private static void positive(List<String> m, String n, Double v) {
    if (v == null || !Double.isFinite(v) || v <= 0) m.add(n);
  }

  private static void nonnegative(List<String> m, String n, Double v) {
    if (v == null || !Double.isFinite(v) || v < 0) m.add(n);
  }
}
