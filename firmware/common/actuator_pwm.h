#pragma once

namespace maxbot {
// Map a signed logical 8-bit request to the permitted physical motor duty.
// Brake logic levels bypass this function: HIGH/HIGH is not a drive voltage.
inline int motorDuty(int request, int ceiling) {
  if (ceiling < 0 || ceiling > 255) return 0;
  if (request < -255) request = -255;
  if (request > 255) request = 255;
  const int magnitude = request < 0 ? -request : request;
  const int scaled = (magnitude * ceiling + 127) / 255;
  return request < 0 ? -scaled : scaled;
}
}
