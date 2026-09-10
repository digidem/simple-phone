#!/usr/bin/env bash
# Runs the instrumented suite across the two emulators it needs.
#
# The split is not cosmetic. On a device where this app is Device Owner, the
# HOME activity, and holding lock task, Compose's test harness cannot see any
# compose hierarchy at all — even a one-line smoke test fails with "No compose
# hierarchies found". The policy tests need exactly that device; the UI tests
# need a device without it.
#
#   kiosk_aosp_30  Device Owner. Policy, lock task, install verification, PIN.
#   kiosk_ui_30    Clean. Compose UI tests.
#
# Create both from an AOSP image (not Google Play — dpm set-device-owner fails
# if any account exists):
#   sdkmanager "system-images;android-30;default;arm64-v8a"
#   avdmanager create avd -n kiosk_aosp_30 -k "system-images;android-30;default;arm64-v8a" -d pixel_5
#   avdmanager create avd -n kiosk_ui_30   -k "system-images;android-30;default;arm64-v8a" -d pixel_5
#
# Make the first one Device Owner once, after it boots:
#   adb -s "$(./tools/test.sh --serial kiosk_aosp_30)" shell dpm set-device-owner \
#     app.comapeo.kiosk/.KioskDeviceAdminReceiver
set -euo pipefail

cd "$(dirname "$0")/.."

ADB=${ADB:-adb}

# Serials are assigned by boot order, so they are looked up by AVD name instead.
serial_for() {
  local want=$1 serial name
  for serial in $("$ADB" devices | awk '/^emulator-/ {print $1}'); do
    name=$("$ADB" -s "$serial" emu avd name 2>/dev/null | head -1 | tr -d '\r')
    if [ "$name" = "$want" ]; then echo "$serial"; return 0; fi
  done
  return 1
}

if [ "${1:-}" = "--serial" ]; then
  serial_for "$2" || { echo "No running emulator named $2" >&2; exit 1; }
  exit 0
fi

DO_SERIAL=$(serial_for kiosk_aosp_30 || true)
UI_SERIAL=$(serial_for kiosk_ui_30 || true)

if [ -z "$DO_SERIAL" ]; then
  echo "kiosk_aosp_30 is not running. Start it with:" >&2
  echo "  emulator -avd kiosk_aosp_30 -no-snapshot -no-boot-anim -no-audio &" >&2
  exit 1
fi

echo "== policy and lock task on $DO_SERIAL (kiosk_aosp_30) =="
ANDROID_SERIAL="$DO_SERIAL" ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.package=app.comapeo.kiosk \
  -Pandroid.testInstrumentationRunnerArguments.notPackage=app.comapeo.kiosk.launcher

if [ -z "$UI_SERIAL" ]; then
  echo
  echo "kiosk_ui_30 is not running, so the launcher UI tests were skipped." >&2
  echo "  emulator -avd kiosk_ui_30 -no-snapshot -no-boot-anim -no-audio &" >&2
  exit 1
fi

echo "== launcher UI on $UI_SERIAL (kiosk_ui_30) =="
ANDROID_SERIAL="$UI_SERIAL" ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.package=app.comapeo.kiosk.launcher
