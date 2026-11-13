/* Standalone bench calibration. Open THIS folder as an Arduino sketch, never
 * put it next to maxbot.ino. Classic ESP32, Arduino core 2.0.17 / 3.3.0,
 * ArduinoJson 6.21.5. All motor tests are time bounded; no automatic end seeking.
 * The feedback wire MUST be moved from GPIO14 to GPIO35 before use with Wi-Fi.
 */
#include <Arduino.h>
#include <ArduinoJson.h>
#include <WiFi.h>
#include <WebServer.h>
#include <esp_timer.h>
#include <esp_arduino_version.h>
#include "calibration_logic.h"
#include "actuator_pwm.generated.h"
#include "dashboard.h"

using namespace calibration;
// Fixed bench-network credentials survive resets; runtime Wi-Fi is configured separately.
constexpr char AP_SSID[] = "MaxBot-Cal";
constexpr char AP_PASSWORD[] = "MaxBot2026";
static_assert(sizeof(AP_PASSWORD)-1>=8 && sizeof(AP_PASSWORD)-1<=63,"AP password must be 8–63 characters");
WebServer server(80);
SemaphoreHandle_t stateMutex;
String controlToken;
uint32_t lastSequence = 0;
enum Motion { IDLE, STEERING_PULSE, DRIVE_PULSE, PID_TEST, BRAKE_TEST };
struct BatterySample { int adc; float voltage; };
struct PulseResult {
  bool valid=false; int motor=0,direction=0,pwm=0,duration=0,before=0,after=0;
};
struct ActionEvent { uint32_t revision=0,seq=0; bool error=false,complete=true; char message[160]=""; };
constexpr int EVENT_CAPACITY=16;
ActionEvent actionEvents[EVENT_CAPACITY];
int eventHead=0,eventCount=0;
struct State {
  bool armed=false, fault=false, stopPressed=false, tuningRecorded=false, powerRecorded=false, outputsReady=false;
  bool brakeVerified=false, probe1Valid=false, probe2Valid=false;
  int adc=0, pinMv=0, batteryRaw=-1, batteryPin=-1, stopPin=-1;
  int steerPolarity=0, drivePolarity=0, probe1=0, probe2=0;
  int count=0, historyCount=0, historyIndex=0, history[16]={};
  int direction=1, pwm=0, startAdc=0, maxPwm=255, appliedSteer=0,appliedDrive=0;
  int startPwm[2][2]={{0,0},{0,0}};
  PulseResult lastPulse;
  uint32_t contextSeq=0,eventActionSeq=0,eventRevision=0,motionSeq=0;
  bool eventIsError=false,eventComplete=true;
  Point points[MAX_POINTS];
  BatterySample batterySamples[2]; int batteryCount=0;
  double kp=4, ki=0, kd=0;
  float deadband=1,target=0;
  PidMemory pid;
  PidResult pidResult;
  uint64_t pidTimeUs=0;
  float travelTime=0, divider=0, referenceMv=0, offsetMv=0, fullV=0, warningV=0, stopV=0;
  float stallAngle=0;
  uint64_t heartbeat=0, deadline=0, started=0, stallSince=0;
  Motion motion=IDLE;
  char error[80]="", event[160]="Outputs disabled. Verify wiring, then enable bench outputs.";
} s;

uint64_t nowMs() { return (uint64_t)esp_timer_get_time()/1000; }
void pwmWrite(int channel,int value) {
  value=constrain(value,0,MAX_PWM);
#if ESP_ARDUINO_VERSION_MAJOR >= 3
  ledcWriteChannel(channel,value);
#else
  ledcWrite(channel,value);
#endif
}
void coast() { for(int i=0;i<4;++i) pwmWrite(i,0); s.appliedSteer=s.appliedDrive=0; }
void motor(int first,int second,int value) {
  value=clampPwm(value);
  value=maxbot::motorDuty(value,first==0?DRIVE_MAX_DUTY:STEER_MAX_DUTY);
  if(first==0) s.appliedDrive=value; else s.appliedSteer=value;
  if(value>=0) { pwmWrite(second,0); pwmWrite(first,value); }
  else { pwmWrite(first,0); pwmWrite(second,-value); }
}
void event(const char* text,bool complete=true) {
  snprintf(s.event,sizeof(s.event),"%s",text);
  s.eventActionSeq=s.contextSeq; ++s.eventRevision; s.eventIsError=false; s.eventComplete=complete;
  ActionEvent& item=actionEvents[eventHead];item.revision=s.eventRevision;item.seq=s.contextSeq;
  item.error=false;item.complete=complete;snprintf(item.message,sizeof(item.message),"%s",text);
  eventHead=(eventHead+1)%EVENT_CAPACITY;eventCount=min(EVENT_CAPACITY,eventCount+1);
}
void cancel(const char* reason=nullptr) {
  if(s.motion!=IDLE&&s.motionSeq&&s.motionSeq!=s.contextSeq){
    uint32_t caller=s.contextSeq;s.contextSeq=s.motionSeq;
    event(reason?reason:"Motor test stopped.");s.contextSeq=caller;
  }
  s.motion=IDLE; s.deadline=0; s.pid=PidMemory{}; s.stallSince=0; coast();
  if(reason) event(reason);
}
void trip(const char* reason) {
  if(s.fault&&!strcmp(s.error,reason)){cancel();s.armed=false;return;}
  cancel(reason); s.armed=false; s.fault=true;
  snprintf(s.error,sizeof(s.error),"%s",reason);
  s.eventIsError=true;
  actionEvents[(eventHead+EVENT_CAPACITY-1)%EVENT_CAPACITY].error=true;
}
bool stableAdc() {
  if(s.historyCount<16) return false;
  int lo=4095,hi=0;
  for(int i=0;i<16;++i) { lo=min(lo,s.history[i]); hi=max(hi,s.history[i]); }
  return hi-lo<=18;
}
bool tableValid() { return validTable(s.points,s.count); }
float currentAngle() { return angle(s.points,s.count,s.adc); }
float packVoltage() {
  return (s.batteryRaw*s.referenceMv/4095.f+s.offsetMv)*s.divider/1000.f;
}
bool atBoundary(int effort) {
  if(!tableValid()) return false;
  float a=currentAngle();
  return (a>=s.points[s.count-1].degrees && effort>0) ||
         (a<=s.points[0].degrees && effort<0);
}
void finishPulse() {
  if(s.motion==STEERING_PULSE || s.motion==DRIVE_PULSE)
    s.lastPulse={true,s.motion==STEERING_PULSE?0:1,s.direction,s.pwm,int(nowMs()-s.started),s.startAdc,s.adc};
  if(s.motion==STEERING_PULSE) {
    int delta=s.adc-s.startAdc;
    if(abs(delta)>=8) {
      if(s.direction>0) { s.probe1=delta; s.probe1Valid=true; }
      else { s.probe2=delta; s.probe2Valid=true; }
      if(s.steerPolarity && polarity(s.points,s.count,s.probe1,s.probe2)!=s.steerPolarity) s.steerPolarity=0;
    }
    char text[160]; snprintf(text,sizeof(text),"IN%d pulse: PWM %d, ADC %d -> %d (change %+d). %s",
      s.direction>0?1:2,s.pwm,s.startAdc,s.adc,delta,abs(delta)<8?"No reliable movement detected.":"Movement detected; verify wheel direction."); event(text);
  } else if(s.motion==PID_TEST) {
    char text[160]; snprintf(text,sizeof(text),"PID test ended. Final error %.2f degrees; last demand %.1f, clipped PWM %d.",s.target-currentAngle(),s.pidResult.raw,s.pidResult.pwm); event(text);
  } else event("Bounded motor test ended; outputs are coasting.");
  cancel();
}
void controlTask(void*) {
  TickType_t wake=xTaskGetTickCount();
  for(;;) {
    xSemaphoreTake(stateMutex,portMAX_DELAY);
    uint64_t now=nowMs();
    int sum=0; for(int i=0;i<4;++i) sum+=analogRead(POT_PIN);
    s.adc=(sum+2)/4;
    s.history[s.historyIndex]=s.adc; s.historyIndex=(s.historyIndex+1)%16;
    s.historyCount=min(16,s.historyCount+1);
    if(now%20<5) s.pinMv=analogReadMilliVolts(POT_PIN);
    if(s.batteryPin>=0) s.batteryRaw=analogRead(s.batteryPin);
    s.stopPressed=s.stopPin>=0 && digitalRead(s.stopPin)==LOW;
    if(s.stopPressed) trip("PHYSICAL_STOP: release it, then reset while disabled.");
    if(s.armed && (s.adc<32 || s.adc>4063)) trip("ADC_RAIL: check feedback voltage/wiring; move away from extremes.");
    if(s.armed && s.powerRecorded && s.batteryPin>=0 && packVoltage()<s.stopV)
      trip("LOW_VOLTAGE: battery below configured stop threshold.");
    if(s.motion!=IDLE) {
      if(!freshMotion(now,s.deadline,s.heartbeat,s.armed,s.fault)) {
        if(now-s.heartbeat>HEARTBEAT_MS) trip("SUPERVISION_LOST: page disconnected or hidden. Reset to continue.");
        else finishPulse();
      } else if(s.motion==STEERING_PULSE) {
        int rawDirection=s.direction;
        // Once endpoints are recorded, block pulses that move farther outward.
        int deltaSign=s.probe1Valid?sign(s.probe1):s.probe2Valid?-sign(s.probe2):0;
        int leftSign=tableValid()?sign(s.points[s.count-1].adc-s.points[0].adc):0;
        if(deltaSign && leftSign && atBoundary(rawDirection*deltaSign*leftSign))
          cancel("Captured wheel-angle boundary reached; outward pulse stopped.");
        else motor(2,3,s.direction*s.pwm);
      } else if(s.motion==DRIVE_PULSE) motor(0,1,s.direction*s.pwm);
      else if(s.motion==BRAKE_TEST) { pwmWrite(0,255); pwmWrite(1,255); }
      else if(s.motion==PID_TEST) {
        float a=currentAngle(),error=s.target-a;
        uint64_t us=esp_timer_get_time(); double dt=(us-s.pidTimeUs)/1000000.; s.pidTimeUs=us;
        if(fabs(error)<=s.deadband) {
          motor(2,3,0); s.pid=PidMemory{}; s.pidResult=PidResult{}; s.stallSince=0;
        } else {
          s.pidResult=pidStep(s.pid,s.kp,s.ki,s.kd,s.target,a,dt,s.maxPwm);
          int output=s.pidResult.pwm;
          if(!s.pidResult.valid) trip("PID_NUMERIC: invalid calculation or control interval over 100 ms.");
          else if(atBoundary(output)) cancel("Wheel-angle boundary reached; PID test stopped.");
          else {
            motor(2,3,output*s.steerPolarity);
            if(abs(output)>s.maxPwm/3) {
              if(!s.stallSince || fabs(a-s.stallAngle)>1) { s.stallSince=now; s.stallAngle=a; }
              else if(now-s.stallSince>300) trip("STEERING_STALL: check mechanism, polarity and PWM.");
            } else s.stallSince=0;
          }
        }
      }
    } else coast();
    xSemaphoreGive(stateMutex);
    vTaskDelayUntil(&wake,pdMS_TO_TICKS(5));
  }
}

String randomHex() {
  char out[33]; snprintf(out,sizeof(out),"%08lx%08lx%08lx%08lx",
    (unsigned long)esp_random(),(unsigned long)esp_random(),(unsigned long)esp_random(),(unsigned long)esp_random());
  return String(out);
}
State snapshot() { xSemaphoreTake(stateMutex,portMAX_DELAY); State copy=s; xSemaphoreGive(stateMutex); return copy; }
void jsonReply(int status,JsonDocument& doc) {
  String json; serializeJson(doc,json);
  server.sendHeader("Cache-Control","no-store"); server.sendHeader("X-Content-Type-Options","nosniff");
  server.send(status,"application/json",json);
}
void jsonError(int code,const char* error) { StaticJsonDocument<256> doc; doc["error"]=error; jsonReply(code,doc); }
void stateRoute() {
  State v; ActionEvent records[EVENT_CAPACITY];int count;
  xSemaphoreTake(stateMutex,portMAX_DELAY);v=s;count=eventCount;
  for(int i=0;i<count;++i)records[i]=actionEvents[(eventHead-count+i+EVENT_CAPACITY)%EVENT_CAPACITY];
  xSemaphoreGive(stateMutex);
  DynamicJsonDocument out(12288);
  uint32_t after=server.hasArg("after")?(uint32_t)server.arg("after").toInt():0;
  out["eventsOverflow"]=count==EVENT_CAPACITY&&after<records[0].revision-1;
  JsonArray events=out.createNestedArray("events");
  for(int i=0;i<count;++i)if(records[i].revision>after){
    JsonObject item=events.createNestedObject();item["revision"]=records[i].revision;item["seq"]=records[i].seq;
    item["message"]=records[i].message;item["error"]=records[i].error;item["complete"]=records[i].complete;
  }
  out["armed"]=v.armed; out["moving"]=v.motion!=IDLE; out["fault"]=v.error;
  out["adc"]=v.adc; out["pinMv"]=v.pinMv; out["event"]=v.event;
  out["eventActionSeq"]=v.eventActionSeq; out["eventRevision"]=v.eventRevision; out["eventIsError"]=v.eventIsError;
  out["appliedSteer"]=v.appliedSteer; out["appliedDrive"]=v.appliedDrive;
  out["pidRaw"]=v.pidResult.raw; out["pidP"]=v.pidResult.p; out["pidI"]=v.pidResult.i; out["pidD"]=v.pidResult.d;
  out["pidClipped"]=v.pidResult.pwm; out["pidSaturated"]=v.pidResult.saturated;
  int lo=4095,hi=0; for(int i=0;i<v.historyCount;++i){lo=min(lo,v.history[i]);hi=max(hi,v.history[i]);}
  out["adcSpread"]=v.historyCount?hi-lo:4095; out["adcStable"]=v.historyCount==16&&hi-lo<=18;
  out["outputsReady"]=v.outputsReady;
  JsonArray starts=out.createNestedArray("startPwm");
  for(int m=0;m<2;++m){JsonArray row=starts.createNestedArray();row.add(v.startPwm[m][0]);row.add(v.startPwm[m][1]);}
  JsonObject pulse=out.createNestedObject("lastPulse");pulse["valid"]=v.lastPulse.valid;
  pulse["motor"]=v.lastPulse.motor; pulse["direction"]=v.lastPulse.direction; pulse["pwm"]=v.lastPulse.pwm;
  pulse["duration"]=v.lastPulse.duration; pulse["before"]=v.lastPulse.before; pulse["after"]=v.lastPulse.after;
  out["batteryAdc"]=v.batteryRaw; out["batteryPin"]=v.batteryPin;
  out["kp"]=v.kp; out["ki"]=v.ki; out["kd"]=v.kd;
  out["maxPwm"]=v.maxPwm; out["deadbandDeg"]=v.deadband; out["travelTimeS"]=v.travelTime;
  out["dividerRatio"]=v.divider; out["fullV"]=v.fullV; out["warningV"]=v.warningV; out["stopV"]=v.stopV;
  out["tableValid"]=validTable(v.points,v.count);
  out["stopPin"]=v.stopPin; out["stopPressed"]=v.stopPressed;
  out["steerPolarity"]=v.steerPolarity; out["drivePolarity"]=v.drivePolarity;
  if(v.probe1Valid) out["probe1"]=v.probe1; else out["probe1"]=nullptr;
  if(v.probe2Valid) out["probe2"]=v.probe2; else out["probe2"]=nullptr;
  if(validTable(v.points,v.count)) out["angle"]=angle(v.points,v.count,v.adc); else out["angle"]=nullptr;
  if(v.powerRecorded && v.batteryPin>=0) out["batteryV"]=(v.batteryRaw*v.referenceMv/4095.f+v.offsetMv)*v.divider/1000.f;
  else out["batteryV"]=nullptr;
  JsonArray points=out.createNestedArray("points");
  for(int i=0;i<v.count;++i) { JsonObject p=points.createNestedObject(); p["adc"]=v.points[i].adc; p["degrees"]=v.points[i].degrees; }
  JsonArray batteries=out.createNestedArray("batterySamples");
  for(int i=0;i<v.batteryCount;++i) { JsonObject p=batteries.createNestedObject(); p["adc"]=v.batterySamples[i].adc; p["voltage"]=v.batterySamples[i].voltage; }
  jsonReply(200,out);
}
bool numeric(JsonVariantConst value,float lo,float hi) {
  return (value.is<float>() || value.is<int>()) && isfinite(value.as<float>()) && value.as<float>()>=lo && value.as<float>()<=hi;
}
bool tuningValid(JsonDocument& msg) {
  auto gain=[](JsonVariantConst v){return (v.is<double>()||v.is<int>())&&isfinite(v.as<double>())&&v.as<double>()>=0;};
  return gain(msg["kp"]) && gain(msg["ki"]) && gain(msg["kd"]) &&
    msg["maxPwm"].is<int>() && numeric(msg["maxPwm"],1,MAX_PWM) && numeric(msg["deadbandDeg"],.1,5);
}
void setTuning(JsonDocument& msg) {
  s.kp=msg["kp"].as<double>(); s.ki=msg["ki"].as<double>(); s.kd=msg["kd"].as<double>();
  s.maxPwm=msg["maxPwm"]; s.deadband=msg["deadbandDeg"]; s.tuningRecorded=true;
}
const char* perform(JsonDocument& msg) {
  const char* action=msg["action"]|""; uint64_t now=nowMs();
  if(!strcmp(action,"heartbeat")) { s.heartbeat=now; return nullptr; }
  if(!strcmp(action,"stopTest")) { cancel("Motor test stopped."); return nullptr; }
  if(!strcmp(action,"estop")) { trip("USER_STOP: outputs disabled. Reset before enabling again."); return nullptr; }
  if(!strcmp(action,"release") || !strcmp(action,"disarm")) {
    cancel("Outputs disabled by operator."); s.armed=false; return nullptr;
  }
  if(!strcmp(action,"reset")) {
    cancel(); s.armed=false;
    if(!s.outputsReady) return "PWM setup failed. Restart after fixing the board/pin configuration.";
    if(s.stopPressed) return "Release the physical stop first.";
    if(s.adc<32 || s.adc>4063) return "Feedback is at an ADC rail; check the wiring/voltage first.";
    s.fault=false; s.error[0]=0; event("Fault reset. Outputs remain disabled."); return nullptr;
  }
  if(!strcmp(action,"arm")) {
    if(!msg["bench"].is<bool>() || !msg["bench"].as<bool>()) return "Confirm the raised-wheel bench and ADC voltage checks.";
    if(!s.outputsReady) return "PWM initialization failed. Restart after checking the board/pins.";
    if(s.fault) return "A fault/stop is latched. Correct its cause, then use Reset stop / fault.";
    if(s.stopPressed) return "Release the physical stop input before enabling outputs.";
    if(s.adc<32||s.adc>4063) return "GPIO35 feedback is at an ADC rail; it must be 32–4063. Check wiring/voltage.";
    if(!stableAdc()) return "ADC feedback is noisy: spread must be at most 18 counts across 80 ms.";
    cancel(); s.armed=true; s.heartbeat=now; event("Bench outputs enabled. Choose one short pulse."); return nullptr;
  }
  if(!strcmp(action,"pulse") || !strcmp(action,"target") || !strcmp(action,"brake")) {
    if(!s.armed || s.fault || now-s.heartbeat>HEARTBEAT_MS) return "Enable bench outputs with the page connected first.";
    if(s.motion!=IDLE) return "A test is already active. Wait or stop it.";
    if(!strcmp(action,"pulse")) {
      if(!msg["pwm"].is<int>() || !msg["ms"].is<int>() || !msg["direction"].is<int>() ||
          !validPulse(msg["pwm"],msg["ms"],msg["direction"])) return "Pulse requires PWM 1–255, 20–250 ms, direction +1/-1.";
      const char* bridge=msg["bridge"]|"";
      if(strcmp(bridge,"steer") && strcmp(bridge,"drive")) return "Unknown bridge.";
      s.direction=msg["direction"]; s.pwm=msg["pwm"]; s.startAdc=s.adc; s.lastPulse.valid=false;
      s.motion=!strcmp(bridge,"steer")?STEERING_PULSE:DRIVE_PULSE; s.deadline=now+msg["ms"].as<int>();
      event("Short pulse active; do not touch moving parts.",false);
    } else if(!strcmp(action,"brake")) {
      if(!msg["confirmed"].is<bool>() || !msg["confirmed"].as<bool>()) return "Confirm verified both-high electrical braking before testing.";
      s.brakeVerified=true; s.motion=BRAKE_TEST; s.deadline=now+50; event("Verified electrical brake applied for 50 ms.",false);
    } else {
      if(!tableValid() || !s.steerPolarity) return "Capture a valid wheel-angle table and derive polarity first.";
      if(!tuningValid(msg)) return "Use finite nonnegative PID gains, PWM cap 1–255 and deadband 0.1–5 degrees.";
      if(!numeric(msg["degrees"],s.points[0].degrees,s.points[s.count-1].degrees)) return "Target must be inside the measured wheel-angle range.";
      if(!msg["ms"].is<int>()||!numeric(msg["ms"],100,5000)) return "PID test duration must be 100–5000 ms.";
      setTuning(msg); s.target=msg["degrees"]; s.pid=PidMemory{}; s.pidResult=PidResult{};
      s.pidTimeUs=esp_timer_get_time(); s.stallSince=0;
      s.motion=PID_TEST; s.deadline=now+msg["ms"].as<int>(); event("PID test active. Raw demand is clipped at the selected cap, never above 255.",false);
    }
    s.started=now; s.motionSeq=s.contextSeq; return nullptr;
  }
  if(s.motion!=IDLE) return "Stop the motor test before changing calibration.";
  if(!strcmp(action,"recordPwm")) {
    if(!s.lastPulse.valid) return "Complete an open-loop PWM pulse first.";
    int m=msg["motor"]|-1;
    if(m<0||m>1||s.lastPulse.motor!=m) return "Run a pulse on this motor before recording its starting PWM.";
    if(!msg["observed"].is<bool>()||!msg["observed"].as<bool>()) return "Confirm that the motor reliably started at this PWM.";
    if(m==0&&abs(s.lastPulse.after-s.lastPulse.before)<8) return "Steering ADC change is under 8 counts; reliable movement was not detected.";
    s.startPwm[m][s.lastPulse.direction>0?0:1]=s.lastPulse.pwm;
    event("Observed minimum starting PWM recorded for this motor/input direction.");
  } else if(!strcmp(action,"capture")) {
    if(!numeric(msg["degrees"],-64,64) || !stableAdc() || s.adc<32 || s.adc>4063) return "Use a measured wheel angle and stable in-range ADC.";
    float degrees=msg["degrees"]; int at=0;
    while(at<s.count && s.points[at].degrees<degrees-.01f) ++at;
    if(at<s.count && fabs(s.points[at].degrees-degrees)<.01f) s.points[at]={s.adc,degrees};
    else {
      if(s.count>=MAX_POINTS) return "Maximum 15 points; remove a point first.";
      for(int i=s.count;i>at;--i) s.points[i]=s.points[i-1];
      s.points[at]={s.adc,degrees}; ++s.count;
    }
    s.steerPolarity=0; s.travelTime=0;
    event(tableValid()?"Angle table valid. Test both pulse directions, then derive polarity.":"Point recorded. Need monotonic right/center/left points to form a valid table.");
  } else if(!strcmp(action,"remove")) {
    if(!numeric(msg["degrees"],-64,64)) return "Invalid angle.";
    for(int i=0;i<s.count;++i) if(fabs(s.points[i].degrees-msg["degrees"].as<float>())<.01f) {
      for(int j=i;j<s.count-1;++j) s.points[j]=s.points[j+1]; --s.count; break;
    }
    s.steerPolarity=0; s.travelTime=0; event("Point removed; recheck the angle table.");
  } else if(!strcmp(action,"clear")) {
    s.count=0; s.steerPolarity=0; s.probe1Valid=s.probe2Valid=false; s.travelTime=0; event("Steering measurements cleared.");
  } else if(!strcmp(action,"derive")) {
    if(!s.probe1Valid || !s.probe2Valid) return "Pulse both steering inputs first.";
    s.steerPolarity=polarity(s.points,s.count,s.probe1,s.probe2);
    if(!s.steerPolarity) return "Need a monotonic angle table and opposite ADC changes of at least 8 counts.";
    event("Steering polarity derived. Test a small target before approaching limits.");
  } else if(!strcmp(action,"driveDirection")) {
    if(!msg["direction"].is<int>() || abs(msg["direction"].as<int>())!=1) return "Observe wheel rotation and choose the forward-driving input.";
    s.drivePolarity=msg["direction"]; event("Observed traction direction recorded.");
  } else if(!strcmp(action,"tuning")) {
    if(!tuningValid(msg)) return "Invalid PID tuning.";
    setTuning(msg); event("PID tuning recorded; commissioning remains unconfirmed.");
  } else if(!strcmp(action,"travel")) {
    if(!numeric(msg["seconds"],.01,10)) return "Enter measured full calibrated travel time.";
    s.travelTime=msg["seconds"]; event("Measured travel time recorded.");
  } else if(!strcmp(action,"pins")) {
    if(s.armed) return "Disable outputs before changing installed input pins.";
    if(!msg["batteryPin"].is<int>() || !msg["stopPin"].is<int>()) return "Pins must be integers.";
    int battery=msg["batteryPin"],stop=msg["stopPin"];
    if((battery!=-1 && (!adcPin(battery) || battery==POT_PIN)) || (stop!=-1 && !calibration::stopPin(stop)) || (battery>=0 && battery==stop)) return "Use distinct supported input pins; GPIO35 is reserved for steering.";
    if(s.stopPin>=0) pinMode(s.stopPin,INPUT);
    s.batteryPin=battery; s.stopPin=stop; s.batteryRaw=-1; s.batteryCount=0; s.powerRecorded=false;
    if(stop>=0) pinMode(stop,INPUT_PULLUP);
    event("Installed input pins recorded. Battery calibration must be recaptured.");
  } else if(!strcmp(action,"batterySample")) {
    if(s.batteryPin<0 || s.batteryRaw<32 || s.batteryRaw>4063 || !numeric(msg["voltage"],1,12)) return "Wire/select a battery ADC pin and enter a valid multimeter reading.";
    if(s.batteryCount>=2) s.batteryCount=0;
    s.batterySamples[s.batteryCount++]={s.batteryRaw,msg["voltage"].as<float>()}; s.powerRecorded=false;
    event("Battery voltage sample captured. Capture a second different pack voltage.");
  } else if(!strcmp(action,"power")) {
    if(s.batteryCount!=2 || !numeric(msg["dividerRatio"],1,100) || !numeric(msg["stopV"],1,12) ||
        !numeric(msg["warningV"],1,12) || !numeric(msg["fullV"],1,12)) return "Need two battery samples, divider ratio and all voltage thresholds.";
    int delta=s.batterySamples[1].adc-s.batterySamples[0].adc;
    float dv=s.batterySamples[1].voltage-s.batterySamples[0].voltage;
    if(abs(delta)<40 || fabs(dv)<.1f || dv/delta<=0) return "Use two clearly separated voltage/ADC readings with positive slope.";
    float stop=msg["stopV"],warning=msg["warningV"],full=msg["fullV"];
    if(!(full>warning && warning>stop)) return "Require full > warning > stop.";
    s.divider=msg["dividerRatio"]; float slope=dv/delta;
    s.referenceMv=slope*4095.f*1000.f/s.divider;
    s.offsetMv=(s.batterySamples[0].voltage-slope*s.batterySamples[0].adc)*1000.f/s.divider;
    s.fullV=full; s.warningV=warning; s.stopV=stop; s.powerRecorded=true;
    event("Two-point battery model recorded. Verify against the multimeter across operating voltages.");
  } else return "Unknown calibration action.";
  return nullptr;
}
void actionRoute() {
  if(server.header("X-MaxBot-Token")!=controlToken) { jsonError(403,"Reload this page to obtain control."); return; }
  if(server.arg("plain").length()>1536) { jsonError(413,"Request too large."); return; }
  StaticJsonDocument<2048> msg;
  if(deserializeJson(msg,server.arg("plain")) || !msg["seq"].is<uint32_t>()) { jsonError(400,"Invalid JSON/sequence."); return; }
  uint32_t seq=msg["seq"];
  const char* action=msg["action"]|"";
  bool stopAction=!strcmp(action,"estop") || !strcmp(action,"release") || !strcmp(action,"disarm") || !strcmp(action,"stopTest");
  // A heartbeat racing an urgent stop must never make the stop stale.
  // Replaying a stop can only remove motor authority.
  if(seq<=lastSequence && !stopAction) { jsonError(409,"Stale request; reload the page."); return; }
  if(seq>lastSequence) lastSequence=seq;
  xSemaphoreTake(stateMutex,portMAX_DELAY);
  uint32_t oldContext=s.contextSeq;
  if(strcmp(action,"heartbeat")) s.contextSeq=seq;
  const char* error=perform(msg);
  if(error) s.contextSeq=oldContext;
  char message[160]="";uint32_t revision=s.eventRevision;bool waiting=false;
  if(!error&&s.eventActionSeq==seq){snprintf(message,sizeof(message),"%s",s.event);waiting=!s.eventComplete;}
  xSemaphoreGive(stateMutex);
  if(error) jsonError(409,error);
  else { StaticJsonDocument<384> out;out["ok"]=true;out["message"]=message;out["pending"]=waiting;out["eventRevision"]=revision;jsonReply(200,out); }
}
void exportRoute() {
  State v=snapshot(); StaticJsonDocument<4096> out;
  out["car"]["wheelbaseM"]=.1685;
  JsonObject h=out.createNestedObject("hardware");
  h["driveForwardPin"]=DRIVE_IN1; h["driveReversePin"]=DRIVE_IN2;
  h["steerForwardPin"]=STEER_IN1; h["steerReversePin"]=STEER_IN2; h["steerPotPin"]=POT_PIN;
  if(v.batteryPin>=0) h["batteryPin"]=v.batteryPin; else h["batteryPin"]=nullptr;
  if(v.stopPin>=0) h["physicalStopPin"]=v.stopPin; else h["physicalStopPin"]=nullptr;
  if(v.steerPolarity) h["steerPolarity"]=v.steerPolarity; else h["steerPolarity"]=nullptr;
  if(v.drivePolarity) h["drivePolarity"]=v.drivePolarity; else h["drivePolarity"]=nullptr;
  h["driveBrakeVerified"]=v.brakeVerified;
  JsonObject pwm=h.createNestedObject("pwmCalibration");
  for(int m=0;m<2;++m) {
    JsonArray a=pwm.createNestedArray(m==0?"steeringStartPwm":"tractionStartPwm");
    for(int i=0;i<2;++i){if(v.startPwm[m][i])a.add(v.startPwm[m][i]);else a.add(nullptr);}
  }
  JsonObject st=out.createNestedObject("steering");
  // Keep partial captures in the download, even when the table is not yet valid.
  // The configuration generator and phone both reject invalid/incomplete tables.
  if(v.count>0) {
    if(v.points[0].degrees<0) st["rightDeg"]=v.points[0].degrees; else st["rightDeg"]=nullptr;
    if(v.points[v.count-1].degrees>0) st["leftDeg"]=v.points[v.count-1].degrees; else st["leftDeg"]=nullptr;
    JsonArray a=st.createNestedArray("adc"),d=st.createNestedArray("degrees");
    for(int i=0;i<v.count;++i) { a.add(v.points[i].adc); d.add(v.points[i].degrees); }
  } else { st["rightDeg"]=nullptr; st["leftDeg"]=nullptr; st["adc"]=nullptr; st["degrees"]=nullptr; }
  if(v.travelTime>0) st["travelTimeS"]=v.travelTime; else st["travelTimeS"]=nullptr;
  if(v.tuningRecorded) { st["kp"]=v.kp; st["ki"]=v.ki; st["kd"]=v.kd; st["maxPwm"]=v.maxPwm; st["deadbandDeg"]=v.deadband; }
  else for(const char* key:{"kp","ki","kd","maxPwm","deadbandDeg"}) st[key]=nullptr;
  if(v.powerRecorded) {
    JsonObject p=out.createNestedObject("power"); p["dividerRatio"]=v.divider;
    p["adcReferenceMv"]=v.referenceMv; p["adcOffsetMv"]=v.offsetMv;
    p["fullV"]=v.fullV; p["warningV"]=v.warningV; p["stopV"]=v.stopV;
  }
  out["commissioning"]["steeringMeasured"]=false; out["commissioning"]["powerVerified"]=false;
  out["commissioning"]["brakingTested"]=false;
  server.sendHeader("Content-Disposition","attachment; filename=maxbot-calibration.json"); jsonReply(200,out);
}
void setup() {
  Serial.begin(115200);
  const int pins[]={DRIVE_IN1,DRIVE_IN2,STEER_IN1,STEER_IN2};
  bool outputsReady=true;
  for(int i=0;i<4;++i) {
    pinMode(pins[i],OUTPUT); digitalWrite(pins[i],LOW);
#if ESP_ARDUINO_VERSION_MAJOR >= 3
    outputsReady=ledcAttachChannel(pins[i],10000,8,i) && outputsReady;
#else
    outputsReady=ledcSetup(i,10000,8)>0 && outputsReady; ledcAttachPin(pins[i],i);
#endif
  }
  coast(); pinMode(POT_PIN,INPUT); analogReadResolution(12); analogSetAttenuation(ADC_11db);
  stateMutex=xSemaphoreCreateMutex();
  if(!stateMutex) { Serial.println("Mutex setup failed. Outputs remain off."); while(true) delay(1000); }
  s.outputsReady=outputsReady;
  if(!outputsReady) trip("PWM_SETUP failed; outputs disabled.");
  if(xTaskCreatePinnedToCore(controlTask,"bench-control",6144,nullptr,3,nullptr,1)!=pdPASS) {
    coast(); Serial.println("Control task failed. Outputs remain off."); while(true) delay(1000);
  }
  controlToken=randomHex();
  WiFi.mode(WIFI_AP); WiFi.setSleep(false);
  if(!WiFi.softAP(AP_SSID,AP_PASSWORD,1,false,1)) {
    xSemaphoreTake(stateMutex,portMAX_DELAY); trip("Wi-Fi AP setup failed."); xSemaphoreGive(stateMutex);
    Serial.println("Wi-Fi AP setup failed; restart to retry."); return;
  }
  Serial.println("\nMaxBot CALIBRATION ONLY — wheels raised, feedback on GPIO35.");
  Serial.printf("Wi-Fi: %s\nPassword: %s\nOpen http://%s\n",AP_SSID,AP_PASSWORD,WiFi.softAPIP().toString().c_str());
  const char* headers[]={"X-MaxBot-Token"}; server.collectHeaders(headers,1);
  server.on("/",HTTP_GET,[]() {
    xSemaphoreTake(stateMutex,portMAX_DELAY);cancel("Page opened; outputs disabled.");s.armed=false;
    eventHead=eventCount=0;s.contextSeq=s.eventActionSeq=s.eventRevision=0;xSemaphoreGive(stateMutex);
    controlToken=randomHex(); lastSequence=0;
    String page=FPSTR(CALIBRATION_PAGE); page.replace("__MAXBOT_TOKEN__",controlToken);
    server.sendHeader("Cache-Control","no-store");
    server.sendHeader("Content-Security-Policy","default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' blob:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'");
    server.sendHeader("X-Frame-Options","DENY"); server.send(200,"text/html",page);
  });
  server.on("/api/state",HTTP_GET,stateRoute); server.on("/api/action",HTTP_POST,actionRoute);
  server.on("/api/export",HTTP_GET,exportRoute);
  server.onNotFound([]() { jsonError(404,"Not found."); }); server.begin();
}
void loop() { server.handleClient(); delay(1); }
