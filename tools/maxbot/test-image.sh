#!/bin/sh
set -eu
cd "$(dirname "$0")/../.."
TASK_JDK="${MAXBOT_JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
TASK_IMAGE_BUILD=$(mktemp -d "${TMPDIR:-/tmp}/maxbot-image.XXXXXX")
trap 'rm -rf "$TASK_IMAGE_BUILD"' EXIT
case "$(uname -s)" in Darwin) TASK_PLATFORM=darwin; TASK_LIBRARY=libmaxbot_image.dylib; TASK_LINK=-dynamiclib;; *) TASK_PLATFORM=linux; TASK_LIBRARY=libmaxbot_image.so; TASK_LINK=-shared;; esac
clang -O2 -Wall -Wextra -Werror -fPIC "$TASK_LINK" -I"$TASK_JDK/include" -I"$TASK_JDK/include/$TASK_PLATFORM" robot/android/maxbot/src/main/cpp/image_jni.c -lm -o "$TASK_IMAGE_BUILD/$TASK_LIBRARY"
"$TASK_JDK/bin/javac" -d "$TASK_IMAGE_BUILD" tools/maxbot/native-fixture/org/maxbot/app/*.java
"$TASK_JDK/bin/java" -Djava.library.path="$TASK_IMAGE_BUILD" -cp "$TASK_IMAGE_BUILD" org.maxbot.app.ImageNativeTest
