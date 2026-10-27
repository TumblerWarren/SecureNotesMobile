#!/usr/bin/env bash
# Only these fixed synthetic fixture captures are collected from the debug test app.
set -u
test_result=0
./gradlew :app:connectedDebugAndroidTest || test_result=$?
mkdir -p app/build/reports/ui-screenshots
for capture in normal-library normal-notes normal-folders large-library large-notes large-folders; do
  adb exec-out run-as com.anonymous.SecureNotesMobile.nativepreview cat "cache/ui-screenshots/$capture.png" > "app/build/reports/ui-screenshots/$capture.png" || true
done
adb logcat -d -s AndroidRuntime:E > app/build/reports/runtime-errors.log || true
exit "$test_result"
