#pragma once
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstddef>
#include <cstring>

namespace maxbot {
// Integer wire/storage units avoid floating-point canonicalization differences.
struct SteeringSettings {
  int32_t kpMilli,kiMilli,kdMilli,deadbandMdeg,minPwm,maxPwm,filterMilliHz;
};
inline bool validSteeringSettings(const SteeringSettings& s){
  return s.kpMilli>0&&s.kpMilli<=500000&&s.kiMilli>=0&&s.kiMilli<=200000&&
    s.kdMilli>=0&&s.kdMilli<=20000&&s.deadbandMdeg>=100&&s.deadbandMdeg<=5000&&
    s.minPwm>=0&&s.minPwm<=s.maxPwm&&s.maxPwm>=1&&s.maxPwm<=255&&
    s.filterMilliHz>=1000&&s.filterMilliHz<=50000;
}
inline bool sameSettings(const SteeringSettings& a,const SteeringSettings& b){return std::memcmp(&a,&b,sizeof(a))==0;}
inline double steeringClamp(double value,double lo,double hi){return std::max(lo,std::min(hi,value));}
struct SteeringMemory {double integral=0,previous=0,velocity=0;bool initialized=false,settled=true;};
struct SteeringResult {double p=0,i=0,d=0,velocity=0;int pwm=0;bool valid=false,saturated=false;};
inline SteeringResult steeringStep(SteeringMemory& m,const SteeringSettings& s,double target,double measured,double dt){
  SteeringResult r;
  if(!validSteeringSettings(s)||!std::isfinite(target)||!std::isfinite(measured)||!std::isfinite(dt)||dt<=0||dt>.1)return r;
  if(m.initialized){
    double velocity=(measured-m.previous)/dt;
    double tau=1.0/(6.283185307179586*s.filterMilliHz/1000.0);
    m.velocity+=dt/(tau+dt)*(velocity-m.velocity);
  }
  m.previous=measured;m.initialized=true;r.velocity=m.velocity;r.valid=true;
  double error=target-measured,deadband=s.deadbandMdeg/1000.0;
  // Hysteresis prevents sensor noise from repeatedly turning the motor on/off.
  if(std::fabs(error)<=deadband||(m.settled&&std::fabs(error)<=deadband*1.5)){
    m.settled=true;m.integral=0;return r;
  }
  m.settled=false;
  r.p=s.kpMilli/1000.0*error;
  // Derivative on measured position: target ramps never create a derivative kick.
  r.d=-s.kdMilli/1000.0*m.velocity;
  double candidate=std::fabs(error)<=5?steeringClamp(m.integral+s.kiMilli/1000.0*error*dt,-s.maxPwm*.3,s.maxPwm*.3):0;
  double proposed=r.p+candidate+r.d;
  if(!((proposed>s.maxPwm&&error>0)||(proposed<-s.maxPwm&&error<0)))m.integral=candidate;
  r.i=m.integral;double raw=r.p+r.i+r.d;
  if(!std::isfinite(raw)){r.valid=false;return r;}
  // Breakaway effort is only a floor when pulling toward the target, never a braking boost.
  if(raw*error>0&&std::fabs(raw)<s.minPwm)raw=std::copysign(double(s.minPwm),raw);
  r.saturated=std::fabs(raw)>=s.maxPwm;r.pwm=int(std::round(steeringClamp(raw,-double(s.maxPwm),double(s.maxPwm))));
  return r;
}
// Small moving targets must not require a whole degree of travel to prove progress.
inline bool steeringStalled(uint64_t now,double measured,double error,int pwm,int limit,uint64_t& since,float& position){
  if(std::abs(pwm)<=limit/3){since=0;return false;}
  const double progress=steeringClamp(std::fabs(error)*.2,.25,1.0);
  if(!since||std::fabs(measured-position)>=progress){since=now;position=float(measured);return false;}
  return now-since>300;
}
inline int steeringMedianAdc(int a,int b,int c){return a>b?(b>c?b:(a>c?c:a)):(a>c?a:(b>c?c:b));}
inline double steeringFeedbackLow(double right){return std::min(-60.0,right-10.0);}
inline double steeringFeedbackHigh(double left){return std::max(60.0,left+10.0);}
struct SteeringFeedback {
  bool valid=false,pending=false;uint64_t badSince=0;uint32_t rejected=0;
  bool observe(uint64_t now,int adc,double angle,double right,double left){
    valid=adc>=32&&adc<=4063&&std::isfinite(angle)&&angle>=steeringFeedbackLow(right)&&angle<=steeringFeedbackHigh(left);
    if(valid){pending=false;badSince=0;return false;}
    rejected++;
    if(!pending){badSince=now;pending=true;}
    return now>=badSince&&now-badSince>=50;
  }
};
inline bool steeringControlActive(uint64_t now,bool armed,bool emergency,bool fault,bool feedback,uint64_t lastMotion,uint64_t expiry,uint64_t freshness){
  return armed&&!emergency&&!fault&&feedback&&lastMotion>0&&now>=lastMotion&&now-lastMotion<=freshness&&expiry>now;
}
inline int steeringLimitOutput(double angle,int pwm,double right,double left){
  return ((angle>=left&&pwm>0)||(angle<=right&&pwm<0))?0:pwm;
}
struct SteeringRecord {uint32_t magic,version;SteeringSettings settings;char calibration[65];uint32_t checksum;};
inline uint32_t steeringChecksum(const SteeringRecord& record){
  const auto* bytes=reinterpret_cast<const uint8_t*>(&record);uint32_t crc=0xffffffff;
  for(size_t i=0;i<offsetof(SteeringRecord,checksum);i++){crc^=bytes[i];for(int b=0;b<8;b++)crc=(crc>>1)^(0xedb88320u&uint32_t(-int32_t(crc&1)));}
  return ~crc;
}
inline SteeringRecord steeringRecord(const SteeringSettings& settings,const char* calibration){
  SteeringRecord r{};r.magic=0x53545031;r.version=1;r.settings=settings;std::strncpy(r.calibration,calibration,64);r.checksum=steeringChecksum(r);return r;
}
inline bool validSteeringRecord(const SteeringRecord& r,const char* calibration){
  return r.magic==0x53545031&&r.version==1&&r.calibration[64]==0&&std::strncmp(r.calibration,calibration,65)==0&&
    validSteeringSettings(r.settings)&&r.checksum==steeringChecksum(r);
}
}
