# Implementation progress

Working log for the CoMapeo kiosk + provisioning build. Spec:
`/Users/gregor/Downloads/comapeo-kiosk-handoff.md`. Scope for this run is
phases 1-3.

If you are resuming after a session limit: read the **Next step** line, then
carry on. Update this file at every phase boundary and whenever you stop.

## Next step

Phase 3 - the `comapeo-provision` app. Scaffolding exists at
`/Users/gregor/Dev/DdDev/comapeo-provision`; nothing is written yet.

## Established facts

Determined empirically this run; do not re-derive.

| Fact | Value |
|---|---|
| CoMapeo package name | `com.comapeo` (**not** `app.comapeo` as the spec's §5.5 example says) |
| CoMapeo release cert SHA-256 | `f88123ed1f334792c07f1df20ac91038ed2569c3f93f46acb482462d1daf7054` |
| CoMapeo v13.0 | versionCode 40, targetSdk 36, 186 MB |
| APK shape | Single universal APK, arm64-v8a + armeabi-v7a. Not a split install, so §5.6's "known risk to test early" does not apply. |
| Signature schemes | v2 + v3 (no v1 JAR signing) |
| Test AVDs | `kiosk_aosp_30` (Device Owner) and `kiosk_ui_30` (clean), both AOSP API 30 arm64 |
| `setApplicationExemptions` | Not in the public SDK; checked against `android-36/android.jar`. §5.4's "no DPM API for doze" stands. |

## Decisions taken beyond the spec

1. **Admin screen adds Wi-Fi networks, not just a radio toggle.** With
   `DISALLOW_CONFIG_WIFI` set, a deployed device otherwise has no way to ever
   join a network once the provisioning hotspot goes away. A Device Owner is
   exempt from the `addNetwork()` deprecation, so this is cheap.
2. **Network-affecting restrictions are applied last** in `provision()`, after
   install and report, rather than in one block up front as §5.3 orders it.
3. **Provisioning app computes the kiosk APK signature checksum at runtime**
   from the APK it serves, rather than hardcoding it, so debug and release
   variants both work.
4. Hotspot security type is checked against what the QR format can express
   (`NONE`/`WPA`/`WEP`/`EAP`); WPA3-SAE routes to the manual tethering fallback.
5. Deps: NanoHTTPD, ZXing `core` only, kotlinx-serialization, WorkManager,
   PBKDF2WithHmacSHA256 for the PIN.
6. **WorkManager's automatic initialiser is removed from the manifest.** It runs
   on the main thread at process start; on a slow device that pushed the
   launcher's first window past the 5s input-dispatch grace and the system
   ANR-killed the HOME activity at boot. Found on the emulator, but budget
   hardware is exactly where it would bite. WorkManager now starts on first use
   via `Configuration.Provider`.
7. The launcher loads its app list off the main thread and rasterises icons
   once, rather than doing disk and PackageManager work during composition.

## Test emulators - two are needed

On a device where the kiosk is Device Owner, the HOME activity and holding lock
task, Compose's test harness sees **no compose hierarchy at all**: every UI test
fails with "No compose hierarchies found", including a one-line smoke test.
`ComposeSmokeTest` exists as a canary for exactly this, because the symptom
reads like a bug in the screen under test.

| AVD | Role |
|---|---|
| `kiosk_aosp_30` | Device Owner. Policy, lock task, install verification, PIN logic. |
| `kiosk_ui_30` | Clean. Compose UI tests only. |

`tools/test.sh` runs both. Serials are assigned by boot order, so it resolves
them by AVD name; `tools/test.sh --serial <avd>` prints one.

## Answered by Gregor

- Cert: pull from the v13.0 release APK (done, above).
- Repos: `git init`, local commits only, no remote.
- Locales: English only, all strings externalised.
- Signing: dev keystore + `KEYS.md`, and exercise v3 rotation lineage.

## Phase status

- [x] **Phase 1 - policy core.** 31 instrumented tests green on `kiosk_aosp_30`.
      Shade unreachable in lock task, battery/signal visible, every intended
      restriction set and every excluded one unset, location on, recents
      unreachable, force-stop blocked, provision idempotent, certificate
      verification refusing a mismatched APK, PIN backoff.
      Reboot survival confirmed by hand: after `adb reboot` the launcher is the
      resumed activity with `mLockTaskModeState=LOCKED`, and `BootReceiver`
      logs the policy re-application.
- [x] **Phase 2 - launcher and admin screen.** 12 Compose UI tests green on
      `kiosk_ui_30`, covering the icon grid, launch-on-tap, the empty state,
      both admin-gesture timings (1s does nothing, 5s opens), the gesture having
      no semantics to discover, PIN success and failure, the PIN never being
      rendered in clear, keypad disabled during backoff, and cancel.
- [ ] Phase 3 - provisioning app
