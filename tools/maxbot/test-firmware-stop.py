#!/usr/bin/env python3
"""Compile real firmware halt/applyCommand bodies against host actuator/queue stubs."""
import pathlib, subprocess, tempfile
ROOT = pathlib.Path(__file__).resolve().parents[2]
source = (ROOT / 'firmware/maxbot/maxbot.ino').read_text()
def function(name):
    start = source.index('void ' + name + '(')
    brace = source.index('{', start)
    depth = 1
    end = brace + 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]
harness = r'''
#include <cassert>
#include <atomic>
#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include "motion_deadline.h"
struct Command {char mode[16],session[33];int angle,pwm;int64_t seq,epoch,issued,expiry;uint64_t rx,validated;uint32_t stopGeneration;};
struct Applied {int64_t seq,epoch;uint64_t rx,validated,applied,expiry;int pwm;char reason[32];char session[33];};
struct Fault {std::string value;Fault& operator=(const char* s){value=s;return *this;}size_t length()const{return value.size();}} fault;
std::atomic<uint32_t> stopGeneration{0},deadlineRejected{0},applicationLoss{0};
uint32_t resetGeneration=0;
uint64_t absoluteExpiry=0,lastMotion=0,lastCommand=0,stallSince=0,nowMs=10;
int64_t motionEpoch=0,lastSequence=-1;
int drivePwm=0;float integral=0,lastError=0,targetAngle=0,actualAngle=0;
bool armed=true,manualArmed=true,emergency=false,outputsReady=true;
char motorSession[33]="";void* applications=nullptr;
constexpr bool MAXBOT_CALIBRATED=true,MANUAL_COMMISSIONING_ENABLED=true;
constexpr int MANUAL_MAX_PWM=255,pdTRUE=1;
uint64_t timeMs(){return nowMs;}
bool physicalStopReleased(){return true;}bool batteryHealthy(){return true;}
void bridge(int,int,int,bool){}
int constrain(int v,int lo,int hi){return std::clamp(v,lo,hi);}
size_t strlcpy(char* out,const char* in,size_t n){if(n)std::snprintf(out,n,"%s",in);return std::strlen(in);}
int xQueueSend(void*,const Applied*,int){return pdTRUE;}
'''
harness += '\n' + function('halt') + '\n' + function('applyCommand') + '\n' + function('checkMotionDeadline') + r'''
Command command(const char* mode,int pwm=0){Command c{};strlcpy(c.mode,mode,sizeof(c.mode));strlcpy(c.session,"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",sizeof(c.session));c.pwm=pwm;c.issued=nowMs;c.expiry=nowMs+80;c.seq=lastSequence+1;c.epoch=motionEpoch;c.stopGeneration=stopGeneration.load();return c;}
int main(){
  applyCommand(command("DRIVE_MANUAL",160));assert(drivePwm==160&&absoluteExpiry==90);
  nowMs=20;applyCommand(command("STOP"));assert(drivePwm==0&&absoluteExpiry==0&&armed);
  nowMs=200;assert(!absoluteExpiry); // A stopped car cannot inherit the previous motion expiry.
  applyCommand(command("DISARM"));applyCommand(command("ARM_MANUAL"));assert(armed&&manualArmed&&absoluteExpiry==0&&drivePwm==0);
  applyCommand(command("DRIVE_MANUAL",120));assert(absoluteExpiry==280);
  nowMs=210;applyCommand(command("ARM_MANUAL"));assert(armed&&drivePwm==0&&absoluteExpiry==0);
  applyCommand(command("DRIVE_MANUAL",100));assert(absoluteExpiry==290);
  nowMs=290;checkMotionDeadline();
  assert(armed&&manualArmed&&drivePwm==0&&absoluteExpiry==0&&fault.length()==0);
  nowMs=300;applyCommand(command("DRIVE_MANUAL",80));assert(armed&&drivePwm==80); // Fresh input resumes without reset.
  nowMs=800;checkMotionDeadline();assert(!armed&&fault.value=="WATCHDOG"&&drivePwm==0);
  applyCommand(command("RESET"));applyCommand(command("ARM"));assert(armed&&!manualArmed);
  applyCommand(command("DRIVE",100));nowMs=880;checkMotionDeadline();
  assert(!armed&&fault.value=="ABSOLUTE_EXPIRY"&&drivePwm==0);
  applyCommand(command("ARM_MANUAL"));assert(!armed); // Arm never clears a genuine latched fault.
  applyCommand(command("RESET"));assert(!armed&&fault.length()==0&&absoluteExpiry==0);
  applyCommand(command("ARM_MANUAL"));assert(armed&&manualArmed&&absoluteExpiry==0);
  auto late=command("DRIVE_MANUAL",255);nowMs=late.expiry;auto rejects=deadlineRejected.load();applyCommand(late);
  assert(drivePwm==0&&absoluteExpiry==0&&deadlineRejected.load()==rejects+1);
  puts("PASS real firmware STOP/disarm/rearm deadline lifecycle, fault/reset and expired-motion rejection (host stubs)");
}
'''
with tempfile.TemporaryDirectory(prefix='maxbot-stop-') as tmp:
    test = pathlib.Path(tmp) / 'stop.cpp'
    test.write_text(harness)
    binary = pathlib.Path(tmp) / 'stop'
    subprocess.run(['c++', '-std=c++17', '-Wall', '-Wextra', '-Werror', '-Wno-missing-field-initializers', '-I', str(ROOT / 'firmware/common'), str(test), '-o', str(binary)], check=True)
    subprocess.run([str(binary)], check=True)
