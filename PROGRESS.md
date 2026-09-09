# Implementation progress

Working log for the CoMapeo kiosk + provisioning build. Spec:
`/Users/gregor/Downloads/comapeo-kiosk-handoff.md`. Scope for this run is
phases 1-3.

If you are resuming after a session limit: read the **Next step** line, then
carry on. Update this file at every phase boundary and whenever you stop.

## Next step

Phase 2 UI tests (launcher grid, hidden admin gesture, PIN), then phase 3 —
the `comapeo-provision` app.

## Established facts

Determined empirically this run; do not re-derive.

| Fact | Value |
|---|---|
| CoMapeo package name | `com.comapeo` (**not** `app.comapeo` as the spec's §5.5 example says) |
| CoMapeo release cert SHA-256 | `f88123ed1f334792c07f1df20ac91038ed2569c3f93f46acb482462d1daf7054` |
| CoMapeo v13.0 | versionCode 40, targetSdk 36, 186 MB |
| APK shape | Single universal APK, arm64-v8a + armeabi-v7a. Not a split install, so §5.6's "known risk to test early" does not apply. |
| Signature schemes | v2 + v3 (no v1 JAR signing) |
| Test AVD | `kiosk_aosp_30` - AOSP (no GMS) API 30 arm64, port 5560 |
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
- [ ] Phase 2 - launcher and admin screen (UI written, UI tests outstanding)
- [ ] Phase 3 - provisioning app
