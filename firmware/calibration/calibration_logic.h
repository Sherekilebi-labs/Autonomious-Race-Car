#pragma once
#include <cmath>
#include <cstdint>

namespace calibration {
constexpr int DRIVE_IN1 = 4, DRIVE_IN2 = 16;
constexpr int STEER_IN1 = 22, STEER_IN2 = 23, POT_PIN = 35;
constexpr int MAX_POINTS = 15, MAX_PWM = 255, MAX_PULSE_MS = 250;
constexpr int HEARTBEAT_MS = 350;
struct Point { int adc; float degrees; };
inline int sign(float x) { return (x > 0) - (x < 0); }
inline bool adcPin(int pin) {
  return pin == 32 || pin == 33 || pin == 34 || pin == 35 || pin == 36 || pin == 39;
}
inline bool stopPin(int pin) {
  return pin == 13 || pin == 14 || pin == 17 || pin == 18 || pin == 19 ||
         pin == 21 || pin == 25 || pin == 26 || pin == 27 || pin == 32 || pin == 33;
}
inline bool validTable(const Point* points, int n) {
  if (n < 3 || n > MAX_POINTS) return false;
  int direction = sign(points[1].adc - points[0].adc);
  bool center = false;
  for (int i = 0; i < n; ++i) {
    if (!std::isfinite(points[i].degrees) || std::fabs(points[i].degrees) >= 65 ||
        points[i].adc < 32 || points[i].adc > 4063) return false;
    if (std::fabs(points[i].degrees) < .01f) center = true;
    if (i && (points[i].degrees <= points[i-1].degrees ||
              (points[i].adc - points[i-1].adc) * direction <= 0)) return false;
  }
  return center && points[0].degrees < 0 && points[n-1].degrees > 0;
}
inline float angle(const Point* points, int n, int adc) {
  int direction = sign(points[n-1].adc - points[0].adc);
  int i = 1;
  while (i < n-1 && (adc - points[i].adc) * direction > 0) ++i;
  return points[i-1].degrees + float(adc - points[i-1].adc) /
    (points[i].adc - points[i-1].adc) * (points[i].degrees - points[i-1].degrees);
}
inline int polarity(const Point* points, int n, int in1Delta, int in2Delta) {
  if (!validTable(points,n) || std::abs(in1Delta) < 8 || std::abs(in2Delta) < 8 ||
      sign(in1Delta) == sign(in2Delta)) return 0;
  return sign(in1Delta) * sign(points[n-1].adc - points[0].adc);
}
inline bool freshMotion(uint64_t now, uint64_t deadline, uint64_t heartbeat,
                        bool armed, bool fault) {
  return armed && !fault && now < deadline && now >= heartbeat &&
         now - heartbeat <= HEARTBEAT_MS;
}
inline bool validPulse(int pwm, int ms, int direction) {
  return pwm >= 1 && pwm <= MAX_PWM && ms >= 20 && ms <= MAX_PULSE_MS &&
         (direction == -1 || direction == 1);
}
inline int clampPwm(double demand, int limit=MAX_PWM) {
  limit=limit<0?0:limit>MAX_PWM?MAX_PWM:limit;
  if(std::isnan(demand)) return 0;
  if(demand>limit) return limit;
  if(demand<-limit) return -limit;
  return int(std::round(demand));
}
struct PidMemory { double integral=0, previous=0, velocity=0; bool initialized=false; };
struct PidResult { double p=0,i=0,d=0,raw=0; int pwm=0; bool saturated=false,valid=false; };
inline PidResult pidStep(PidMemory& memory,double kp,double ki,double kd,
                         double target,double measurement,double dt,int limit=255,double filterS=.02) {
  PidResult r;
  if(!std::isfinite(kp)||!std::isfinite(ki)||!std::isfinite(kd)||kp<0||ki<0||kd<0||
     !std::isfinite(target)||!std::isfinite(measurement)||!std::isfinite(dt)||dt<=0||dt>.1||
     !std::isfinite(filterS)||filterS<0||limit<1||limit>255) return r;
  double error=target-measurement;
  if(memory.initialized) {
    double derivative=(measurement-memory.previous)/dt;
    memory.velocity+=dt/(filterS+dt)*(derivative-memory.velocity);
  }
  memory.previous=measurement; memory.initialized=true;
  r.p=kp*error; r.d=-kd*memory.velocity;
  double candidate=memory.integral+ki*error*dt;
  candidate=candidate>limit?limit:candidate<-limit?-limit:candidate;
  double proposed=r.p+candidate+r.d;
  // Conditional integration prevents windup, without restricting P/D gains.
  if(!((proposed>limit&&error>0)||(proposed<-limit&&error<0))) memory.integral=candidate;
  r.i=memory.integral; r.raw=r.p+r.i+r.d;
  if(std::isnan(r.raw)) return r;
  r.pwm=clampPwm(r.raw,limit); r.saturated=std::fabs(r.raw)>limit; r.valid=true;
  return r;
}
}  // namespace calibration
