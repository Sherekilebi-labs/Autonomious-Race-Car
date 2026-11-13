#!/bin/sh
set -eu
cd "$(dirname "$0")/../.."
TASK_JDK="${MAXBOT_JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
if [ -x "$TASK_JDK/bin/javac" ]; then JAVA_COMPILER="$TASK_JDK/bin/javac"; JAVA_RUNNER="$TASK_JDK/bin/java"; else JAVA_COMPILER=javac; JAVA_RUNNER=java; fi
TASK_BUILD_DIR="$(mktemp -d "${TMPDIR:-/tmp}/maxbot-core.XXXXXX")"
find robot/android/maxbot-core/src -name '*.java' -print > "$TASK_BUILD_DIR/sources.txt"
"$JAVA_COMPILER" -d "$TASK_BUILD_DIR" @"$TASK_BUILD_DIR/sources.txt"
"$JAVA_RUNNER" -ea -cp "$TASK_BUILD_DIR" org.maxbot.core.CoreTest
