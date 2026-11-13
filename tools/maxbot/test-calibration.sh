#!/bin/sh
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
build=$(mktemp -d)
trap 'rm -rf "$build"' EXIT
c++ -std=c++17 -Wall -Wextra -Werror -I "$root/firmware/calibration" -I "$root/firmware/common" "$root/tools/maxbot/CalibrationLogicTest.cpp" -o "$build/test"
"$build/test"
c++ -std=c++17 -Wall -Wextra -Werror -I "$root/firmware/common" "$root/tools/maxbot/MotionDeadlineTest.cpp" -o "$build/deadline"
"$build/deadline"
python3 - "$root/firmware/calibration/dashboard.h" "$build/dashboard.js" <<'PY'
import pathlib,re,sys
html=pathlib.Path(sys.argv[1]).read_text()
script=re.search(r'<script>(.*?)</script>',html,re.S).group(1)
pathlib.Path(sys.argv[2]).write_text(script)
PY
node --check "$build/dashboard.js"
node "$root/tools/maxbot/CalibrationDashboardTest.cjs" "$build/dashboard.js"
