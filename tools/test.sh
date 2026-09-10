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
set -euo pipefail

cd "$(dirname "$0")/.."

DO_SERIAL=${DO_SERIAL:-emulator-5560}
UI_SERIAL=${UI_SERIAL:-emulator-5562}

echo "== policy and lock task on $DO_SERIAL =="
ANDROID_SERIAL="$DO_SERIAL" ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.package=app.comapeo.kiosk \
  -Pandroid.testInstrumentationRunnerArguments.notPackage=app.comapeo.kiosk.launcher

echo "== launcher UI on $UI_SERIAL =="
ANDROID_SERIAL="$UI_SERIAL" ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.package=app.comapeo.kiosk.launcher
