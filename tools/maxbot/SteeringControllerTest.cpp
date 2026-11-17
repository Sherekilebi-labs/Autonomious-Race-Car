#include "steering_controller.h"
#include <cassert>
#include <cstdio>
#include <limits>
using namespace maxbot;
int main(){
  SteeringSettings s{50000,0,150,750,0,255,12000};assert(validSteeringSettings(s));
  SteeringMemory m;auto center=steeringStep(m,s,0,0,.005);assert(center.valid&&center.pwm==0);
  auto close=steeringStep(m,s,2,0,.005);assert(close.pwm==100&&close.d==0); // No target derivative kick.
  m={};steeringStep(m,s,0,0,.005);auto ramp=steeringStep(m,s,.2,0,.005);assert(ramp.pwm==0);
  auto small=steeringStep(m,s,1.2,0,.005);assert(small.pwm==60); // A 3.5 degree dead zone previously suppressed this.
  auto measured=steeringStep(m,s,1.2,.1,.005);assert(measured.d<0&&measured.d>-3);
  s.minPwm=120;m={};auto friction=steeringStep(m,s,1.2,0,.005);assert(friction.pwm==120);
  auto rest=steeringStep(m,s,1.2,1.0,.005);assert(rest.pwm==0&&m.integral==0);
  assert(steeringStep(m,s,1.2,.25,.005).pwm==0); // Settled hysteresis avoids noise-triggered re-engagement.
  assert(steeringStep(m,s,1.2,0,.005).pwm>0);
  s={50000,200000,150,750,0,255,12000};m={};
  for(int i=0;i<10000;i++){auto v=steeringStep(m,s,5,0,.005);assert(v.valid&&v.pwm<=255);}
  assert(m.integral<=5.01); // Saturation never winds the I term up to the clamp.
  assert(steeringStep(m,s,-5,0,.005).pwm<0);
  assert(!steeringStep(m,s,0,0,.2).valid);assert(!steeringStep(m,s,NAN,0,.005).valid);
  s.maxPwm=80;s.minPwm=0;m={};assert(steeringStep(m,s,20,0,.005).pwm==80);
  s.minPwm=81;assert(!validSteeringSettings(s));s.minPwm=0;
  auto record=steeringRecord(s,"a");assert(validSteeringRecord(record,"a"));assert(!validSteeringRecord(record,"b"));
  record.settings.kpMilli++;assert(!validSteeringRecord(record,"a"));
  record=steeringRecord(s,"a");record.version=2;assert(!validSteeringRecord(record,"a"));
  uint64_t since=0;float position=0;
  for(int tick=0;tick<200;tick++)assert(!steeringStalled(100+tick*5,tick*.005,1.2,120,255,since,position));
  since=0;position=0;for(int tick=0;tick<=60;tick++)assert(!steeringStalled(100+tick*5,(tick%2)*.1,1.2,120,255,since,position));
  assert(steeringStalled(405,0,1.2,120,255,since,position));
  assert(!steeringStalled(410,0,1.2,0,255,since,position)&&since==0);
  assert(steeringMedianAdc(800,0,801)==800&&steeringMedianAdc(4095,800,799)==800);
  SteeringFeedback feedback;
  assert(!feedback.observe(10,800,40,-26,26)&&feedback.valid);
  assert(!feedback.observe(15,800,-40,-26,26)&&feedback.valid);
  assert(!feedback.observe(20,0,0,-26,26)&&!feedback.valid);
  assert(!feedback.observe(25,800,0,-26,26)&&feedback.valid);
  assert(!feedback.observe(30,4095,0,-26,26)&&!feedback.valid);
  assert(!feedback.observe(79,4095,0,-26,26));assert(feedback.observe(80,4095,0,-26,26));
  assert(!feedback.observe(85,800,0,-26,26)&&feedback.valid);
  assert(!feedback.observe(90,800,61,-26,26)&&!feedback.valid);
  assert(!feedback.observe(95,800,NAN,-26,26)&&!feedback.valid);
  assert(steeringControlActive(100,true,false,false,true,90,200,300));
  assert(!steeringControlActive(100,true,false,false,true,0,0,300));
  assert(!steeringControlActive(200,true,false,false,true,90,200,300));
  assert(!steeringControlActive(100,true,false,false,false,90,200,300));
  m={};s={50000,0,150,750,0,255,12000};auto recovery=steeringStep(m,s,0,40,.005);
  assert(recovery.pwm<0&&steeringLimitOutput(40,recovery.pwm,-26,26)<0);
  assert(steeringLimitOutput(40,255,-26,26)==0&&steeringLimitOutput(-40,-255,-26,26)==0);
  // Deterministic synthetic load: inertia, friction and noisy feedback, continuously moving target.
  s={50000,0,150,500,70,255,12000};m={};double angle=0,velocity=0,maxError=0;
  for(int i=0;i<3000;i++){
    double target=6*std::sin(i*.005*1.5),noise=(i%3-1)*.05;
    auto r=steeringStep(m,s,target,angle+noise,.005);assert(r.valid&&std::abs(r.pwm)<=255);
    double force=std::abs(r.pwm)>55?r.pwm-std::copysign(55.0,r.pwm):0;
    velocity+=(force*4-velocity*20)*.005;angle+=velocity*.005;
    maxError=std::max(maxError,std::fabs(target-angle));assert(std::isfinite(angle)&&std::fabs(angle)<15);
  }
  assert(maxError<2.5);
  std::printf("PASS steering PID: no setpoint kick, filtered D, small errors, friction floor, hysteresis, anti-windup, limits, corrupt/storage binding; synthetic moving-load max error %.2f deg (not car evidence)\n",maxError);
}
