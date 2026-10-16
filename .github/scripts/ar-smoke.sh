#!/usr/bin/env bash
# AR screen smoke run on an emulator: each scenario in its own instrumentation process, the crash
# buffer and the app's log captured per scenario, plus a screenshot taken while the AR screen is up.
# Output goes to ar-smoke-out/ and is summarised by .github/scripts/ar-smoke-report.py.
set -u

PKG=com.alijafari.red.astronomy
TPKG=${PKG}.test
RUNNER=androidx.test.runner.AndroidJUnitRunner
CLASS=${PKG}.ArScreenLaunchSmokeTest
OUT=ar-smoke-out
mkdir -p "$OUT"

APP_APK=$(find app/build/outputs/apk/debug -maxdepth 1 -name "*-debug.apk" | head -n 1)
TEST_APK=$(find app/build/outputs/apk/androidTest/debug -maxdepth 1 -name "*-androidTest.apk" | head -n 1)
echo "app=$APP_APK test=$TEST_APK"
{
  echo "sdk=$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
  echo "model=$(adb shell getprop ro.product.model | tr -d '\r')"
  adb shell wm size | tr -d '\r'
  adb shell wm density | tr -d '\r'
  adb shell dumpsys SurfaceFlinger 2>/dev/null | grep -i -m 3 "GLES" | tr -d '\r'
} > "$OUT/device.txt" 2>&1

# Root (google_apis images allow it) to read /data/anr traces after an "App isn't responding".
adb root > "$OUT/root.txt" 2>&1 || true
sleep 3
adb wait-for-device

adb install -r "$APP_APK" > "$OUT/install.txt" 2>&1
adb install -r "$TEST_APK" >> "$OUT/install.txt" 2>&1

# run_case <label> <test method> <grant|nogrant>
run_case() {
  local label=$1
  local method=$2
  local grant=$3
  adb shell am force-stop "$PKG"
  if [ "$grant" = "grant" ]; then
    for p in android.permission.CAMERA android.permission.ACCESS_FINE_LOCATION android.permission.ACCESS_COARSE_LOCATION android.permission.POST_NOTIFICATIONS; do
      adb shell pm grant "$PKG" "$p" > /dev/null 2>&1 || true
    done
  fi
  adb logcat -c
  adb logcat -b crash -c
  adb shell 'rm -f /data/anr/* 2>/dev/null' > /dev/null 2>&1 || true
  adb shell dumpsys gfxinfo "$PKG" reset > /dev/null 2>&1 || true
  ( timeout 300 adb shell am instrument -w -r -e class "${CLASS}#${method}" "${TPKG}/${RUNNER}" > "$OUT/${label}.instrument.txt" 2>&1 ) &
  local inst=$!
  sleep 24
  adb exec-out screencap -p > "$OUT/${label}.png" 2>/dev/null || true
  sleep 4
  adb shell dumpsys gfxinfo "$PKG" > "$OUT/${label}.gfxinfo.txt" 2>&1 || true
  wait $inst
  # Main-thread stacks of any ANR during the case (newest trace file).
  ANRF=$(adb shell 'ls -t /data/anr/ 2>/dev/null | head -n 1' | tr -d '\r')
  if [ -n "$ANRF" ]; then adb shell "cat /data/anr/$ANRF" > "$OUT/${label}.anr.txt" 2>&1 || true; fi
  adb logcat -d -b crash > "$OUT/${label}.crash.txt" 2>&1
  adb logcat -d -v threadtime > "$OUT/${label}.logcat.txt" 2>&1
  echo "=== ${label} ==="
  tail -n 15 "$OUT/${label}.instrument.txt"
}

# 1. First open without runtime permissions (no camera → GL sky; permission dialog over the app).
run_case 1_persian_nopermission persianLive nogrant
# 2-5. The usual state of an installed app: camera + location granted.
run_case 2_persian_live persianLive grant
run_case 3_english_live englishLive grant
run_case 4_persian_night persianNight grant
run_case 5_english_moon_evening englishMoonEvening grant
run_case 6_persian_crescent_earthshine persianCrescentEarthshine grant
exit 0
