#pragma once
#include <stdint.h>
#include <inttypes.h>
#include <stdio.h>
#include <stddef.h>
namespace maxbot {
inline bool canonicalMotion(char* output,size_t capacity,const char* boot,const char* session,int64_t epoch,int64_t sequence,int64_t issued,int64_t expiry,const char* mode,int angle,int pwm,const char* state,const char* frame,const char* calibration){
  int n=snprintf(output,capacity,"2|%s|%s|%" PRId64 "|%" PRId64 "|%" PRId64 "|%" PRId64 "|%s|%d|%d|%s|%s|%s",boot,session,epoch,sequence,issued,expiry,mode,angle,pwm,state,frame,calibration);
  return n>=0&&(size_t)n<capacity;
}
}
