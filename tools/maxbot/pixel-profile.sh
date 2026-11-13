#!/bin/sh
# Explicit, inspectable preparation; never changes governors or thermal protection.
set -eu
ADB="${MAXBOT_ADB:-adb}"
case "${1:-inspect}" in
  inspect)
    "$ADB" shell top -b -n 1
    "$ADB" shell dumpsys meminfo org.maxbot.app
    "$ADB" shell dumpsys thermalservice
    "$ADB" shell dumpsys battery
    ;;
  stop-unused)
    ALLOWLIST="${2:?Supply a text file listing ONLY unused third-party package names}"
    THIRD_PARTY="$("$ADB" shell pm list packages -3 | tr -d '\r' | sed 's/^package://')"
    while IFS= read -r package || [ -n "$package" ]; do
      case "$package" in ''|'#'*) continue;; org.maxbot.app|com.google.ar.core|com.google.android.gms) echo "Refusing required package: $package" >&2;exit 1;; *[!a-zA-Z0-9._]*) echo 'Invalid package name' >&2;exit 1;; esac
      if ! printf '%s\n' "$THIRD_PARTY" | rg -F -x -- "$package" >/dev/null; then echo "Not an installed third-party package: $package" >&2;exit 1;fi
      echo "Force-stopping $package"
      "$ADB" shell am force-stop "$package"
    done < "$ALLOWLIST"
    ;;
  *) echo 'Usage: pixel-profile.sh inspect | stop-unused allowlist.txt' >&2;exit 2;;
esac
