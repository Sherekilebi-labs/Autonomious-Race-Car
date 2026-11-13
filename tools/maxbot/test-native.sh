#!/bin/sh
set -eu
cd "$(dirname "$0")/../.."
TASK_JDK="${MAXBOT_JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
TASK_BUILD_DIR="$(mktemp -d "${TMPDIR:-/tmp}/maxbot-native.XXXXXX")"
TASK_NATIVE_DIR=robot/android/maxbot/src/main/cpp
case "$(uname -s)" in Darwin) TASK_JNI_PLATFORM=darwin; TASK_LIB_NAME=libmaxbot_mpc.dylib; TASK_LINK=-dynamiclib;; *) TASK_JNI_PLATFORM=linux; TASK_LIB_NAME=libmaxbot_mpc.so; TASK_LINK=-shared;; esac
clang -O2 -fPIC "$TASK_LINK" -I"$TASK_JDK/include" -I"$TASK_JDK/include/$TASK_JNI_PLATFORM" \
  -I"$TASK_NATIVE_DIR/osqp_generated" -I"$TASK_NATIVE_DIR/osqp_generated/inc/public" -I"$TASK_NATIVE_DIR/osqp_generated/inc/private" \
  "$TASK_NATIVE_DIR/mpc_jni.c" "$TASK_NATIVE_DIR/osqp_generated/workspace.c" "$TASK_NATIVE_DIR"/osqp_generated/src/*.c \
  -lm -o "$TASK_BUILD_DIR/$TASK_LIB_NAME"
find robot/android/maxbot-core/src -name '*.java' -print > "$TASK_BUILD_DIR/sources.txt"
printf '%s\n' robot/android/maxbot/src/main/java/org/maxbot/app/NativeMpc.java tools/maxbot/NativeControllerTest.java >> "$TASK_BUILD_DIR/sources.txt"
"$TASK_JDK/bin/javac" -d "$TASK_BUILD_DIR" @"$TASK_BUILD_DIR/sources.txt"
"$TASK_JDK/bin/java" -Djava.library.path="$TASK_BUILD_DIR" -cp "$TASK_BUILD_DIR" org.maxbot.core.NativeControllerTest
