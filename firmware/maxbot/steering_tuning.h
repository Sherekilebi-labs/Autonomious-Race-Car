#pragma once
// Included after the runtime's motor state and transport helpers are declared.
#include <Preferences.h>
#include "../common/steering_controller.h"
// Settings I/O runs only on loopTask. Fixed reusable documents avoid nested stack exhaustion.
WiFiUDP tuningUdp;
StaticJsonDocument<1536> steeringRequestJson,steeringReplyJson;
maxbot::SteeringSettings steeringSettings{int(STEER_KP*1000),int(STEER_KI*1000),int(STEER_KD*1000),int(STEER_DEADBAND*1000),0,STEER_MAX_PWM,12000};
maxbot::SteeringSettings savedSteeringSettings{};
maxbot::SteeringMemory steeringMemory;
maxbot::SteeringFeedback steeringFeedback;
maxbot::SteeringResult steeringResult;
std::atomic<bool> tuningBusy{false},tuningSafe{false};
bool steeringSaved=false,tuningPending=false,pendingTuningSerial=false;
uint64_t steeringTimeUs=0,lastTuningIssued=0;
String pendingTuningId,pendingTuningOp,lastTuningId,lastTuningReply;
IPAddress tuningPeer;uint16_t tuningPort;
maxbot::SteeringSettings pendingSteeringSettings;

void loadSteeringSettings(){
  Preferences p;if(!p.begin("maxbot-steer",true))return;
  maxbot::SteeringRecord record{};
  bool read=p.getBytesLength("pid-v1")==sizeof(record)&&p.getBytes("pid-v1",&record,sizeof(record))==sizeof(record);p.end();
  if(read&&maxbot::validSteeringRecord(record,CALIBRATION_ID)){steeringSettings=record.settings;savedSteeringSettings=record.settings;steeringSaved=true;}
}
bool saveSteeringSettings(const maxbot::SteeringSettings& settings){
  Preferences p;if(!p.begin("maxbot-steer",false))return false;
  auto record=maxbot::steeringRecord(settings,CALIBRATION_ID);maxbot::SteeringRecord verify{};
  bool good=p.putBytes("pid-v1",&record,sizeof(record))==sizeof(record)&&p.getBytes("pid-v1",&verify,sizeof(verify))==sizeof(verify)&&
    maxbot::validSteeringRecord(verify,CALIBRATION_ID)&&maxbot::sameSettings(settings,verify.settings);p.end();return good;
}
void tuningReply(const String& id,const String& status,IPAddress ip,uint16_t port,bool retain=false,bool serialReply=false){
  MotorSnapshot state;portENTER_CRITICAL(&snapshotMux);state=snapshot;portEXIT_CRITICAL(&snapshotMux);
  const auto& s=steeringSettings;auto& out=steeringReplyJson;out.clear();uint64_t t=timeMs();
  int target=std::lround(state.target*1000),angle=std::lround(state.angle*1000),p=std::lround(state.steerP*1000),i=std::lround(state.steerI*1000),d=std::lround(state.steerD*1000);
  out["v"]=1;out["type"]="steering_tuning_reply";out["boot"]=bootId;out["request_id"]=id;out["status"]=status;out["time_ms"]=t;
  out["kp_milli"]=s.kpMilli;out["ki_milli"]=s.kiMilli;out["kd_milli"]=s.kdMilli;out["deadband_mdeg"]=s.deadbandMdeg;out["min_pwm"]=s.minPwm;out["max_pwm"]=s.maxPwm;out["filter_millihz"]=s.filterMilliHz;
  bool persisted=steeringSaved&&maxbot::sameSettings(s,savedSteeringSettings);
  out["saved"]=persisted;out["armed"]=state.armed;out["drive_pwm"]=state.pwm;out["target_mdeg"]=target;out["actual_mdeg"]=angle;out["steering_pwm"]=state.steerPwm;
  out["p_milli"]=p;out["i_milli"]=i;out["d_milli"]=d;out["pot_adc"]=state.pot;
  String canonical="STA|"+bootId+"|"+id+"|"+status+"|"+String((unsigned long long)t)+"|"+String(s.kpMilli)+"|"+String(s.kiMilli)+"|"+String(s.kdMilli)+"|"+String(s.deadbandMdeg)+"|"+String(s.minPwm)+"|"+String(s.maxPwm)+"|"+String(s.filterMilliHz)+"|"+String(persisted?1:0)+"|"+String(state.armed?1:0)+"|"+String(state.pwm)+"|"+String(target)+"|"+String(angle)+"|"+String(state.steerPwm)+"|"+String(p)+"|"+String(i)+"|"+String(d)+"|"+String(state.pot);
  int low=std::lround(maxbot::steeringFeedbackLow(STEER_RIGHT)*1000),high=std::lround(maxbot::steeringFeedbackHigh(STEER_LEFT)*1000);
  out["diagnostics_version"]=2;out["pid_active"]=state.pidActive;out["feedback_valid"]=state.feedbackValid;
  out["feedback_min_mdeg"]=low;out["feedback_max_mdeg"]=high;out["feedback_rejects"]=state.feedbackRejects;
  int commandLow=std::lround(STEER_RIGHT*1000),commandHigh=std::lround(STEER_LEFT*1000);
  out["command_min_mdeg"]=commandLow;out["command_max_mdeg"]=commandHigh;
  canonical+="|2|"+String(state.pidActive?1:0)+"|"+String(state.feedbackValid?1:0)+"|"+String(low)+"|"+String(high)+"|"+String(state.feedbackRejects)+"|"+String(commandLow)+"|"+String(commandHigh);
  out["mac"]=hmac(canonical);String text;serializeJson(out,text);
  if(retain){lastTuningId=id;lastTuningReply=text;}
  if(serialReply){Serial.println(text);return;}
  tuningUdp.beginPacket(ip,port);tuningUdp.print(text);tuningUdp.endPacket();
}
void receiveSteeringTuning(const String& text,bool serialReply,IPAddress ip,uint16_t port){
  auto& m=steeringRequestJson;m.clear();
  if(deserializeJson(m,text)||(m["v"]|0)!=1||String(m["type"]|"")!="steering_tuning")return;
  String id=m["request_id"]|"",op=m["operation"]|"",boot=m["boot"]|"";
  if(id.length()!=32||!(op=="get"||op=="apply"||op=="save"))return;
  for(unsigned j=0;j<id.length();j++)if(!isHexadecimalDigit(id[j]))return;
  for(const char* field:{"issued_ms","expires_ms"})if(!m[field].is<int64_t>())return;
  for(const char* field:{"kp_milli","ki_milli","kd_milli","deadband_mdeg","min_pwm","max_pwm","filter_millihz"})if(!m[field].is<int32_t>())return;
  int64_t issued=m["issued_ms"],expiry=m["expires_ms"];
  maxbot::SteeringSettings s{m["kp_milli"].as<int32_t>(),m["ki_milli"].as<int32_t>(),m["kd_milli"].as<int32_t>(),m["deadband_mdeg"].as<int32_t>(),m["min_pwm"].as<int32_t>(),m["max_pwm"].as<int32_t>(),m["filter_millihz"].as<int32_t>()};
  String canonical="ST|"+boot+"|"+id+"|"+op+"|"+String((long long)issued)+"|"+String((long long)expiry)+"|"+String(s.kpMilli)+"|"+String(s.kiMilli)+"|"+String(s.kdMilli)+"|"+String(s.deadbandMdeg)+"|"+String(s.minPwm)+"|"+String(s.maxPwm)+"|"+String(s.filterMilliHz);
  if(!sameMac(hmac(canonical),m["mac"]|""))return;
  if(op=="get"){tuningReply(id,"ok",ip,port,false,serialReply);return;}
  uint64_t now=timeMs();if(boot!=bootId||issued<0||expiry<=issued||expiry-issued>1500||uint64_t(issued)>now+20||uint64_t(expiry)<=now)return;
  if(id==lastTuningId){if(serialReply)Serial.println(lastTuningReply);else{tuningUdp.beginPacket(ip,port);tuningUdp.print(lastTuningReply);tuningUdp.endPacket();}return;}
  if(uint64_t(issued)<=lastTuningIssued)return;
  if(!maxbot::validSteeringSettings(s)){tuningReply(id,"invalid",ip,port,false,serialReply);return;}
  MotorSnapshot state;portENTER_CRITICAL(&snapshotMux);state=snapshot;portEXIT_CRITICAL(&snapshotMux);
  if(state.armed||state.pwm!=0){tuningReply(id,"disarm_required",ip,port,false,serialReply);return;}
  if(tuningPending){if(id!=pendingTuningId)tuningReply(id,"busy",ip,port,false,serialReply);return;}
  pendingTuningSerial=serialReply;lastTuningIssued=issued;pendingSteeringSettings=s;pendingTuningId=id;pendingTuningOp=op;tuningPeer=ip;tuningPort=port;
  tuningSafe.store(false);tuningPending=true;tuningBusy.store(true);
}

void steeringTuning(){
  if(tuningPending&&tuningSafe.load()){
    bool good=pendingTuningOp!="save"||saveSteeringSettings(pendingSteeringSettings);
    if(good){steeringSettings=pendingSteeringSettings;if(pendingTuningOp=="save"){savedSteeringSettings=steeringSettings;steeringSaved=true;}}
    tuningReply(pendingTuningId,good?(pendingTuningOp=="save"?"saved":"applied"):"save_failed",tuningPeer,tuningPort,true,pendingTuningSerial);
    tuningPending=false;tuningBusy.store(false);
  }
  int length=tuningUdp.parsePacket();if(length<=0)return;
  char bytes[1024];if(length>=int(sizeof(bytes))){while(tuningUdp.available())tuningUdp.read();return;}
  int n=tuningUdp.read(bytes,sizeof(bytes)-1);bytes[n]=0;
  receiveSteeringTuning(String(bytes),false,tuningUdp.remoteIP(),tuningUdp.remotePort());
}
