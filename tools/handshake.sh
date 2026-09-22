#!/usr/bin/env bash
# Runs the setup wizard's own provisioning flow against the kiosk on an
# emulator, the way a trainer's scan does it in the field: ManagedProvisioning
# downloads /dpc.apk from the :handshake app's server, checks the kiosk's
# signature, installs it, makes it Device Owner, and the kiosk then fetches its
# config, installs the payload and posts a setup report. No camera, no hotspot.
# See docs/handshake-and-api-matrix.md §2.
#
# It needs an AVD that has never had a device owner, so make one for this and
# wipe it before every run:
#   avdmanager create avd -n kiosk_wizard_30 \
#     -k "system-images;android-30;default;arm64-v8a" -d pixel_5
#   emulator -avd kiosk_wizard_30 -no-snapshot -no-boot-anim -no-audio -wipe-data &
#
# Then:
#   tools/handshake.sh                       # kiosk_wizard_30
#   KIOSK_WIZARD_AVD=kiosk_wizard_34 tools/handshake.sh
#   tools/handshake.sh --case wrong-checksum      # the wizard must refuse the download
#   tools/handshake.sh --case wrong-config-hash   # the kiosk must refuse the config
#
# Never kiosk_aosp_30 or kiosk_ui_30: this leaves a provisioned device behind.
set -euo pipefail

cd "$(dirname "$0")/.."

# The interactive `adb` on a developer machine may be a multi-device wrapper
# that ignores -s, so the SDK binary is preferred wherever one is found.
ADB=${ADB:-}
if [ -z "$ADB" ]; then
  for candidate in "$HOME/Library/Android/sdk/platform-tools/adb" \
    "${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}/platform-tools/adb"; do
    if [ -x "$candidate" ]; then ADB=$candidate; break; fi
  done
fi
ADB=${ADB:-adb}
AVD=${KIOSK_WIZARD_AVD:-kiosk_wizard_30}
APK=handshake/build/outputs/apk/debug/handshake-debug.apk
PACKAGE=org.awana.kiosk.handshake
KIOSK=org.awana.kiosk
SAMPLE=org.awana.kiosk.sample
PORT=8080
OUT=build/handshake

say() { echo "$@" >&2; }
die() { say "$@"; exit 1; }

CASE=positive
while [ $# -gt 0 ]; do
  case $1 in
    --case) CASE=${2:-}; shift 2 || true ;;
    *) die "Unknown argument $1. Use --case positive|wrong-checksum|wrong-config-hash." ;;
  esac
done

# Each case corrupts one field of the setup code in place and has its own idea
# of how far the wizard is meant to get; see docs/handshake-and-api-matrix.md §2.
SCAN_EXTRA=()
case $CASE in
  positive) ;;
  wrong-checksum) SCAN_EXTRA=(--ez wrongChecksum true) ;;
  wrong-config-hash) SCAN_EXTRA=(--ez wrongConfigHash true) ;;
  *) die "Unknown case '$CASE'. Use positive, wrong-checksum or wrong-config-hash." ;;
esac

# Serials are assigned by boot order, so they are looked up by AVD name, as in
# tools/test.sh.
serial_for() {
  local want=$1 serial name
  for serial in $("$ADB" devices | awk '/^emulator-/ {print $1}'); do
    name=$("$ADB" -s "$serial" emu avd name 2>/dev/null | head -1 | tr -d '\r')
    if [ "$name" = "$want" ]; then echo "$serial"; return 0; fi
  done
  return 1
}

sh_() { "$ADB" -s "$SERIAL" shell "$@" 2>/dev/null | tr -d '\r'; }

# The package named as device owner, or nothing. Read from the Device Owner
# block alone: "package=" appears under every enabled admin too.
# Every reader here consumes its whole input: with pipefail, a grep -q or an
# awk exit that closes the pipe early fails the adb side with SIGPIPE, which
# on newer levels' larger dumps reads as "not found".
device_owner() {
  sh_ dumpsys device_policy | awk '
    /^[[:space:]]*Device Owner:/ { inblock = 1; next }
    inblock && !found && /^[[:space:]]*package=/ { sub(/^[[:space:]]*package=/, ""); print; found = 1 }
    inblock && /^[[:space:]]*$/ { inblock = 0 }
  '
}

boot_completed() { [ "$(sh_ getprop sys.boot_completed)" = "1" ]; }

kiosk_is_owner() { [ "$(device_owner || true)" = "$KIOSK" ]; }

# Before Android 13 there is no PROVISIONING_ALLOW_OFFLINE, so a device that is
# not on a network yet gets the wizard's Wi-Fi picker instead of the consent
# screen. A freshly wiped AVD takes a while to associate with AndroidWifi.
online() { sh_ dumpsys connectivity | grep VALIDATED >/dev/null; }

# HOST_PORT is whatever adb allocated for this device; a fixed host port would
# be shared by every emulator on the machine and read the wrong server.
state() { curl -sf --max-time 5 "http://localhost:$HOST_PORT/state"; }

have_report() { state | grep '"reports":\["' >/dev/null; }

# ManagedProvisioning finished its activity and returned to the trigger.
wizard_returned() { state | grep 'returned result' >/dev/null; }

logcat_has() { "$ADB" -s "$SERIAL" logcat -d -s "$1" 2>/dev/null | grep -F "$2" >/dev/null; }

# What AOSP's VerifyPackageTask logs when none of the downloaded APK's
# signatures hashes to the checksum the setup code named. Its own wording,
# read out of ManagedProvisioning.apk rather than guessed at.
REFUSAL="Provided hash does not match any signature hash."
download_refused() { logcat_has ManagedProvisioning "$REFUSAL"; }

# The kiosk's account of a config that did not match the hash the wizard
# carried. ConfigFetch returns the mismatch rather than logging it, so this is
# the line the service writes when it gives up; the reason itself only reaches
# the launcher and the telemetry extras.
config_refused() { logcat_has ProvisioningService "Could not obtain the deployment config"; }

# The screen as uiautomator sees it, into $1; empty if the dump failed.
# Removed first: a dump that fails mid-animation would otherwise leave the
# previous screen to be read as the current one.
dump_window() {
  rm -f "$1"
  sh_ rm -f /sdcard/handshake-dump.xml >/dev/null 2>&1 || true
  sh_ uiautomator dump /sdcard/handshake-dump.xml >/dev/null 2>&1 || true
  sh_ cat /sdcard/handshake-dump.xml > "$1" 2>/dev/null || true
}

# The wizard's screens put their action button bottom-right and give it no
# resource-id, so it is found by position: the enabled, clickable Button in
# the wizard's window furthest down, and of those furthest right — a footer's
# "back" sits at the same height on the left. Prints "x y label".
bottom_button() {
  # A dump can fail while the screen animates ("could not get idle state");
  # the next loop round tries again.
  dump_window "$OUT/window.xml"
  if [ ! -s "$OUT/window.xml" ]; then echo "$(date +%T) no dump" >> "$OUT/taps.log"; return 0; fi
  python3 - "$OUT/window.xml" <<'PY' | tee -a "$OUT/taps.log"
import re, sys

xml = open(sys.argv[1], encoding="utf-8", errors="replace").read()
best = None
for tag in re.findall(r"<node\b[^>]*>", xml):
    if "android.widget.Button" not in tag or 'clickable="true"' not in tag:
        continue
    # The wizard's own screens and the Wi-Fi picker it borrows from Settings;
    # the kiosk's compliance screen is not ours to tap.
    if not re.search(r'package="com\.android\.(managedprovisioning|settings)"', tag):
        continue
    # "Next" is shown disabled while the download and install run.
    if 'enabled="false"' in tag:
        continue
    bounds = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', tag)
    if not bounds:
        continue
    x1, y1, x2, y2 = map(int, bounds.groups())
    if x2 - x1 < 10 or y2 - y1 < 10:
        continue
    label = re.search(r'text="([^"]*)"', tag)
    found = ((y2, x2), (x1 + x2) // 2, (y1 + y2) // 2, label.group(1) if label else "")
    if best is None or found[0] > best[0]:
        best = found
if best:
    print(best[1], best[2], best[3])
PY
}

# Every line of text on the screen $1 holds, one node per line, so a failure
# report can quote what a trainer would have been looking at.
screen_text() {
  python3 - "$1" <<'PY'
import re, sys

try:
    xml = open(sys.argv[1], encoding="utf-8", errors="replace").read()
except OSError:
    raise SystemExit
seen = []
for tag in re.findall(r"<node\b[^>]*>", xml):
    for attribute in ("text", "content-desc"):
        found = re.search(r'\b%s="([^"]+)"' % attribute, tag)
        if found and found.group(1) not in seen:
            seen.append(found.group(1))
for line in seen:
    print(line)
PY
}

# The clickable nodes the package $2 is showing on the screen $1, one per line.
# Compose publishes its buttons as bare enabled Views — no resource id, no
# class of their own, the label in a child node — so they can be counted but
# not read, and the caller quotes screen_text alongside the count.
clickable_nodes() {
  python3 - "$1" "$2" <<'PY'
import re, sys

try:
    xml = open(sys.argv[1], encoding="utf-8", errors="replace").read()
except OSError:
    raise SystemExit
for tag in re.findall(r"<node\b[^>]*>", xml):
    if 'clickable="true"' not in tag or 'enabled="true"' not in tag:
        continue
    if 'package="%s"' % sys.argv[2] not in tag:
        continue
    bounds = re.search(r'bounds="(\[[^"]*\])"', tag)
    print(bounds.group(1) if bounds else "?")
PY
}

# Taps whatever the wizard is asking for until $2 says its part is over, which
# on a passing run is when it hands back to the trigger: after the education
# screen on Android 11, after the kiosk's compliance activity from 12. The
# screens differ by level (consent, education, "belongs to your organization"),
# so they are not counted, only tapped while one is up. $1 seconds to allow.
drive_wizard() {
  local deadline=$((SECONDS + $1)) done_when=$2 found last= rest x y label taps=0
  while [ "$SECONDS" -lt "$deadline" ]; do
    if "$done_when"; then return 0; fi
    found=$(bottom_button || true)
    if [ -n "$found" ]; then
      x=${found%% *}
      rest=${found#* }
      y=${rest%% *}
      label=${rest#* }
      [ "$found" = "$last" ] || say "   tapping the wizard at $x,$y: \"$label\""
      last=$found
      sh_ input tap "$x" "$y" >/dev/null
      taps=$((taps + 1))
      sleep 4
    else
      sleep 2
    fi
  done
  say "   the wizard did not finish in time ($taps taps)"
  return 1
}

# $1 seconds, $2 what it is, rest: a command whose success ends the wait.
wait_until() {
  local deadline=$((SECONDS + $1)) what=$2
  shift 2
  while [ "$SECONDS" -lt "$deadline" ]; do
    if "$@" >/dev/null 2>&1; then return 0; fi
    sleep 3
  done
  say "   timed out waiting for $what"
  return 1
}

# --- set up ------------------------------------------------------------------

mkdir -p "$OUT"
SERIAL=$(serial_for "$AVD" || true)
[ -n "$SERIAL" ] || die "No running emulator named $AVD. Start it with:
  ~/Library/Android/sdk/emulator/emulator -avd $AVD -no-snapshot -no-boot-anim -no-audio -wipe-data &"
say "== $AVD on $SERIAL =="
[ "$CASE" = positive ] || say "== the $CASE case =="

[ -s build/platform-key/platform.p12 ] || tools/platform-key.sh >/dev/null
./gradlew -q :handshake:assembleDebug
[ -s "$APK" ] || die "No $APK"

owner=$(device_owner || true)
[ -z "$owner" ] || die "$AVD already has a device owner ($owner). The wizard can only
provision a device that has never had one: wipe the AVD and boot it again.
  $ADB -s $SERIAL emu kill
  ~/Library/Android/sdk/emulator/emulator -avd $AVD -no-snapshot -no-boot-anim -no-audio -wipe-data &"

say "-- putting the device back to 'setup not finished'"
# The wizard must download the kiosk rather than find it installed.
"$ADB" -s "$SERIAL" uninstall "$KIOSK" >/dev/null 2>&1 || true
"$ADB" -s "$SERIAL" root >/dev/null
"$ADB" -s "$SERIAL" wait-for-device
# adbd restarts to become root; a shell that gets in first is not root and the
# edit below silently does nothing.
is_root() { [ "$(sh_ id -u)" = "0" ]; }
wait_until 60 "adb to be root" is_root || die "adb root did not take; is this a 'default' (userdebug) image?"
sdk=$(sh_ getprop ro.build.version.sdk)
# DevicePolicyManagerService latches setup-complete at first boot and keeps it
# in device_policies.xml, so the two settings alone are not enough. From
# Android 12 that file is binary ABX and has to be converted to edit it.
# The framework reads either form back, so once converted it stays text.
# While user_setup_complete is 0 the system ignores preferred activities and
# the highest-priority HOME wins, which on an AOSP image is Provision — the
# stub that sets both flags straight back to 1. It disables itself the first
# time it runs, but package manager writes that out on a delay that a stopped
# framework loses, so the disable is made here and waited for on disk.
# Binary ABX from Android 12, like device_policies.xml.
stub_disabled() {
  sh_ 'f=/data/system/users/0/package-restrictions.xml; \
    if [ "$(head -c 3 $f)" = "ABX" ]; then abx2xml $f -; else cat $f; fi' \
    | grep -F com.android.provision.DefaultActivity >/dev/null
}

undo_setup() {
  sh_ pm disable com.android.provision/.DefaultActivity >/dev/null 2>&1 || true
  wait_until 60 "the Provision stub's disable to reach disk" stub_disabled || true
  "$ADB" -s "$SERIAL" shell 'f=/data/system/device_policies.xml; t=/data/local/tmp/dp.xml; \
    if [ "$(head -c 3 $f)" = "ABX" ]; then abx2xml $f $t; else cp $f $t; fi \
    && sed -i "s/ setup-complete=\"true\"//" $t && cp $t $f && chown system:system $f' || true
  "$ADB" -s "$SERIAL" shell 'settings put secure user_setup_complete 0; \
    settings put global device_provisioned 0; stop; start' || true
  wait_until 180 "the device to come back up" boot_completed || die "it did not come back up"
  # boot_completed comes before every service is registered, and the gap is
  # longer on newer levels.
  wait_until 120 "the services to come back" sh_ dumpsys battery || die "the services did not come back"
  sleep 5
}
undo_setup
# Belt and braces, for an image whose stub is not the one disabled above: once
# more, with whatever provisioned the device again now on disk as disabled.
if [ "$(sh_ settings get global device_provisioned)" = "1" ]; then
  say "   the device set itself up again; undoing it once more"
  undo_setup
fi
say "   $(sh_ dumpsys device_policy | grep mUserSetupComplete | sed -n 1p)"

# The AVD reports no power source, so stayon does nothing until one is faked.
# A screen that sleeps mid-run takes the wizard's buttons with it.
sh_ dumpsys battery set ac 1 >/dev/null || true
sh_ svc power stayon true >/dev/null || true

say "-- installing the trigger"
"$ADB" -s "$SERIAL" install -r "$APK" >/dev/null
granted=$(sh_ dumpsys package "$PACKAGE" | grep DISPATCH_PROVISIONING_MESSAGE || true)
case "$granted" in
  *granted=true*) say "   $granted" ;;
  *) die "DISPATCH_PROVISIONING_MESSAGE is not granted ($granted). The APK has to be
signed with the platform key of this image: tools/platform-key.sh, and a 'default' image." ;;
esac

HOST_PORT=$("$ADB" -s "$SERIAL" forward tcp:0 "tcp:$PORT" | tr -d '\r')
[ -n "$HOST_PORT" ] || die "adb forward gave no port"
trap '"$ADB" -s "$SERIAL" forward --remove "tcp:$HOST_PORT" >/dev/null 2>&1 || true' EXIT
: > "$OUT/taps.log"
"$ADB" -s "$SERIAL" logcat -c || true

# --- the run -----------------------------------------------------------------

wait_until 240 "the emulator to join AndroidWifi" online || die "the emulator has no network"

say "-- scanning the setup code"
sh_ am start -n "$PACKAGE/.TriggerActivity" "${SCAN_EXTRA[@]}" >/dev/null
wait_until 60 "the deployment server" state >/dev/null || die "the server never came up"

if [ "$CASE" = wrong-checksum ]; then
  # Nothing to finalize: the wizard never installs a DPC, so it has no
  # provisioning to finish. It is left on its error screen and only read, not
  # tapped on: on API 30 that screen's one button is a factory reset.
  drive_wizard 600 download_refused \
    || die "the wizard neither refused the download nor timed out; $OUT/window.xml is its last screen"
  dump_window "$OUT/error-screen.xml"
  say "-- the wizard's error screen"
  screen_text "$OUT/error-screen.xml" | sed 's/^/   /' >&2
else
  drive_wizard 600 wizard_returned || die "the wizard never handed back; $OUT/window.xml is its last screen"
  kiosk_is_owner || say "   the wizard handed back without setting a device owner"

  say "-- finalizing, as the end of the wizard does"
  sh_ am start -n "$PACKAGE/.TriggerActivity" --es step finalize >/dev/null
  # The real wizard sets both of these when it ends.
  sh_ settings put secure user_setup_complete 1 >/dev/null
  sh_ settings put global device_provisioned 1 >/dev/null
fi

if [ "$CASE" = positive ]; then
  say "-- waiting for the setup report"
  wait_until 300 "a setup report" have_report || true
elif [ "$CASE" = wrong-config-hash ]; then
  say "-- waiting for the kiosk to refuse the config"
  wait_until 300 "the kiosk to refuse the config" config_refused || true
  # The launcher is started only once the failure is recorded, and it reads
  # that record before it can draw the screen that offers to try again.
  sleep 15
  dump_window "$OUT/launcher.xml"
fi

# --- what happened -----------------------------------------------------------

state > "$OUT/state.json" || echo '{}' > "$OUT/state.json"
TAGS="HandshakeTrigger:V HandshakeServer:V Handshake:V ManagedProvisioning:V"
# Only a negative run asks why the kiosk gave up, and only it has to read the
# kiosk's own tags to find out.
[ "$CASE" = positive ] || TAGS="$TAGS ProvisioningService:V ConfigFetch:V PolicyCompliance:V KioskDeviceAdmin:V"
# shellcheck disable=SC2086
"$ADB" -s "$SERIAL" logcat -d -v time $TAGS '*:S' \
  > "$OUT/logcat.txt" 2>/dev/null || true
say "-- saved $OUT/state.json and $OUT/logcat.txt"

FAILED=0
row() {
  if [ "$1" = ok ]; then printf '  PASS  %-26s %s\n' "$2" "$3"
  else printf '  FAIL  %-26s %s\n' "$2" "$3"; FAILED=$((FAILED + 1)); fi
}
verdict() { if [ "$1" = "$2" ]; then echo ok; else echo no; fi; }

requested=$(python3 -c 'import json,sys; print(" ".join(json.load(open(sys.argv[1])).get("requested",[])))' \
  "$OUT/state.json")

# $1 the path, $2 yes if the run should have asked for it, no if it must not have.
requested_row() {
  local label="$1 requested" want=ok
  if [ "$2" = no ]; then label="$1 not requested"; fi
  case " $requested " in *" $1 "*) ;; *) want=no ;; esac
  if [ "$2" = no ]; then
    if [ "$want" = ok ]; then want=no; else want=ok; fi
  fi
  row "$want" "$label" "${requested:-nothing was requested}"
}

# Which of the three the system calls depends on the level. Android 11 sends
# only the completion broadcast. From 12 the wizard asks GET_PROVISIONING_MODE
# before setting the owner and ADMIN_POLICY_COMPLIANCE at finalization, and
# the broadcast does not arrive at all — seen here on 36 and on a Galaxy A17
# in the field, so it is the platform, not the OEM.
steps_row() {
  local steps required missing= step
  steps=$( { grep -o 'Provisioning mode\|Policy compliance\|Provisioning complete' "$OUT/logcat.txt" || true; } \
    | sort -u | tr '\n' ',' | sed 's/,$//; s/,/, /g')
  if [ "$sdk" -ge 31 ]; then required="Provisioning mode|Policy compliance"; else required="Provisioning complete"; fi
  IFS='|'; for step in $required; do
    case "$steps" in *"$step"*) ;; *) missing="${missing:+$missing, }no $step" ;; esac
  done; unset IFS
  if [ -z "$missing" ]; then row ok "handshake steps" "$steps (SDK $sdk)"
  else row no "handshake steps" "${steps:-none logged}; $missing (SDK $sdk)"; fi
}

installed() { sh_ pm list packages | grep -x "package:$1" || true; }

home_package() {
  sh_ cmd package resolve-activity -a android.intent.action.MAIN \
    -c android.intent.category.HOME | awk -F= '/packageName=/ && !found {print $2; found = 1}'
}

if [ "$CASE" = wrong-checksum ]; then
  owner=$(device_owner || true)
  row "$(verdict "${owner:-none}" none)" "no device owner" "${owner:-none}"

  kiosk=$(installed "$KIOSK")
  row "$(verdict "$kiosk" "")" "kiosk not installed" "${kiosk:-not installed}"

  requested_row /dpc.apk yes
  requested_row /config.json no

  refusal=$( { grep -F "$REFUSAL" "$OUT/logcat.txt" || true; } | sed -n 1p)
  row "$(verdict "${refusal:+found}" found)" "download refused" \
    "${refusal:-ManagedProvisioning never logged its refusal: $REFUSAL}"

  # An error screen a trainer has to acknowledge, in ManagedProvisioning's own
  # window: the wizard stopped rather than carrying on with an unverified APK.
  error=$(screen_text "$OUT/error-screen.xml" | tr '\n' '|' | sed 's/|$//; s/|/ | /g')
  wizards_window=$( { grep -o 'package="com.android.managedprovisioning"' "$OUT/error-screen.xml" 2>/dev/null \
    || true; } | sed -n 1p)
  if [ -n "$wizards_window" ]; then row ok "wizard showed an error" "$error"
  else row no "wizard showed an error" "${error:-no dump of the screen}"; fi

  # Left where the wizard left it: the driver finalizes nothing here, because
  # the real wizard has no provisioning to finish.
  complete=$(sh_ settings get secure user_setup_complete)
  row "$(verdict "$complete" 0)" "user_setup_complete" "$complete"

elif [ "$CASE" = wrong-config-hash ]; then
  owner=$(device_owner || true)
  row "$(verdict "$owner" "$KIOSK")" "device owner" "${owner:-none}"

  requested_row /dpc.apk yes
  requested_row /config.json yes
  requested_row "/apks/$SAMPLE.apk" no

  reports=$(python3 -c 'import json,sys; print(len(json.load(open(sys.argv[1])).get("reports",[])))' \
    "$OUT/state.json")
  row "$(verdict "$reports" 0)" "no setup report" "$reports report(s)"

  sample=$(installed "$SAMPLE")
  row "$(verdict "$sample" "")" "payload not installed" "${sample:-not installed}"

  refusal=$( { grep -F "Could not obtain the deployment config" "$OUT/logcat.txt" || true; } | sed -n 1p)
  row "$(verdict "${refusal:+found}" found)" "config refused" \
    "${refusal:-the kiosk never gave up on the config}"

  home=$(home_package)
  row "$(verdict "$home" "$KIOSK")" "kiosk is HOME" "${home:-nothing resolves}"

  complete=$(sh_ settings get secure user_setup_complete)
  row "$(verdict "$complete" 1)" "user_setup_complete" "$complete"

  steps_row

  # Two buttons at least in the kiosk's own window — "set this phone up again"
  # and "scan a setup code". Counted rather than matched on a string: the
  # labels move with the deployment's locale, and Compose gives uiautomator no
  # resource id to ask for instead.
  count=$(clickable_nodes "$OUT/launcher.xml" "$KIOSK" | grep -c . || true)
  labels=$(screen_text "$OUT/launcher.xml" | tr '\n' '|' | sed 's/|$//; s/|/ | /g')
  if [ "${count:-0}" -ge 2 ]; then row ok "launcher offers a way out" "$count buttons: $labels"
  else row no "launcher offers a way out" "${labels:-nothing of the kiosk on screen}"; fi

else
  owner=$(device_owner || true)
  row "$(verdict "$owner" "$KIOSK")" "device owner" "${owner:-none}"

  for path in /dpc.apk /config.json; do
    case " $requested " in
      *" $path "*) row ok "$path requested" "$requested" ;;
      *) row no "$path requested" "${requested:-nothing was requested}" ;;
    esac
  done

  sample=$(sh_ pm list packages "$SAMPLE" | sed -n 1p)
  row "$(verdict "$sample" "package:$SAMPLE")" "payload installed" "${sample:-not installed}"

  report=$(python3 - "$OUT/state.json" <<'PY'
import json, sys

state = json.load(open(sys.argv[1]))
reports = state.get("reports", [])
if len(reports) != 1:
    print("no", "%d reports" % len(reports))
    raise SystemExit
try:
    report = json.loads(reports[0])
except ValueError:
    print("no", "unreadable report")
    raise SystemExit
wrong = []
if report.get("failures"):
    wrong.append("failures: " + "; ".join(report["failures"]))
if not report.get("isDeviceOwner"):
    wrong.append("not device owner")
extra = report.get("permissionFailures") or []
detail = "%s, %s, %d app(s)" % (
    report.get("deviceLabel"), report.get("deploymentName"), len(report.get("installed", [])))
if extra:
    detail += ", permission failures: " + "; ".join(extra)
print("no" if wrong else "ok", "; ".join(wrong) or detail)
PY
)
  row "${report%% *}" "setup report" "${report#* }"

  home=$(home_package)
  row "$(verdict "$home" "$KIOSK")" "kiosk is HOME" "${home:-nothing resolves}"

  complete=$(sh_ settings get secure user_setup_complete)
  row "$(verdict "$complete" 1)" "user_setup_complete" "$complete"

  steps_row
fi

echo
[ "$FAILED" = 0 ] || die "$FAILED row(s) failed. $OUT/logcat.txt has the wizard's own account."
say "every row passed."
