#include "calibration_logic.h"
#include "actuator_pwm.h"
#include <climits>
#include <cassert>
#include <iostream>
using namespace calibration;
int main() {
  const int ceilings[]={0,170,255};
  for(int ceiling:ceilings) {
    int previous=0;
    for(int request=0;request<=1024;++request) {
      int duty=maxbot::motorDuty(request,ceiling);
      assert(duty>=previous && duty<=ceiling);
      assert(maxbot::motorDuty(-request,ceiling)==-duty);
      previous=duty;
    }
  }
  for(int request=-255;request<=255;++request)assert(maxbot::motorDuty(request,255)==request);
  assert(maxbot::motorDuty(255,170)==170);
  assert(maxbot::motorDuty(120,170)==80);
  assert(maxbot::motorDuty(110,170)==73);
  assert(maxbot::motorDuty(INT_MIN,170)==-170);
  assert(maxbot::motorDuty(INT_MAX,170)==170);
  assert(maxbot::motorDuty(255,-1)==0);
  assert(maxbot::motorDuty(255,256)==0);
  Point rising[]={{600,-25},{1200,-12.5},{2000,0},{2700,12.5},{3400,25}};
  Point falling[]={{3400,-25},{2000,0},{600,25}};
  assert(validTable(rising,5)); assert(validTable(falling,3));
  assert(angle(rising,5,2000)==0); assert(angle(falling,3,600)==25);
  assert(std::fabs(angle(rising,5,850)+19.791666f)<.001f);
  assert(polarity(rising,5,30,-29)==1); assert(polarity(rising,5,-30,29)==-1);
  assert(polarity(falling,3,30,-29)==-1); assert(polarity(falling,3,-30,29)==1);
  assert(polarity(rising,5,7,-29)==0); assert(polarity(rising,5,30,29)==0);
  rising[2].adc=3600; assert(!validTable(rising,5)); rising[2].adc=2000;
  rising[2].degrees=1; assert(!validTable(rising,5)); rising[2].degrees=0;
  rising[0].adc=0; assert(!validTable(rising,5)); rising[0].adc=600;
  rising[0].degrees=NAN; assert(!validTable(rising,5));
  assert(!validTable(rising,2));
  Point measured[]={{1158,-26},{975,-16},{849,-7},{785,0},{688,10},{608,16},{461,26}};
  assert(validTable(measured,7));
  for(const auto& p:measured)assert(std::fabs(angle(measured,7,p.adc)-p.degrees)<.0001f);
  assert(std::fabs(angle(measured,7,912)+11.5f)<.0001f);
  assert(std::fabs(angle(measured,7,817)+3.5f)<.0001f);
  assert(std::fabs(angle(measured,7,736)-float(490.0/97))<.0001f);
  assert(validPulse(1,20,1)); assert(validPulse(120,250,-1));
  assert(validPulse(255,250,1)); assert(validPulse(255,250,-1));
  assert(!validPulse(256,100,1)); assert(!validPulse(255,251,1));
  assert(!validPulse(0,20,1)); assert(!validPulse(50,0,1)); assert(!validPulse(50,100,0));
  assert(freshMotion(1200,1250,1000,true,false));
  assert(!freshMotion(1250,1250,1000,true,false)); // exact hardware deadline
  assert(!freshMotion(1351,2000,1000,true,false)); // lost supervision
  assert(!freshMotion(1200,1250,1000,false,false));
  assert(!freshMotion(1200,1250,1000,true,true));
  assert(!freshMotion(999,1250,1000,true,false));
  assert(POT_PIN==35); assert(adcPin(35)); assert(!adcPin(14)); assert(!stopPin(35)); assert(!stopPin(4));
  assert(clampPwm(1000000)==255); assert(clampPwm(-1000000)==-255);
  assert(clampPwm(INFINITY)==255); assert(clampPwm(-INFINITY)==-255); assert(clampPwm(NAN)==0);
  assert(clampPwm(1e6,999)==255); assert(clampPwm(1e6,80)==80);
  PidMemory memory;
  auto positive=pidStep(memory,10000,500,5000,10,0,.005);
  assert(positive.valid&&positive.raw>255&&positive.pwm==255&&positive.saturated);
  memory=PidMemory{};
  auto negative=pidStep(memory,10000,500,5000,-10,0,.005);
  assert(negative.valid&&negative.raw<-255&&negative.pwm==-255);
  // A changed target does not create a derivative kick on stationary feedback.
  memory=PidMemory{};pidStep(memory,0,0,5000,0,0,.005);
  auto noKick=pidStep(memory,0,0,5000,20,0,.005);assert(noKick.valid&&noKick.pwm==0);
  auto derivative=pidStep(memory,0,0,5000,20,1,.005);assert(derivative.valid&&derivative.d<-255&&derivative.pwm==-255);
  // Saturation does not wind up the integral; a corrected error recovers immediately.
  memory=PidMemory{};
  for(int i=0;i<1000;++i)assert(pidStep(memory,1000,10000,0,10,0,.005).pwm==255);
  assert(memory.integral==0);assert(pidStep(memory,1000,10000,0,0,0,.005).pwm==0);
  memory=PidMemory{};
  auto overflow=pidStep(memory,1e308,0,0,10,0,.005);assert(overflow.valid&&overflow.pwm==255);
  assert(!pidStep(memory,NAN,0,0,10,0,.005).valid);
  assert(!pidStep(memory,1,0,0,10,0,.5).valid);
  assert(!pidStep(memory,-1,0,0,10,0,.005).valid);
  std::cout << "PASS calibration table, both feedback polarities, pulse limits and supervision deadlines\n";
  std::cout << "PASS aggressive PID gains, final PWM clamp, derivative feedback and anti-windup\n";
}
