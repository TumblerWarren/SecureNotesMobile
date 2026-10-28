#!/usr/bin/env bash
# Only these fixed synthetic fixture captures are collected from the debug test app.
set -u
test_result=0
./gradlew :app:connectedDebugAndroidTest || test_result=$?
mkdir -p app/build/reports/ui-screenshots
for capture in normal-library normal-notes normal-folders large-library large-notes large-folders normal-pdf large-pdf; do
  adb pull "/sdcard/Pictures/SecureNoteSyntheticTests/$capture.png" "app/build/reports/ui-screenshots/$capture.png" || true
  adb shell rm -f "/sdcard/Pictures/SecureNoteSyntheticTests/$capture.png" || true
done
# These public paths are written only by the isolated synthetic walkthrough tests.
mkdir -p app/build/reports/visual-walkthrough
adb pull /sdcard/Download/SecureNoteSyntheticWalkthrough/. app/build/reports/visual-walkthrough/ || true
adb pull /sdcard/Movies/SecureNoteSyntheticTests/. app/build/reports/visual-walkthrough/ || true
adb shell rm -rf /sdcard/Download/SecureNoteSyntheticWalkthrough /sdcard/Movies/SecureNoteSyntheticTests || true
git rev-parse HEAD > app/build/reports/visual-walkthrough/source-commit.txt
python3 tools/visual_contact_sheets.py || test_result=1
adb logcat -d -s AndroidRuntime:E > app/build/reports/runtime-errors.log || true
exit "$test_result"
