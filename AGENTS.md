# AGENTS.md

Guidance for coding agents working in `comapeo-kiosk`.

An Android **Device Owner** app: a locked-down launcher for CoMapeo deployments
on budget phones handed to non-technical, sometimes non-literate users. The full
specification is `/Users/gregor/Downloads/comapeo-kiosk-handoff.md`; running
notes and phase status are in `PROGRESS.md`.

## Commands

```sh
./gradlew :app:assembleDebug          # build
./gradlew :policy:assembleDebug       # policy module alone
tools/test.sh                         # the whole instrumented suite, both emulators
tools/dev-keys.sh                     # dev keystore + v3 lineage, both gitignored
```

Run one class or one test. Emulator serials are assigned by boot order, so
resolve them by AVD name rather than assuming a port:

```sh
SERIAL=$(tools/test.sh --serial kiosk_aosp_30)
ANDROID_SERIAL=$SERIAL ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=app.comapeo.kiosk.PolicyTest
ANDROID_SERIAL=$SERIAL ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=app.comapeo.kiosk.PolicyTest#provisionIsIdempotent
```

Gradle prints only a pass/fail count. For failure messages, read the XML:

```sh
python3 -c "
import glob, xml.etree.ElementTree as ET
for f in glob.glob('app/build/outputs/androidTest-results/connected/debug/*.xml'):
    r = ET.parse(f).getroot()
    for tc in r.iter('testcase'):
        for fa in list(tc): print(tc.get('name'), (fa.text or '')[:600])
"
```

## Two emulators, and why

| AVD | Role |
|---|---|
| `kiosk_aosp_30` | Device Owner. Policy, lock task, install verification, PIN logic. |
| `kiosk_ui_30` | Clean. Compose UI tests only. |

Both must be **AOSP** images (`system-images;android-30;default;arm64-v8a`), not
Google Play ones: `dpm set-device-owner` fails if any account exists, and Play
images acquire accounts readily.

On a device where this app is Device Owner, the HOME activity and holding lock
task, **Compose's test harness sees no compose hierarchy at all** — every UI test
fails with "No compose hierarchies found", including a one-line smoke test.
`ComposeSmokeTest` is a canary for exactly that, because the symptom otherwise
reads like a bug in the screen under test. Hence the split.

Making the device owner, once, after `kiosk_aosp_30` boots:

```sh
adb -s "$(tools/test.sh --serial kiosk_aosp_30)" shell dpm set-device-owner \
  app.comapeo.kiosk/.KioskDeviceAdminReceiver
```

## Architecture

```
policy/     DevicePolicyManager wrapper, config, installer, provisioning. No UI deps.
launcher/   HOME activity, icon grid, PIN, admin screen.
app/        Assembly: KioskApp, the DeviceAdminReceiver, the boot receiver.
```

The seam that makes everything testable: **`onProfileProvisioningComplete` is a
thin wrapper over `Provisioner.provision(config)` and nothing else.** Tests call
`provision()` directly with a synthesised config, so the whole post-scan path —
download, verify, install, apply policy, report — runs without a camera or a
setup wizard. Do not grow logic into the receiver.

`DevicePolicy` resolves its own admin `ComponentName` by querying
`PackageManager` for the receiver in this package, so `:policy` needs no
compile-time dependency on `:app`, which declares it.

Wiring is manual in `KioskApp`. There is not enough of it for a DI framework,
and the maintaining team works in React Native, not Kotlin. Prefer boring code;
every dependency is a maintenance liability.

## Things that look like bugs but are deliberate

**`DISALLOW_FACTORY_RESET`, `DISALLOW_DEBUGGING_FEATURES` and
`DISALLOW_INSTALL_UNKNOWN_SOURCES` are not set.** They are listed in
`DevicePolicy.NOT_SET_BY_DESIGN` and a test asserts they stay unset. A device
that cannot be recovered in the field is worse than any escape it prevents; ADB
is the recovery route; and blocking unknown sources would break third-party
self-updaters.

**Network restrictions are applied last**, after install and reporting, not in
one block up front — locking down Wi-Fi configuration must not disturb the
hotspot the payload arrives over.

**The admin screen can add a Wi-Fi network, not just toggle the radio.** With
`DISALLOW_CONFIG_WIFI` set and the provisioning hotspot gone, a deployed device
would otherwise never join a network again. A Device Owner is exempt from
`addNetwork`'s deprecation.

**WorkManager's automatic initialiser is removed from the manifest.** It runs on
the main thread at process start; that delayed the launcher's first window past
the five seconds `InputDispatcher` waits before declaring "does not have a
focused window", and the system ANR-killed the HOME activity at boot. Do not
re-add it — `KioskApp` implements `Configuration.Provider` so WorkManager starts
on first use. Anything else added to process startup must be measured the same
way.

**`Certificates.matches` checks the whole certificate history**, not just the
current signer, so a package that has rotated its key under APK Signature Scheme
v3 still verifies against a fingerprint recorded beforehand.

## Facts established empirically

- CoMapeo's package is **`com.comapeo`** — the spec's §5.5 example says
  `app.comapeo` and is wrong.
- Its release certificate SHA-256 is pinned in `Pins.COMAPEO_CERT_SHA256`. This
  value must not become substitutable: if a trainer can be talked into deploying
  a fake CoMapeo, a whole team is compromised at once.
- CoMapeo ships as a **single universal APK** (~186 MB, arm64-v8a +
  armeabi-v7a, v2/v3 signed), so the spec's split-install concern does not
  apply.
- CoMapeo declares `SYSTEM_ALERT_WINDOW`, which is an app-op and **cannot** be
  pre-granted by a Device Owner.
- `DevicePolicyManager.setApplicationExemptions` is not in the public SDK, so
  there is no doze exemption at any API level. `setUserControlDisabledPackages`
  is the ceiling.

## Signing

One key for this app and `comapeo-provision`, separate from CoMapeo's, no Play
App Signing. **If the kiosk key is lost, no enrolled device can ever receive a
DPC update and the only remedy is factory resetting every fleet.** Read
`KEYS.md` before touching signing config. Without `keystore.properties` the
release build silently falls back to debug signing — check `buildVariant` in the
admin screen or in an enrolment report rather than assuming.

## Conventions

Strings are all externalised and written for a non-technical reader in the
deployment locale; error text says what to do, not what failed. Destructive
actions state their consequence in plain language before they happen.
