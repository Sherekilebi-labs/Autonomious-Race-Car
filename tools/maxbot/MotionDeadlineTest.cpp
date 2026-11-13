#include <cassert>
#include <cstdio>
#include "motion_deadline.h"
#include "motion_protocol.h"
#include <cstring>
int main(){
  using namespace maxbot;
  assert(validDeadline(1099,1000,1100));assert(!validDeadline(1100,1000,1100));
  assert(!validDeadline(1120,1000,1100)); // validation followed by queue delay cannot renew
  assert(!validDeadline(900,1000,1100));assert(!validDeadline(1000,1000,1101));
  assert(!validDeadline(1000,-1,1090));assert(expired(1100,1100));
  MotionOrder order;assert(!order.accept(0,1,true));assert(order.accept(0,1,false));
  assert(order.accept(0,2,true));assert(!order.accept(0,2,true));assert(!order.accept(1,3,true));
  assert(order.accept(1,3,false));assert(!order.accept(0,4,true));assert(order.accept(1,4,true));
  for(int i=5;i<100000;i++){assert(order.accept(1,i,true));assert(expired(2000,1100));}
  char canonical[640];assert(canonicalMotion(canonical,sizeof(canonical),"0123456789abcdef","aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",3,42,1000,1080,"DRIVE",12000,120,"99","88","bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"));
  const char* expected="2|0123456789abcdef|aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa|3|42|1000|1080|DRIVE|12000|120|99|88|bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
  assert(strcmp(canonical,expected)==0);char small[4];assert(!canonicalMotion(small,sizeof(small),"b","s",0,1,1000,1100,"STOP",0,0,"0","0","hash"));
  puts("PASS motion expiry, queue delay, replay/epoch and flood invariants (host logic only)");
}
