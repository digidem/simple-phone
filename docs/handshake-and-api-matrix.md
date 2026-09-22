# Plan: more API levels, and the setup wizard handshake on an emulator

A handoff for the next two steps in automated testing, written so an agent can
pick either up cold. §1 is not built. §2 is built and passing locally on API
30, 34 and 36 as of 2026-09-22, with its CI job still to do; its verification
log at the end records what was learned on the way. It
follows on from [ci-and-releases.md](ci-and-releases.md), which plans the
workflows these steps extend, and assumes [CONTRIBUTING.md](../CONTRIBUTING.md)
has been read: the two-emulator split, the Device Owner tests, and the seam
through `Provisioner.provision()`.

Where this says **verified**, it was checked against this repository, the
Android documentation, or a public bug tracker in September 2026. Everything
else is a working assumption, and §2 lists the ones that have to be tested in
order before any real work is done on that step.

## 0. Why these two, and what they do not cover

**verified** Every bug that reached the field so far was in the setup wizard's
handshake on a real phone: the completion broadcast that never arrived on a
Galaxy A17 on Android 16, and the wizard on Android 14 insisting on internet
until the QR carried `PROVISIONING_ALLOW_OFFLINE`. The instrumented suite
cannot see either, because it starts after the wizard: `dpm set-device-owner`
makes the kiosk Device Owner without ManagedProvisioning ever running, so
`ProvisioningModeActivity`, `PolicyComplianceActivity` and
`onProfileProvisioningComplete` are never called by the system in any test.
`ProvisioningActivitiesTest` checks only that they resolve.

**verified** No commercial device farm (Firebase Test Lab, AWS Device Farm,
BrowserStack, Sauce Labs, Samsung Remote Test Lab) hands over a factory-reset
phone at the setup wizard, and none can put a QR code in front of the camera.
The wizard on a real phone stays a manual step. These two plans narrow what
that manual step has to catch:

1. **Run the Device Owner suite on more API levels** than the 30 it runs on
   today, so a policy or install API that changed in 33 to 36 fails in CI, not
   on the phone.
2. **Run the real ManagedProvisioning handshake on an emulator**, end to end:
   the wizard downloads the kiosk from `/dpc.apk`, checks its signature, installs
   it, makes it Device Owner, calls the two provisioning activities, and sends
   the completion broadcast, and the kiosk then fetches its config, installs the
   payload and posts a setup report. No camera, no hotspot.

What neither reaches: Google's provisioning role holder (the Android 14+
"couldn't connect to the internet" behaviour), and any OEM's own wizard. AOSP
emulator images have neither. Those stay on the release checklist, done by a
person with a real phone.

---

## 1. The Device Owner suite on API 33 to 36

### What exists

**verified** `tools/test.sh` looks for two AVDs by name, `kiosk_aosp_30` and
`kiosk_ui_30`, and every Device Owner test opens with
`assumeTrue(isDeviceOwner)`. Both apps are minSdk 30, targetSdk 36. The CI plan
(§3 of ci-and-releases.md) runs one `owner` job and one `ui` job on API 30 with
`reactivecircus/android-emulator-runner`, `target: default`.

### What to build

**A matrix over API level for the `owner` job**, and optionally the `ui` job.
Everything else about the jobs is as the CI plan describes.

1. **AVD names carry the API level.** `kiosk_aosp_<api>` and `kiosk_ui_<api>`.
   `tools/test.sh` reads `KIOSK_API` from the environment, default `30`, and
   resolves `kiosk_aosp_$KIOSK_API` and `kiosk_ui_$KIOSK_API`. With no variable
   set it behaves exactly as today. Update the AVD creation commands in the
   script's header comment and in CONTRIBUTING to use the variable.

2. **System images.** AOSP images only, for the reason CONTRIBUTING gives:
   `dpm set-device-owner` fails if any account exists. Locally on Apple Silicon:

   ```sh
   for api in 33 34 35 36; do
     sdkmanager "system-images;android-$api;default;arm64-v8a"
     avdmanager create avd -n kiosk_aosp_$api -k "system-images;android-$api;default;arm64-v8a" -d pixel_5
   done
   ```

   Check first that a `default` image exists for each level with
   `sdkmanager --list | grep 'system-images;android-3[3-6];default'`. If one is
   missing, `google_apis` (never `google_apis_playstore`) is the fallback, and
   the first run must confirm `dpm set-device-owner` still succeeds on it.

3. **The workflow.** In `instrumented.yml`, `owner` becomes a matrix job:

   ```yaml
   strategy:
     fail-fast: false
     matrix:
       api: [30, 34, 36]
   ```

   with `api-level: ${{ matrix.api }}`, `target: default`, `arch: x86_64`,
   `avd-name: kiosk_aosp_${{ matrix.api }}`, and `KIOSK_API` exported for the
   script. The job summary table from the CI plan gains an API column, and the
   uploaded XML is named by API level so two levels' results do not overwrite
   each other.

4. **Triage, do not paper over.** Run the full suite on each new level locally
   before wiring the matrix, and record every failure in a table in this
   document (test, API, message, verdict). A failure has one of three causes,
   and each has a different fix:

   - **Product bug**: a policy or install API behaves differently on that
     level. Fix the product. This is the outcome the matrix exists for.
   - **Test assumption**: UiAutomator selectors in `LockTaskTest` tied to the
     API 30 status bar, a notification permission that API 33 now needs, a
     foreground service type API 34 enforces. Fix the test, with a comment
     naming the API level.
   - **Emulator artefact**: the `system_server` NullPointerException
     CONTRIBUTING documents, or a lock task flake. Apply the retry policy from
     the CI plan; do not skip the test.

   Nothing gets an `assumeTrue(SDK_INT == 30)` without a line in this document
   saying why.

5. **Documentation.** CONTRIBUTING's "Two emulators, and why" section gains
   the `KIOSK_API` variable and a sentence saying which levels CI runs.

### Order of work

Each one commit.

1. `test(tools): resolve emulators by KIOSK_API`. Check: no-argument behaviour
   unchanged; `KIOSK_API=34 tools/test.sh` finds `kiosk_aosp_34`.
2. `test: run the Device Owner suite on API 34 and 36 locally`. Check: the
   triage table below is filled in, and every product bug found has its own
   fix commit before this one lands.
3. `ci: run the Device Owner suite on an API matrix`. Check: three green
   `owner` jobs, zero skipped in each, summary shows the API level.
4. Optionally the same for `ui`, once the `owner` matrix has run for a week.

### What still needs deciding

- **Which levels on every push.** Suggested `30`, `34`, `36`: the floor, the
  level where offline provisioning broke, and the level the newest field
  phone runs. `33` and `35` on a nightly or manual run. Each `owner` job is
  20 to 30 minutes, so five on every push is a lot of queue time.
- **Firebase Test Lab virtual devices as the runner** instead of GitHub's
  emulators. They offer Arm images for 26 to 36 and do not need KVM, but the
  test would have to make itself Device Owner from inside the run
  (`UiDevice.executeShellCommand("dpm set-device-owner …")` in a suite-level
  rule), which the CI plan rejected for Gradle Managed Devices for the same
  reason. Only worth it if GitHub's KVM runners prove unreliable.

### Triage table

| Test | API | Message | Verdict |
|---|---|---|---|
| *(fill in during step 2)* | | | |

---

## 2. The setup wizard handshake on an emulator

### The idea

Make the emulator do what the wizard does when a trainer scans the setup code,
without the camera. ManagedProvisioning, the system app that runs QR
provisioning, is started directly with the same extras the QR carries. It then
does everything the wizard would: download, checksum, install, set Device
Owner, `GET_PROVISIONING_MODE`, `ADMIN_POLICY_COMPLIANCE`,
`PROFILE_PROVISIONING_COMPLETE`. The kiosk's side of the handshake is the code
under test, unchanged.

Two facts shape the design:

- **verified** `adb shell am start -a android.app.action.PROVISION_MANAGED_DEVICE`,
  the old adb route, no longer works from Android 12
  ([What's new for enterprise in Android 12](https://developer.android.com/work/versions/android-12)).
- **verified** The route the wizard uses,
  `android.app.action.PROVISION_MANAGED_DEVICE_FROM_TRUSTED_SOURCE`, is behind
  `android.permission.DISPATCH_PROVISIONING_MESSAGE`, a signature-level
  permission. Starting it from the adb shell is refused with a
  `SecurityException`.

So the trigger has to be an app signed with the platform key. AOSP emulator
images are built with the public AOSP test keys, so an app signed with
`platform.pk8` from AOSP holds every signature permission the framework
defines. That is the one assumption everything rests on, and it is the first
thing to verify.

### Components

**`:handshake`, a test-only app**, following `:sample`'s precedent: not
shipped, exists for one test. Application id `org.awana.kiosk.handshake`,
minSdk 30, depends on `:shared` only. It plays the trainer's phone on the same
emulator as the field phone:

- **A server** on `127.0.0.1:8080` with the five endpoints of
  `setup/…/ProvisioningServer.kt`: `/dpc.apk`, `/config.json`, `/apks/{package}.apk`,
  `/manifest.json`, `POST /report`. Reuse the kiosk test's `DeploymentServer`
  shape. It runs in a foreground service, since ManagedProvisioning takes
  minutes and the process has to outlive the trigger activity. Add one extra
  endpoint, `GET /state`, returning JSON: every path requested, every report
  received, and the handshake steps seen. The host reads it through
  `adb forward tcp:8080 tcp:8080` and `curl`.
- **Two bundled assets**, copied in by Gradle the way `:setup` bundles the
  kiosk and `:kiosk:app`'s tests bundle the sample: the debug kiosk APK
  (`kiosk/app/build/outputs/apk/debug/app-debug.apk`) and the sample APK.
- **The config**, built on the device from `KioskConfig` in `:shared` with
  the sample's certificate fingerprint from `Certificates.ofApkFile`, encoded
  once, hashed once with `Digests.sha256Hex`, and served byte for byte. Mirror
  `TestConfigs.withServer` in the kiosk tests for the fields. `serverUrl` is
  `http://127.0.0.1:8080`.
- **The setup code JSON**, built with the same function the setup app uses.
  `QrPayload.build` lives in `:setup`, an application module, so move the JSON
  half of it (not `render`) into `:shared` next to `SetupCode`, which already
  parses it. The signature checksum comes from `signatureChecksumOf` in
  `ProvisioningSession.kt`, which should move with it; both are
  `Certificates` plus base64url. `QrPayloadTest` and the golden fixture keep
  covering it.
- **A trigger activity**, started from the host with
  `adb shell am start -n org.awana.kiosk.handshake/.TriggerActivity`. It
  converts the setup code JSON into an `Intent` exactly as the wizard would:
  string values as string extras, booleans as boolean extras, the
  `PROVISIONING_ADMIN_EXTRAS_BUNDLE` object as a `PersistableBundle` of
  strings. Then it logs
  `DevicePolicyManager.isProvisioningAllowed(ACTION_PROVISION_MANAGED_DEVICE)`
  and starts `ACTION_PROVISION_MANAGED_DEVICE_FROM_TRUSTED_SOURCE`.
  Leave the three `PROVISIONING_WIFI_*` extras out on the first pass: the
  emulator is already online and there is no hotspot to join.
- **Platform signing.** `tools/platform-key.sh` fetches `platform.pk8` and
  `platform.x509.pem` from
  `https://android.googlesource.com/platform/build/+/refs/heads/main/target/product/security/`
  (`?format=TEXT` returns base64), converts them with `openssl pkcs8` and
  `openssl pkcs12`, and writes a gitignored `build/platform-key/platform.p12`. The
  `:handshake` debug signing config points at it. They are public test keys;
  nothing about this is secret, but do not commit the binary.

**`tools/handshake.sh`**, the driver, run against a freshly wiped AVD. What
it has to do, **verified** on `kiosk_wizard_30` (API 30, `default` image):

```sh
adb root
# The device policy service latches "setup complete" at first boot and keeps
# it in device_policies.xml; settings alone do not unlatch it. Needs root.
adb shell "sed -i 's/ setup-complete=\"true\"//' /data/system/device_policies.xml \
  && settings put secure user_setup_complete 0 && settings put global device_provisioned 0 \
  && stop && start"                                    # then wait for sys.boot_completed again
adb shell pm disable com.android.provision/.DefaultActivity   # or the stub re-provisions on restart
adb uninstall org.awana.kiosk 2>/dev/null || true      # the wizard must download it
adb install -r handshake/build/outputs/apk/debug/handshake-debug.apk
adb shell dumpsys package org.awana.kiosk.handshake | grep DISPATCH_PROVISIONING_MESSAGE   # granted=true
adb forward tcp:8080 tcp:8080
adb shell am start -n org.awana.kiosk.handshake/.TriggerActivity                       # the scan
# tap the wizard's bottom-right button whenever one is enabled, until it returns
adb shell am start -n org.awana.kiosk.handshake/.TriggerActivity --es step finalize  # end of wizard
adb shell settings put secure user_setup_complete 1; adb shell settings put global device_provisioned 1
# poll curl localhost:8080/state until a report arrives or 5 minutes pass
```

Three things the first draft of this plan had wrong:

- **Root is needed**, for the `device_policies.xml` edit above. AOSP `default`
  images are userdebug, so `adb root` works; Play images would not.
- **Finalisation is a second call.** After install and Device Owner,
  ManagedProvisioning saves its parameters and sets the user provisioning state
  to "setup incomplete", then returns. The completion broadcast and, on
  Android 12+, `ADMIN_POLICY_COMPLIANCE` only happen when the setup wizard
  starts `android.app.action.PROVISION_FINALIZATION` at its own end. Google's
  wizard does that; the AOSP stub never does, so the trigger app does it.
- **Several screens, and they differ by level.** API 30 shows a consent
  screen ("Let's set up your work device", "Accept & continue") and, after
  install, an education screen in the locale the code carried ("Este
  dispositivo não é privado", "Próximo"). API 36 shows four: the Wi-Fi picker
  it borrows from Settings (NEXT, even though the emulator is already online),
  "This device belongs to your organization" (Next, disabled until the
  download and install finish), the consent screen, then the education
  screen. None of the buttons has a resource id; all sit bottom-right. So the
  driver does not count screens: it taps the enabled bottom-right button in
  the wizard's or Settings' window every few seconds until ManagedProvisioning
  returns to the trigger, which is what a trainer does. The Wi-Fi picker's
  BACK sits at the same height on the left, hence "bottom-most, then
  right-most". `PROVISIONING_SKIP_USER_CONSENT` was not tried; the taps are
  part of what the field flow looks like.
- **From Android 12 the setup happens inside `ADMIN_POLICY_COMPLIANCE`, and
  the completion broadcast never comes.** On API 36 the wizard called
  `GET_PROVISIONING_MODE` before setting the owner, then at finalisation
  started `ADMIN_POLICY_COMPLIANCE`, in which the kiosk fetched the config,
  installed the payload and posted the report. `onProfileProvisioningComplete`
  was never called; ManagedProvisioning logged "Attempt to commitFinalizedState
  when params have already been deleted". That is the Galaxy A17 behaviour
  CONTRIBUTING records, reproduced on AOSP: it is the platform, not Samsung.
  On API 30 it is the other way round: only the broadcast, neither activity.
  The driver's step assertion is therefore by level.

Applying `PROVISIONING_LOCALE` mid-run recreates the trigger activity, so it
only starts provisioning when `savedInstanceState` is null. Every reader in
the driver consumes its whole input: under `pipefail`, a `grep -q` that closes
the pipe early fails the adb side with SIGPIPE, and on newer levels' larger
dumps that read as "not found".

**Assertions**, all from the host after the run:

| Check | Command | Expect |
|---|---|---|
| Kiosk is Device Owner | `adb shell dumpsys device_policy` | `Device Owner:` names `org.awana.kiosk` |
| Wizard downloaded, not sideloaded | `/state` | `/dpc.apk` was requested |
| The handshake steps for the level | `adb logcat -d -s Handshake` | API 30: "Provisioning complete". API 31+: "Provisioning mode" and "Policy compliance". |
| Config fetched and verified | `/state` | `/config.json` requested; no `ConfigFetch` failure in logcat |
| Payload installed | `adb shell pm list packages` | `org.awana.kiosk.sample` |
| Report delivered | `/state` | one report, `failures` empty, `isDeviceOwner` true |
| Kiosk is HOME | `adb shell cmd package resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME` | `org.awana.kiosk` |
| Wizard finished | `adb shell settings get secure user_setup_complete` | `1` |

**Negative runs**, `tools/handshake.sh --case wrong-checksum` and
`--case wrong-config-hash`, each a flag the driver passes to the trigger,
which corrupts one value in the setup code. **verified** on API 30:

- **wrong-checksum**: ManagedProvisioning downloads `/dpc.apk`, logs "Provided
  hash does not match any signature hash", and shows "Can't set up device /
  The admin app could not be used because of a checksum error" with a single
  RESET button. The kiosk is never installed and there is no device owner.
  This plan expected the wizard to return to the scanner; it does not, it
  offers a factory reset. What a trainer is told to do here is an open
  question for the field notes.
- **wrong-config-hash**: the wizard completes and the kiosk is Device Owner,
  the kiosk fetches `/config.json`, refuses it, asks for nothing else and
  sends no report, and its launcher shows "Setting up this phone did not
  finish" with "Set this phone up again", "Scan a setup code" and "Remove the
  lock". This case found a product bug, fixed the same day: on API 30 the
  kiosk was not HOME afterwards. `applyHome()` ran inside `provision()` and in
  `PolicyComplianceActivity`'s failure branch, and on Android 11 neither is
  reached, since only the completion broadcast arrives; the launcher was on
  screen only because the service started it explicitly, and pressing HOME
  would have shown the chooser. `ProvisioningService` now makes the launcher
  HOME whenever a run ends in anything but success, on every path, and the
  compliance activity keeps only the case where the run never finished.
  `ProvisioningServiceTest` asserts it on the Device Owner emulator.

The refused config is also hard to tell apart in logcat: `ConfigFetch` puts
the mismatch text in the telemetry extras and the bootstrap failure, and the
only logcat line, "Could not obtain the deployment config", is the same for an
unreachable server. The driver matches on that line and says so.

### Verify in this order, and stop if one fails

Each is a short experiment on a wiped AVD of the same image as `kiosk_aosp_30`
(`kiosk_wizard_30` was created for it, so the suite's own emulator keeps its
device owner). Record the outcome in this document either way. If 1 to 3 do not pass within a day, stop: the rest
of the plan is not worth building on a different trigger mechanism without
first deciding what that mechanism is.

1. **The platform key is accepted.** Build `:handshake` signed with the AOSP
   platform key, install it, and confirm `dumpsys package` shows
   `android.permission.DISPATCH_PROVISIONING_MESSAGE: granted=true`. If not,
   check which certificate signed `/system/framework/framework-res.apk`
   (`adb pull` then `apksigner verify --print-certs`) against the AOSP
   `platform.x509.pem` digest. A `google_apis` image may be signed differently;
   only `default` images are expected to work.
2. **Provisioning is allowed.** After the two `settings put` lines,
   `isProvisioningAllowed` logs `true`. If it logs `false`, run
   `adb shell dumpsys device_policy` and look for an existing owner or a
   second user; `-wipe-data` and try again.
3. **ManagedProvisioning accepts the intent** and reaches the download step.
   Watch `adb logcat -s ManagedProvisioning`. A consent screen may appear
   ("Accept & continue" in the field). Try `PROVISIONING_SKIP_USER_CONSENT`
   first; if a screen still appears, the driver taps it with
   `uiautomator dump` and `input tap`, and the resource id goes in the script
   with a comment.
4. **The download from localhost works.** If ManagedProvisioning refuses
   `127.0.0.1`, serve from the host instead: a Python script with the same
   endpoints on port 8080, reached from the emulator at `10.0.2.2:8080`. The
   trigger app then only builds the intent. The config and its hash would then
   be produced on the host, which is why this is the fallback and not the
   design.
5. **The kiosk's side completes**: every row of the assertion table.
6. **Repeat on API 34 and 36.** From Android 12 the wizard calls
   `ADMIN_POLICY_COMPLIANCE` and may not send the completion broadcast at all;
   from 14 there is a role holder step that AOSP images skip. Expect the logcat
   assertions to differ by level and record which steps each level produced.

### CI

A third job in `instrumented.yml`, `handshake`, separate from `owner` because
it needs an emulator that has never had a device owner. Same emulator runner,
`target: default`, matrix over the same API levels as §1, `script:` runs
`tools/handshake.sh`. About four minutes a level after boot. It publishes `/state`, the
`Handshake` and `ManagedProvisioning` logcat lines, and a screenshot on
failure, into the job summary.

### Order of work

1. `test(handshake): move the setup code builder into shared`. Check: golden
   fixture unchanged, `QrPayloadTest` green. **Done 2026-09-22.**
2. `tools: fetch the AOSP platform key`. Check: `build/platform.p12` exists,
   gitignored. **Done 2026-09-22.**
3. `test(handshake): add the trigger app`. Check: verification steps 1 to 3
   above, results recorded here. **Done 2026-09-22.**
4. `test(handshake): serve the deployment and assert the report`. Check: steps
   4 and 5. **Done 2026-09-22.**
5. `test(handshake): the two negative runs`. **Done 2026-09-22.** The
   wrong-config-hash case found the API 30 HOME bug described above;
   `fix(kiosk): make the launcher HOME after a failed setup on every path` is
   its own commit, with the service test that covers it.
6. `ci: run the handshake on every push`. Check: green on 30; results for 34
   and 36 recorded.
7. `docs: describe the handshake test` in CONTRIBUTING, under Testing, and
   reword `ProvisioningActivitiesTest`'s comment, which said nothing exercises
   the wizard. **Done 2026-09-22.**

### What still needs deciding

- **Where the trigger app lives.** Suggested top-level `:handshake` beside
  `:sample`, since it depends on `:shared` and nothing under `kiosk/`.
- **Whether to also try `google_apis` images.** They carry GMS and, from 14,
  possibly the role holder that produced the offline bug. Worth one experiment
  after step 6, and only if verification step 1 passes on them too.
- **The Wi-Fi extras go in, quoted.** Decided on 2026-09-22. Without them
  the wizard shows the Wi-Fi picker it borrows from Settings, and on API 34
  that picker's NEXT ends provisioning. With them the wizard's
  `AddWifiNetworkTask` compares the SSID against the quoted one Android
  reports for the network the emulator is already on, and cannot add a
  duplicate when they differ, so the trigger passes `"AndroidWifi"` with the
  quotes as part of the value, security `NONE`. The field path is untouched: a
  real phone is not on the hotspot yet, so the unquoted SSID from the setup
  app is what gets joined.

### Verification log

| Step | API | Date | Outcome |
|---|---|---|---|
| 1 platform key accepted | 30 | 2026-09-22 | Pass. `framework-res.apk` on the `default` image is signed with the AOSP platform certificate (SHA-256 `c8a2e9bc…2ab8`); `DISPATCH_PROVISIONING_MESSAGE: granted=true`. |
| 2 provisioning allowed | 30 | 2026-09-22 | Fail, then pass. `settings put` alone left `isProvisioningAllowed` false and ManagedProvisioning logging "Device already provisioned"; removing `setup-complete` from `device_policies.xml` as root and restarting the framework made it true. |
| 3 intent accepted | 30 | 2026-09-22 | Pass. Consent screen, then `DownloadPackageTask`, `VerifyPackageTask` ("Checking SHA-256-hashes of all signatures"), `InstallPackageTask`, `SetDevicePolicyTask`, `DisallowAddUserTask`; locale `pt_BR` applied. Then an education screen and "pre-finalization completed". |
| 4 localhost download | 30 | 2026-09-22 | Pass. `GET /dpc.apk from 127.0.0.1` served by the trigger app's own server; no host-side server needed. |
| 5 kiosk side | 30 | 2026-09-22 | Pass. After `PROVISION_FINALIZATION`: `Provisioning complete: admin extras [CONFIG_SHA256, SERVER_URL], bootstrap present, device owner true`; config fetched and verified, sample installed, report delivered with no failures, kiosk HOME. Neither activity was started on API 30. Every row of `tools/handshake.sh` passed on three consecutive wiped runs. |
| 6 full run | 36 | 2026-09-22 | Pass on every row once the driver tapped the two extra screens. The wizard called `GET_PROVISIONING_MODE` before setting the owner and `ADMIN_POLICY_COMPLIANCE` at finalisation; the setup ran inside the latter and the report arrived before the completion broadcast would have, which never came. About four minutes. |
| 1 platform key accepted | 36 | 2026-09-22 | Pass at the certificate level: the API 36 `default` image's framework is signed with the same AOSP platform certificate. `com.android.managedprovisioning` is present; the `DEVICE_POLICY_MANAGEMENT` role has no holder, so no role holder step. |
| 2 provisioning allowed | 36 | 2026-09-22 | Pass, with one change: from Android 12 `device_policies.xml` is binary (ABX), so the driver converts with the on-device `abx2xml`, edits the text, and writes the text form back; the framework reads either. `dumpsys device_policy` then shows `mUserSetupComplete=false`. |
| 6 full run | 34 | 2026-09-22 | Pass on every row, with the Wi-Fi extras. Without them the wizard's Wi-Fi picker appeared and its NEXT logged "User canceled wifi picking" and ended the run; with an unquoted SSID `AddWifiNetworkTask` gave up after six retries ("Unable to add network"). Same screens and steps as 36. |
| 6 full run, again | 30 and 36 | 2026-09-22 | Pass on every row with the finished driver, Wi-Fi extras included. On 36 the Wi-Fi picker no longer appears. |
| negative runs | 30 | 2026-09-22 | wrong-checksum: every row passed, one flake in three where the wizard showed an "OK" dialog after consent and aborted; rerun before believing it. wrong-config-hash: ten of eleven rows passed and "kiosk is HOME" failed, the product bug recorded above; every row passes since the fix. |
| reset on a fresh AVD | 30 | 2026-09-22 | The reset was racy: AOSP's `Provision` stub disables itself on first run but package manager writes that out on a delay that `stop; start` loses, so on the restart the stub ran again and set both flags back. The driver now disables the component itself and waits for the disable to reach `package-restrictions.xml` (ABX from Android 12). |
