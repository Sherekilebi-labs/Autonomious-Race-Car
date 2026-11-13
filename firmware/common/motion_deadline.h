#pragma once
#include <stdint.h>
namespace maxbot {
// Shared with host tests. Times are ESP boot-local milliseconds, never receipt-relative TTLs.
inline bool validDeadline(int64_t now, int64_t issued, int64_t expiry) {
  return issued >= 0 && expiry > issued && expiry-issued <= 100 && issued-now <= 20 && now < expiry;
}
inline bool expired(int64_t now, int64_t expiry) { return expiry <= 0 || now >= expiry; }
struct MotionOrder {
  int64_t epoch=-1, sequence=-1;
  bool accept(int64_t nextEpoch, int64_t nextSequence, bool motion) {
    if(nextEpoch<epoch || nextSequence<=sequence || (motion && nextEpoch!=epoch)) return false;
    epoch=nextEpoch;sequence=nextSequence;return true;
  }
};
}
