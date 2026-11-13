/* MaxBot classic ESP32: TA6586 traction IN1=4/IN2=16, steering IN1=22/IN2=23.
 * Potentiometer divider -> GPIO35 (ADC1), moved from documented GPIO14/ADC2.
 * Physical wheel-angle calibration, drive direction, battery and stop wiring
 * come from calibration.generated.h; use ../calibration/calibration.ino first.
 * Arduino ESP32 2.0.17 / 3.3.0 + ArduinoJson 6.21.5.
 */
#include <Arduino.h>
#include <ArduinoJson.h>
#include <WiFi.h>
#include <WiFiUdp.h>
#include <mbedtls/md.h>
#include <esp_timer.h>
#include <esp_arduino_version.h>
#include <atomic>
#include "../common/motion_deadline.h"
#include "../common/motion_protocol.h"
#include "actuator_pwm.generated.h"
#if __has_include("calibration.generated.h")
#include "calibration.generated.h"
#else
#error "Run python3 tools/maxbot/configure.py from the repository root first"
#endif

// Force regeneration of older calibration headers which assumed IN1=forward.
#ifndef DRIVE_POLARITY
#error "Regenerate calibration: hardware.drivePolarity must be measured"
#endif
#ifndef BATTERY_MONITORING_ENABLED
#error "Regenerate calibration: select power.batteryMonitoring explicitly"
#endif
#ifndef MAXBOT_ACTUATORS_READY
#error "Regenerate calibration for manual commissioning support"
#endif

WiFiUDP udp,stopUdp;
struct Command {
  char mode[16],session[33]; int angle,pwm; int64_t seq,epoch,issued,expiry;
  uint64_t rx,validated; uint32_t stopGeneration;
};
struct Applied {int64_t seq,epoch; uint64_t rx,validated,applied,expiry; int pwm;char reason[32];char session[33];};
QueueHandle_t commands,applications;
std::atomic<uint32_t> stopGeneration{0},appliedStopGeneration{0};
std::atomic<uint64_t> appliedStopMs{0};
std::atomic<uint32_t> applicationLoss{0},motorOverruns{0},deadlineRejected{0};
uint32_t resetGeneration=0;
uint64_t absoluteExpiry=0; int64_t motionEpoch=-1,acceptedEpoch=-1;
String stopHistory[32];uint64_t stopHistoryTime[32]{};int stopHistoryHead=0,pendingHistory=-1;
String pendingStopId; IPAddress stopPeer; uint16_t stopPort=0; uint32_t pendingStopGeneration=0;
maxbot::MotionOrder ordering;
portMUX_TYPE snapshotMux=portMUX_INITIALIZER_UNLOCKED;
struct MotorSnapshot {float angle,volts; int pot,battery,pwm; bool armed,manual,emergency; char fault[32]; int64_t seq,epoch; uint64_t expiry,sensorMs;};
MotorSnapshot snapshot{};
String bootId,activeSession,serialLine;char motorSession[33]="";
IPAddress peerIp;
uint16_t peerPort=0;
uint64_t lastCommand=0,lastTelemetry=0,lastLoop=0,stallSince=0,lastMotion=0;
int64_t lastSequence=-1;
bool armed=false,manualArmed=false,emergency=false,useUdp=false,outputsReady=false;
struct FaultText {
  char value[32]; FaultText(const char* text){strlcpy(value,text,sizeof(value));}
  FaultText& operator=(const char* text){strlcpy(value,text,sizeof(value));return *this;}
  size_t length()const{return strlen(value);}void toCharArray(char* out,size_t n)const{strlcpy(out,value,n);}
};
FaultText fault(MAXBOT_ACTUATORS_READY?"":"CALIBRATION");
float targetAngle=0,actualAngle=0,voltage=0,integral=0,lastError=0,stallAngle=0;
int drivePwm=0,potRaw=0,batteryRaw=-1;

uint64_t timeMs(){return (uint64_t)esp_timer_get_time()/1000;}
bool physicalStopReleased(){
#if STOP_PIN >= 0
  return digitalRead(STOP_PIN)==HIGH;
#else
  return true;  // No physical switch configured; software stops remain active.
#endif
}
bool batteryHealthy(){
#if BATTERY_MONITORING_ENABLED
  return isfinite(voltage)&&voltage>=BATTERY_STOP_V;
#else
  return true;
#endif
}
void writePwm(int channel,int duty){
#if ESP_ARDUINO_VERSION_MAJOR >= 3
  ledcWriteChannel(channel,duty);
#else
  ledcWrite(channel,duty);
#endif
}
String hmac(const String& message){
  unsigned char bytes[32];
  mbedtls_md_hmac(mbedtls_md_info_from_type(MBEDTLS_MD_SHA256),
    (const unsigned char*)PAIRING_KEY,strlen(PAIRING_KEY),
    (const unsigned char*)message.c_str(),message.length(),bytes);
  char out[65];for(int i=0;i<32;i++)sprintf(out+2*i,"%02x",bytes[i]);out[64]=0;return String(out);
}
bool sameMac(const String& a,const String& b){
  if(a.length()!=64||b.length()!=64)return false;
  unsigned char diff=0;for(int i=0;i<64;i++)diff|=a[i]^b[i];return diff==0;
}
void bridge(int forwardChannel,int reverseChannel,int pwm,bool brake){
#if MAXBOT_ACTUATORS_READY
  pwm=maxbot::motorDuty(pwm,forwardChannel==0?DRIVE_MAX_DUTY:STEER_MAX_DUTY);
  if(pwm>0){writePwm(reverseChannel,0);writePwm(forwardChannel,pwm);}
  else if(pwm<0){writePwm(forwardChannel,0);writePwm(reverseChannel,-pwm);}
  else {writePwm(forwardChannel,brake&&DRIVE_BRAKE_VERIFIED?255:0);writePwm(reverseChannel,brake&&DRIVE_BRAKE_VERIFIED?255:0);}
#endif
}
void halt(const char* reason,bool latch=false){
  // Zero outputs revoke the previous motion lease; STOP must not leave an old expiry armed.
  drivePwm=0;absoluteExpiry=0;integral=0;lastError=0;stallSince=0;
  lastMotion=0;
  bridge(0,1,0,true);bridge(2,3,0,false);
  if(reason[0]){fault=reason;armed=false;manualArmed=false;}
  if(latch){emergency=true;armed=false;manualArmed=false;}
}
float angleFromAdc(int reading){
  bool ascending=STEER_ADC[STEER_POINTS-1]>STEER_ADC[0];
  for(int i=1;i<STEER_POINTS;i++){
    if((ascending&&reading<=STEER_ADC[i])||(!ascending&&reading>=STEER_ADC[i])){
      float t=(float)(reading-STEER_ADC[i-1])/(STEER_ADC[i]-STEER_ADC[i-1]);
      return STEER_DEG[i-1]+t*(STEER_DEG[i]-STEER_DEG[i-1]);
    }
  }
  int i=STEER_POINTS-1;return STEER_DEG[i-1]+(float)(reading-STEER_ADC[i-1])/(STEER_ADC[i]-STEER_ADC[i-1])*(STEER_DEG[i]-STEER_DEG[i-1]);
}
void reply(JsonDocument& doc,bool network,IPAddress ip,uint16_t port){
  if(network){udp.beginPacket(ip,port);serializeJson(doc,udp);udp.endPacket();}
  else{serializeJson(doc,Serial);Serial.println();}
}
void receive(const String& input,bool network,IPAddress ip=IPAddress(),uint16_t port=0){
  StaticJsonDocument<1024> msg;
  if(input.length()>900||deserializeJson(msg,input)||strlen(PAIRING_KEY)<32)return;
  uint64_t rx=timeMs();
  if((msg["v"]|0)!=1&&(msg["v"]|0)!=2)return;
  String type=msg["type"]|"";
  if(type=="hello"){
    String nonce=msg["nonce"]|"";
    if(nonce.length()!=32||!sameMac(hmac("H|"+nonce),msg["mac"]|""))return;
    StaticJsonDocument<768> out;uint64_t helloTime=timeMs();String t=String((unsigned long long)helloTime);
    out["v"]=1;out["type"]="hello";out["boot"]=bootId;out["time_ms"]=helloTime;out["nonce"]=nonce;
    out["calibration_id"]=CALIBRATION_ID;out["rx_ms"]=rx;out["tx_ms"]=timeMs();out["sender_ms"]=msg["sender_ms"]|0LL;out["owner_epoch"]=ordering.epoch;out["owner_seq"]=ordering.sequence;out["firmware_source_id"]=FIRMWARE_SOURCE_ID;
    out["mac"]=hmac("H|"+bootId+"|"+t+"|"+nonce+"|"+CALIBRATION_ID+"|"+String((unsigned long long)rx)+"|"+String((unsigned long long)(out["tx_ms"].as<uint64_t>()))+"|"+String((long long)(out["sender_ms"].as<int64_t>()))+"|"+String((long long)ordering.epoch)+"|"+String((long long)ordering.sequence)+"|"+FIRMWARE_SOURCE_ID);
    reply(out,network,ip,port);return;
  }
  if(type!="command"||(msg["v"]|0)!=2||msg.size()!=15||String(msg["boot"]|"")!=bootId)return;
  for(const char* field:{"seq","epoch","issued_ms","expires_ms","steering_mdeg","pwm"})if(!msg[field].is<int64_t>())return;
  String session=msg["session"]|"",mode=msg["mode"]|"";
  if(session.length()!=32||String(msg["calibration_id"]|"")!=CALIBRATION_ID)return;
  int64_t seq=msg["seq"]|-1LL,epoch=msg["epoch"]|-1LL,issued=msg["issued_ms"]|-1LL,expiry=msg["expires_ms"]|-1LL;
  int angle=msg["steering_mdeg"]|INT_MAX,pwm=msg["pwm"]|INT_MAX;
  String state=msg["source_state"]|"",frame=msg["source_frame"]|"";
  if(seq<0||seq>9007199254740991LL||epoch<0||epoch>9007199254740991LL||state.length()>19||frame.length()>19||!state.length()||!frame.length())return;
  for(unsigned i=0;i<state.length();i++)if(!isDigit(state[i]))return;
  for(unsigned i=0;i<frame.length();i++)if(!isDigit(frame[i]))return;
  if(pwm < -255||pwm>255||angle<(int)(STEER_RIGHT*1000)||angle>(int)(STEER_LEFT*1000))return;
  for(unsigned i=0;i<session.length();i++)if(!isHexadecimalDigit(session[i]))return;
  char signedText[640];if(!maxbot::canonicalMotion(signedText,sizeof(signedText),bootId.c_str(),session.c_str(),epoch,seq,issued,expiry,mode.c_str(),angle,pwm,state.c_str(),frame.c_str(),CALIBRATION_ID))return;
  String canonical=signedText;
  if(!sameMac(hmac(canonical),msg["mac"]|""))return;
  uint64_t validated=timeMs();
  if(!maxbot::validCommandDeadline(validated,issued,expiry,mode.c_str())){deadlineRejected.fetch_add(1);return;}
  if(activeSession.length()&&session!=activeSession){
    MotorSnapshot current;portENTER_CRITICAL(&snapshotMux);current=snapshot;portEXIT_CRITICAL(&snapshotMux);
    if(mode!="RESET"||current.armed||current.pwm!=0||epoch<=ordering.epoch)return;
  }
  bool motion=mode=="DRIVE"||mode=="DRIVE_MANUAL";
  if(!(motion||mode=="STOP"||mode=="DISARM"||mode=="RESET"||mode=="ARM"||mode=="ARM_MANUAL"||mode=="ESTOP"))return;
  if(!motion&&pwm!=0)return;
  auto next=ordering;
  if(!next.accept(epoch,seq,motion))return;
  Command command{};mode.toCharArray(command.mode,sizeof(command.mode));session.toCharArray(command.session,sizeof(command.session));command.angle=angle;command.pwm=pwm;
  command.seq=seq;command.epoch=epoch;command.issued=issued;command.expiry=expiry;command.rx=rx;command.validated=validated;command.stopGeneration=stopGeneration.load();
  if(xQueueSend(commands,&command,0)!=pdTRUE)return;
  ordering=next;activeSession=session;acceptedEpoch=epoch;
  useUdp=network;if(network){peerIp=ip;peerPort=port;}
}
void operatorStop(){
  int length=stopUdp.parsePacket();if(length<=0)return;
  char packet[384];if(length>=int(sizeof(packet))){while(stopUdp.available())stopUdp.read();return;}
  int n=stopUdp.read(packet,sizeof(packet)-1);packet[n]=0;
  StaticJsonDocument<512> m;if(deserializeJson(m,packet))return;
  String id=m["request_id"]|"";
  if((m["v"]|0)!=1||String(m["type"]|"")!="operator_stop"||String(m["boot"]|"")!=bootId||id.length()!=32)return;
  for(unsigned i=0;i<id.length();i++)if(!isHexadecimalDigit(id[i]))return;
  // Domain-separated key: operator role can only assert stop.
  String key=hmac("operator-stop-key-v1");
  unsigned char digest[32];String canonical="S|"+bootId+"|"+id;
  mbedtls_md_hmac(mbedtls_md_info_from_type(MBEDTLS_MD_SHA256),(const unsigned char*)key.c_str(),key.length(),(const unsigned char*)canonical.c_str(),canonical.length(),digest);
  char sig[65];for(int i=0;i<32;i++)sprintf(sig+2*i,"%02x",digest[i]);sig[64]=0;
  if(!sameMac(String(sig),m["mac"]|""))return;
  uint64_t issued=strtoull(id.substring(0,16).c_str(),nullptr,16),now=timeMs();
  if(issued>now+20||now>issued+5000)return;
  pendingStopId=id;stopPeer=stopUdp.remoteIP();stopPort=stopUdp.remotePort();
  for(int i=0;i<32;i++)if(stopHistory[i]==id){pendingStopGeneration=appliedStopGeneration.load();pendingHistory=i;return;}
  pendingHistory=stopHistoryHead;stopHistoryHead=(stopHistoryHead+1)%32;stopHistory[pendingHistory]=id;stopHistoryTime[pendingHistory]=0;
  pendingStopGeneration=stopGeneration.fetch_add(1)+1;
}
void acknowledgeStop(){
  if(!pendingStopId.length()||appliedStopGeneration.load()<pendingStopGeneration)return;
  uint64_t t=stopHistoryTime[pendingHistory];if(!t){t=appliedStopMs.load();stopHistoryTime[pendingHistory]=t;}String key=hmac("operator-stop-key-v1");
  String canonical="SA|"+bootId+"|"+pendingStopId+"|"+String((unsigned long long)t);
  unsigned char digest[32];mbedtls_md_hmac(mbedtls_md_info_from_type(MBEDTLS_MD_SHA256),(const unsigned char*)key.c_str(),key.length(),(const unsigned char*)canonical.c_str(),canonical.length(),digest);
  char sig[65];for(int i=0;i<32;i++)sprintf(sig+2*i,"%02x",digest[i]);sig[64]=0;
  StaticJsonDocument<384> out;out["v"]=1;out["type"]="operator_stop_applied";out["boot"]=bootId;out["request_id"]=pendingStopId;out["applied_ms"]=t;out["mac"]=sig;
  stopUdp.beginPacket(stopPeer,stopPort);serializeJson(out,stopUdp);stopUdp.endPacket();pendingStopId="";
}
void applyCommand(const Command& c){
  uint64_t now=timeMs();
  if(!maxbot::validCommandDeadline(now,c.issued,c.expiry,c.mode)){deadlineRejected.fetch_add(1);return;}
  const char* mode=c.mode;
  if(c.epoch<motionEpoch)return;
  if(c.epoch>motionEpoch){halt("");motionEpoch=c.epoch;absoluteExpiry=0;}
  if(strcmp(mode,"ESTOP")==0)halt("ESTOP",true);
  else if(strcmp(mode,"RESET")==0){
    if(!armed&&drivePwm==0&&c.stopGeneration==stopGeneration.load()&&outputsReady&&physicalStopReleased()&&batteryHealthy()){
      emergency=false;fault="";manualArmed=false;resetGeneration=c.stopGeneration;halt("");
    }
  }else if(strcmp(mode,"DISARM")==0){armed=false;manualArmed=false;halt("");}
  else if(strcmp(mode,"STOP")==0)halt("");
  else if(strcmp(mode,"ARM")==0||strcmp(mode,"ARM_MANUAL")==0){
    bool permitted=strcmp(mode,"ARM")==0?MAXBOT_CALIBRATED:MANUAL_COMMISSIONING_ENABLED;
    if(permitted&&outputsReady&&!emergency&&!fault.length()&&physicalStopReleased()&&batteryHealthy()){
      armed=true;manualArmed=strcmp(mode,"ARM_MANUAL")==0;targetAngle=actualAngle;drivePwm=0;absoluteExpiry=0;
    }
  }else if(armed&&!emergency&&!fault.length()&&c.stopGeneration==resetGeneration&&((strcmp(mode,"DRIVE_MANUAL")==0)==manualArmed)){
    targetAngle=c.angle/1000.f;drivePwm=manualArmed?constrain(c.pwm,-MANUAL_MAX_PWM,MANUAL_MAX_PWM):c.pwm;lastMotion=now;absoluteExpiry=c.expiry;
  }else if(strcmp(mode,"DRIVE")==0||strcmp(mode,"DRIVE_MANUAL")==0)return;
  if((strcmp(mode,"DRIVE")==0||strcmp(mode,"DRIVE_MANUAL")==0)&&(!armed||emergency||fault.length()))return;
  strlcpy(motorSession,c.session,sizeof(motorSession));
  lastCommand=now;lastSequence=c.seq;
  Applied event{c.seq,c.epoch,c.rx,c.validated,now,(uint64_t)c.expiry,drivePwm};
  strlcpy(event.session,c.session,sizeof(event.session));
  if(xQueueSend(applications,&event,0)!=pdTRUE)applicationLoss.fetch_add(1);
}
void checkMotionDeadline(){
  if(armed&&absoluteExpiry&&maxbot::expired(timeMs(),absoluteExpiry)){
    // Manual gaps neutralize; autonomous expiry still disarms with a fault.
    if(manualArmed)halt("");else halt("ABSOLUTE_EXPIRY");
  }
  if(armed&&timeMs()-lastCommand>=(manualArmed?maxbot::kManualLinkMs:150))halt("WATCHDOG");
}
void motorTask(void*){
  TickType_t next=xTaskGetTickCount();char lastFault[32]="";
  for(;;){
    uint64_t start=timeMs();
    uint32_t stop=stopGeneration.load();
    if(stop!=resetGeneration){halt("OPERATOR_STOP",true);if(appliedStopGeneration.load()!=stop){appliedStopMs.store(timeMs());appliedStopGeneration.store(stop);}}
    Command command;for(int n=0;n<8&&xQueueReceive(commands,&command,0)==pdTRUE;n++)applyCommand(command);
    // The stop latch takes priority even over queued reset/arm commands.
    if(stopGeneration.load()!=resetGeneration)halt("OPERATOR_STOP",true);
    sensors();uint64_t sensorTime=timeMs();
    checkMotionDeadline();
    steeringLoop();
    if(strcmp(lastFault,fault.value)!=0){
      strlcpy(lastFault,fault.value,sizeof(lastFault));Applied local{};local.seq=lastSequence;local.epoch=motionEpoch;local.applied=timeMs();local.expiry=absoluteExpiry;local.pwm=drivePwm;strlcpy(local.reason,lastFault,sizeof(local.reason));strlcpy(local.session,motorSession,sizeof(local.session));
      if(xQueueSend(applications,&local,0)!=pdTRUE)applicationLoss.fetch_add(1);
    }
    portENTER_CRITICAL(&snapshotMux);
    snapshot.angle=actualAngle;snapshot.volts=voltage;snapshot.pot=potRaw;snapshot.battery=batteryRaw;snapshot.pwm=drivePwm;
    snapshot.armed=armed;snapshot.manual=manualArmed;snapshot.emergency=emergency;snapshot.seq=lastSequence;snapshot.epoch=motionEpoch;snapshot.expiry=absoluteExpiry;snapshot.sensorMs=sensorTime;
    fault.toCharArray(snapshot.fault,sizeof(snapshot.fault));
    portEXIT_CRITICAL(&snapshotMux);
    if(timeMs()-start>5)motorOverruns.fetch_add(1);
    vTaskDelayUntil(&next,pdMS_TO_TICKS(5));
  }
}

void sensors(){
#if MAXBOT_ACTUATORS_READY
  potRaw=analogRead(POT_PIN);
#if BATTERY_MONITORING_ENABLED
  batteryRaw=analogRead(BATTERY_PIN);
  float measured=(batteryRaw*ADC_REFERENCE_MV/4095.f+ADC_OFFSET_MV)*DIVIDER_RATIO/1000.f;
  voltage=voltage==0?measured:voltage*.9+measured*.1;
#else
  batteryRaw=-1;voltage=0;  // Protocol sentinel for unavailable; never a fake 8 V reading.
#endif
  actualAngle=angleFromAdc(potRaw);
  if(!physicalStopReleased()){halt("PHYSICAL_STOP",true);return;}
  if(potRaw<32||potRaw>4063||actualAngle<STEER_RIGHT-3||actualAngle>STEER_LEFT+3){halt("STEERING_FEEDBACK");return;}
  if(!batteryHealthy()){halt("LOW_VOLTAGE");return;}
#else
  // Compile-time safe diagnostics: no unknown pin is configured or driven.
  potRaw=-1;batteryRaw=-1;
#if POT_PIN == 32 || POT_PIN == 33 || POT_PIN == 34 || POT_PIN == 35 || POT_PIN == 36 || POT_PIN == 39
  potRaw=analogRead(POT_PIN);
#endif
#if BATTERY_MONITORING_ENABLED && (BATTERY_PIN == 32 || BATTERY_PIN == 33 || BATTERY_PIN == 34 || BATTERY_PIN == 35 || BATTERY_PIN == 36 || BATTERY_PIN == 39)
  batteryRaw=analogRead(BATTERY_PIN);
#endif
#endif
}
void steeringLoop(){
#if MAXBOT_ACTUATORS_READY
  if(!armed||emergency||fault.length()){bridge(0,1,0,true);bridge(2,3,0,false);return;}
  bridge(0,1,drivePwm*DRIVE_POLARITY,true);
  // Holding a fresh STOP never renews an old steering target.
  if(!lastMotion||timeMs()-lastMotion>(manualArmed?maxbot::kManualInputMs:100)){bridge(2,3,0,false);integral=0;return;}
  float error=targetAngle-actualAngle;
  if(fabs(error)<=STEER_DEADBAND){bridge(2,3,0,false);integral=0;stallSince=0;return;}
  integral=constrain(integral+error*.005f,-50.f,50.f);
  float effort=STEER_KP*error+STEER_KI*integral+STEER_KD*(error-lastError)/.005f;lastError=error;
  int output=(int)constrain(effort,-(float)STEER_MAX_PWM,(float)STEER_MAX_PWM);
  if((actualAngle>=STEER_LEFT&&output>0)||(actualAngle<=STEER_RIGHT&&output<0))output=0;
  bridge(2,3,output*STEER_POLARITY,false);
  if(abs(output)>STEER_MAX_PWM/3){
    if(!stallSince){stallSince=timeMs();stallAngle=actualAngle;}
    else if(fabs(actualAngle-stallAngle)>1){stallSince=timeMs();stallAngle=actualAngle;}
    else if(timeMs()-stallSince>300)halt("STEERING_STALL");
  }else stallSince=0;
#endif
}
void telemetry(){
  MotorSnapshot state;portENTER_CRITICAL(&snapshotMux);state=snapshot;portEXIT_CRITICAL(&snapshotMux);
  float actualAngle=state.angle,voltage=state.volts;int potRaw=state.pot,batteryRaw=state.battery,drivePwm=state.pwm;
  bool armed=state.armed,manualArmed=state.manual,emergency=state.emergency;String fault=state.fault;int64_t lastSequence=state.seq;
  StaticJsonDocument<1536> out;uint64_t t=timeMs();int angle=(int)round(actualAngle*1000),mv=(int)round(voltage*1000);
  out["v"]=1;out["type"]="telemetry";out["boot"]=bootId;out["session"]=activeSession;
  out["time_ms"]=t;out["seq"]=lastSequence;out["armed"]=armed;out["estop"]=emergency;
  out["steering_mdeg"]=angle;out["battery_mv"]=mv;out["pot_adc"]=potRaw;out["battery_adc"]=batteryRaw;
  out["pwm"]=drivePwm;out["fault"]=fault;out["calibrated"]=(bool)MAXBOT_CALIBRATED;
  out["manual_armed"]=manualArmed;
  String canonical="T|"+bootId+"|"+activeSession+"|"+String((unsigned long long)t)+"|"+String((long long)lastSequence)+"|"+String(armed?1:0)+"|"+String(emergency?1:0)+"|"+String(angle)+"|"+String(mv)+"|"+String(potRaw)+"|"+String(drivePwm)+"|"+fault;
  canonical+="|"+String(manualArmed?1:0);
  out["epoch"]=state.epoch;out["expiry_ms"]=state.expiry;uint32_t lost=applicationLoss.load(),overruns=motorOverruns.load(),rejected=deadlineRejected.load();out["application_loss"]=lost;out["motor_overruns"]=overruns;out["deadline_rejected"]=rejected;out["sensor_ms"]=state.sensorMs;
  canonical+="|"+String((long long)state.epoch)+"|"+String((unsigned long long)state.expiry)+"|"+String(lost)+"|"+String(overruns)+"|"+String(rejected)+"|"+String((unsigned long long)state.sensorMs);
  out["mac"]=hmac(canonical);
  if(useUdp&&peerPort)reply(out,true,peerIp,peerPort);else reply(out,false,IPAddress(),0);
}
void stopTask(void*){for(;;){operatorStop();acknowledgeStop();vTaskDelay(pdMS_TO_TICKS(2));}}
void setup(){
  Serial.begin(SERIAL_BAUD);serialLine.reserve(1024);bootId=String((uint32_t)esp_random(),HEX)+String((uint32_t)esp_random(),HEX);
#if MAXBOT_ACTUATORS_READY
  const int pins[]={DRIVE_F,DRIVE_R,STEER_F,STEER_R};
  outputsReady=true;
  for(int i=0;i<4;i++){
    pinMode(pins[i],OUTPUT);digitalWrite(pins[i],LOW);
#if ESP_ARDUINO_VERSION_MAJOR >= 3
    if(!ledcAttachChannel(pins[i],10000,8,i)){fault="PWM_SETUP";outputsReady=false;}
#else
    if(ledcSetup(i,10000,8)==0){fault="PWM_SETUP";outputsReady=false;}
    ledcAttachPin(pins[i],i);
#endif
  }
#if STOP_PIN >= 0
  pinMode(STOP_PIN,INPUT_PULLUP);
#endif
  analogReadResolution(12);analogSetAttenuation(ADC_11db);halt("");
#endif
  if(strlen(WIFI_SSID)){
#if WIFI_AP_MODE
    WiFi.mode(WIFI_AP);WiFi.setSleep(false);
    if(!WiFi.softAPConfig(IPAddress(192,168,4,1),IPAddress(192,168,4,1),IPAddress(255,255,255,0))
        || !WiFi.softAP(WIFI_SSID,WIFI_PASSWORD,1,0,4))halt("WIFI_SETUP");
#else
    WiFi.mode(WIFI_STA);WiFi.setSleep(false);WiFi.begin(WIFI_SSID,WIFI_PASSWORD);
#endif
    udp.begin(4242);stopUdp.begin(4243);
  }
  commands=xQueueCreate(8,sizeof(Command));applications=xQueueCreate(64,sizeof(Applied));
  if(!commands||!applications){halt("TASK_SETUP",true);return;}
  if(xTaskCreatePinnedToCore(motorTask,"maxbot-motor",4096,nullptr,4,nullptr,1)!=pdPASS){halt("TASK_SETUP",true);return;}
  xTaskCreatePinnedToCore(stopTask,"maxbot-stop",6144,nullptr,3,nullptr,0);
}
void loop(){

  // Bound protocol work so a serial/UDP flood cannot starve the 200 Hz loop.
  for(int n=0;n<256&&Serial.available();n++){
    char ch=Serial.read();if(ch=='\n'){receive(serialLine,false);serialLine="";}
    else if(serialLine.length()<900)serialLine+=ch;else serialLine="";
  }
  int length=udp.parsePacket();
  if(length>0){char packet[1024];if(length<1024){int n=udp.read(packet,1023);packet[n]=0;receive(String(packet),true,udp.remoteIP(),udp.remotePort());}else while(udp.available())udp.read();}
  uint64_t now=timeMs();
  Applied event;
  for(int n=0;n<8&&xQueueReceive(applications,&event,0)==pdTRUE;n++){
    StaticJsonDocument<768> out;out["v"]=1;out["type"]=event.reason[0]?"local_applied":"applied";out["boot"]=bootId;out["session"]=event.session;
    out["seq"]=event.seq;out["epoch"]=event.epoch;out["rx_ms"]=event.rx;out["validated_ms"]=event.validated;out["applied_ms"]=event.applied;out["expires_ms"]=event.expiry;out["pwm"]=event.pwm;
    String canonical="A|"+bootId+"|"+String(event.session)+"|"+String((long long)event.seq)+"|"+String((long long)event.epoch)+"|"+String((unsigned long long)event.rx)+"|"+String((unsigned long long)event.validated)+"|"+String((unsigned long long)event.applied)+"|"+String((unsigned long long)event.expiry)+"|"+String(event.pwm);
    if(event.reason[0]){out["reason"]=event.reason;canonical+="|"+String(event.reason);}
    out["mac"]=hmac(canonical);if(useUdp&&peerPort)reply(out,true,peerIp,peerPort);
  }
  if(now-lastTelemetry>=20){lastTelemetry=now;telemetry();}
}
