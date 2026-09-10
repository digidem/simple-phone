<!-- The original implementation handoff, kept for the record. It predates the
     generic rename and the fetched-config design; CONTRIBUTING.md describes what
     was actually built. -->

# CoMapeo Kiosk & Provisioning — Implementation Handoff

**Audience:** coding agent implementing two new Android apps.
**Status:** v1 scope agreed. Items marked **[DECIDE]** are open and must be raised
rather than guessed at.

---

## 1. Context

Awana Digital deploys CoMapeo (offline-first mapping app) to Indigenous partner
organisations. New deployments involve buying budget Android phones for a team.
Users are often non-technical and sometimes non-literate.

Problems to solve:

- Users wander into other apps and get lost.
- The quick-settings shade is pulled down accidentally, turning off mobile data
  or turning on airplane mode.
- Apps get accidentally uninstalled.
- Everything must work with **no internet, no Google account, no Play Services**
  at provisioning time.

Solution: an Android **Device Owner** app that acts as a locked-down launcher,
plus an admin-phone app that provisions devices over a local hotspot.

Device Owner is plain AOSP. It requires no Google account and no network. This
is deliberately *not* Android Enterprise / AMAPI / managed Google Play.

---

## 2. Deliverables

Two repositories, both **GPL-3.0**, both Kotlin + Jetpack Compose.

| Repo | App | Role |
|---|---|---|
| `comapeo-kiosk` | `app.comapeo.kiosk` | Device Owner DPC + locked launcher. Installed on user devices. |
| `comapeo-provision` | `app.comapeo.provision` | Admin/trainer phone app. Carries APKs, runs hotspot + HTTP server, generates provisioning QR. |

CoMapeo-side changes (managed-device detection, gating Settings deep-links,
share-target picker) are a **separate workstream and out of scope here**.

**Guiding constraint: keep both codebases as small as humanly possible.** The
maintaining team works in React Native / JS, not Kotlin. Prefer boring, obvious
code over abstraction. Minimise dependencies. Every added library is a
maintenance liability for people who don't write Kotlin daily.

---

## 3. Decisions already made

| # | Decision |
|---|---|
| 1 | Small launcher, not single-app lock. Basic Android-style, large icons, single screen, no dock, no pinned row. 2–5 apps total. |
| 2 | No separate camera app — photos are taken inside CoMapeo. Telegram only as a share target (Signal dropped, see §5.10). Self-updating. |
| 2b | The provisioning app's APK library is populated by the trainer, not bundled by Awana. |
| 2c | Notification shade **off by default**. Exposed as a per-deployment setting in the provisioning app for deployments that need it. See §5.4. |
| 3 | Config editable both in the kiosk app's admin screen and in the provisioning app. |
| 4 | **minSdk = 30** (Android 11). Target latest stable. |
| 5 | Fleet is new budget phones, including budget Xiaomi. |
| 6 | Huawei / non-GMS is best-effort only; drop if it costs real time. |
| 7 | Per-deployment admin PIN (not time-based codes — the provisioning device could be lost). Partner trainers hold it. |
| 8 | On-device un-provisioning allowed, behind the admin PIN. |
| 9 | Do **not** set `DISALLOW_FACTORY_RESET`. |
| 10 | Updates: DPC polls GitHub Releases for `comapeo-mobile`. |
| 11 | No offline update path in v1. |
| 12 | No post-provisioning policy push from admin app in v1. |
| 15 | Separate signing key from CoMapeo. See §9. |
| 17 | Provisioning app is operated by partner trainers, not Awana staff. |

---

## 4. Open decisions — raise, don't guess

- **[DECIDE-B] `LOCK_TASK_FEATURE_BLOCK_ACTIVITY_START_IN_TASK`.** Opt-in
  blocking of non-allowlisted activities started into the locked task. Leaving
  it **off** is needed for the system share sheet and document picker to work.
  Default: **off**, but verify empirically (see §10).
- **[DECIDE-C] `DISALLOW_CONFIG_WIFI`.** Locks users out of Wi-Fi config
  entirely. Good for stability, bad if users must join a village network.
  Default: **set** — it is one of the restrictions that greys out a dangerous
  quick-settings tile (§5.4). Wi-Fi toggle exposed in admin screen instead.
- **[DECIDE-D] `DISALLOW_DEBUGGING_FEATURES`.** Hardens the device but removes
  ADB as a field recovery route. Default: **not set** in v1.

---

## 5. `comapeo-kiosk` — the DPC

### 5.1 Module layout

Single APK, two Gradle modules. No IPC, no second process.

```
comapeo-kiosk/
  policy/      # DevicePolicyManager wrapper, provisioning, installer, updater
  launcher/    # HOME activity, icon grid, admin screen
  app/         # assembly, DI wiring (manual — no Hilt unless it earns its place)
```

### 5.2 Manifest essentials

- `DeviceAdminReceiver` subclass with `device_admin.xml` metadata.
- Launcher activity:
  - `android:launchMode="singleInstance"`
  - `android:lockTaskMode="if_whitelisted"` — makes lock task re-enter
    automatically on launch, which is how reboot survival works
  - intent filter: `MAIN` + `HOME` + `DEFAULT`
- `RECEIVE_BOOT_COMPLETED`.
- No `INTERNET` in the launcher module's logical surface; the updater needs it.

### 5.3 Provisioning callback

`DeviceAdminReceiver.onProfileProvisioningComplete(context, intent)`:

1. Read `EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE`. **All values are strings** —
   nested/typed values through the QR JSON path are unreliable. Parse a JSON
   string if structure is needed.
2. Persist the config (§5.5).
3. Apply the full policy set (§5.4).
4. Download and silently install each package listed in the config from
   `serverUrl` (§5.6). The device is still on the provisioning hotspot at this
   point — do not drop the network.
5. Pre-grant permissions for installed packages.
6. `POST` a completion report to `{serverUrl}/report` (§6.5).
7. Set self as persistent HOME, launch launcher, enter lock task.

Every step must be idempotent and independently re-runnable from the admin
screen ("re-apply policy"), because partial provisioning failures in the field
must be recoverable without a factory reset.

### 5.4 Policy set

Apply on provisioning, on `BOOT_COMPLETED`, and on demand from the admin screen.

**Lock task**

```
setLockTaskPackages(admin, allowlist)   // must include the kiosk's own package
setLockTaskFeatures(admin,
    LOCK_TASK_FEATURE_HOME
  | LOCK_TASK_FEATURE_SYSTEM_INFO       // battery/signal indicators visible
  | LOCK_TASK_FEATURE_GLOBAL_ACTIONS    // long-press power → power off
  | if (config.showNotificationShade) LOCK_TASK_FEATURE_NOTIFICATIONS else 0
)
// NOT set: OVERVIEW, KEYGUARD, BLOCK_ACTIVITY_START_IN_TASK (see DECIDE-B)
```

**On the notification shade.** Default is **off**: `SYSTEM_INFO` without
`NOTIFICATIONS` is the combination that leaves battery and signal indicators
visible while making the shade unreachable. That is the primary configuration
and the one to optimise for.

`showNotificationShade` is a per-deployment flag (§5.5), set in the provisioning
app, for deployments where users genuinely need notifications. When it is on,
the four `CONFIG`/`AIRPLANE` user restrictions below are what stop users
breaking things — a restricted tile greys out and shows a "blocked by admin"
dialog rather than acting.

Three gaps apply in that mode, and must be documented in the provisioning app's
UI next to the toggle so a trainer understands what they are enabling:

1. **Battery saver and Data Saver have no corresponding user restriction.** Both
   tiles stay live and both can degrade GPS and background sync. There is no DPM
   API to disable them.
2. **Do Not Disturb** stays live and can suppress the notifications the flag
   exists to deliver.
3. **The settings gear in the shade may open the full Settings app.** System
   apps can run in lock task mode without being allowlisted, so this is a real
   possibility, and it would be a far larger escape hatch than any tile.

§10 test 1 gates whether this flag ships at all. If the gear opens Settings,
remove the flag rather than shipping a footgun.

**Home**

```
addPersistentPreferredActivity(admin, homeIntentFilter, launcherComponent)
```

**Uninstall protection**

```
setUninstallBlocked(admin, pkg, true)        // for every allowlisted package
setUserControlDisabledPackages(admin, [comapeo, kiosk])  // no force-stop/clear-data
```

The kiosk app itself needs no protection — a Device Owner cannot be uninstalled.

**User restrictions**

```
DISALLOW_AIRPLANE_MODE
DISALLOW_CONFIG_MOBILE_NETWORKS
DISALLOW_CONFIG_LOCATION
DISALLOW_CONFIG_WIFI
DISALLOW_SAFE_BOOT
DISALLOW_ADD_USER
DISALLOW_UNINSTALL_APPS
DISALLOW_MODIFY_ACCOUNTS
```

These four `CONFIG`/`AIRPLANE` restrictions are set regardless of the shade
flag. With the shade off they are defence in depth; with it on they are the
mechanism that renders the dangerous tiles inert.

Explicitly **not** set: `DISALLOW_FACTORY_RESET` (decision 9),
`DISALLOW_DEBUGGING_FEATURES` (DECIDE-D), and
`DISALLOW_INSTALL_UNKNOWN_SOURCES` — the last because it would block
Signal's and Telegram's self-updaters (§5.10). The user has no browser and no
file manager, so the practical exposure is low.

Note `DISALLOW_APPS_CONTROL` is broader than needed and implies
`DISALLOW_UNINSTALL_APPS`; prefer the targeted restrictions above.

**Location and time** (both matter for GPS quality and track timestamps)

```
setLocationEnabled(admin, true)          // API 30+
setAutoTimeEnabled(admin, true)          // API 30+
setAutoTimeZoneEnabled(admin, true)      // API 30+
```

**Permissions**

```
setPermissionPolicy(admin, PERMISSION_POLICY_AUTO_GRANT)
// plus explicit grants for CoMapeo's known permissions:
setPermissionGrantState(admin, comapeo, perm, PERMISSION_GRANT_STATE_GRANTED)
```

Grant `ACCESS_FINE_LOCATION` **before** `ACCESS_BACKGROUND_LOCATION` — background
location behaviour has changed across releases and generally requires foreground
first. This is load-bearing for track recording; test it explicitly.

`setPermissionGrantState` covers runtime permissions only. Special access
(`MANAGE_EXTERNAL_STORAGE`, `SYSTEM_ALERT_WINDOW`) are app-ops and are not
covered — flag if CoMapeo turns out to need them.

**Screen and keyguard**

```
setKeyguardDisabled(admin, true)         // no lockscreen; only works with no password set
setSystemSetting(admin, SCREEN_OFF_TIMEOUT, <configurable>)
```

**Battery — read this**

There is no reliable DPM API at minSdk 30 for exempting an app from doze and
app-standby, and **no** DPM API at any level touches OEM battery managers
(Xiaomi/MIUI autostart, Huawei protected apps). Device Owner does not solve
dontkillmyapp. `setUserControlDisabledPackages` (above) prevents force-stop and
is the strongest thing available here.

Implementation task: on first run, detect known-hostile OEMs (`Build.MANUFACTURER`)
and record it in the completion report so the trainer knows a manual per-vendor
step is required. Do **not** attempt to automate OEM settings screens.

### 5.5 Config document

Single JSON document in the DPC's private storage. Seeded at provisioning from
the extras bundle, edited afterwards from the admin screen.

```json
{
  "schemaVersion": 1,
  "deploymentId": "...",
  "deploymentName": "...",
  "adminPinHash": "<PBKDF2 or Argon2id, with salt and params>",
  "serverUrl": "http://192.168.43.1:8080",
  "packages": ["app.comapeo", "org.telegram.messenger"],
  "visibleInLauncher": ["app.comapeo", "org.telegram.messenger"],
  "showNotificationShade": false,
  "updateChannel": { "type": "github", "repo": "digidem/comapeo-mobile" },
  "locale": "pt-BR",
  "screenOffTimeoutMs": 120000
}
```

Never store the PIN in plaintext. Rate-limit and back off on failed attempts.

`packages` (installed + uninstall-blocked + lock-task allowlisted) is
deliberately separate from `visibleInLauncher` (shown as an icon), so an app can
exist as a share target without appearing on the home screen.

### 5.6 Silent install

`PackageInstaller.Session` — create, write bytes, commit. As Device Owner this
requires no user interaction. A system notification saying the app was installed
by the admin will appear and cannot be suppressed; that's acceptable.

**Do not** re-implement signature-match or version-downgrade checks. Android
already enforces both (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`,
`INSTALL_FAILED_VERSION_DOWNGRADE`). What you **must** implement:

1. **Certificate verification on first install.** There is no incumbent package
   to compare against, so read `GET_SIGNING_CERTIFICATES` from the staged APK
   and check it before committing. Two tiers:
   - **CoMapeo's certificate is hardcoded** in the kiosk app. It is the package
     that matters, and it must not be substitutable.
   - **All other APKs are trainer-supplied** (§6.1), so their fingerprints are
     recorded by the provisioning app when the trainer adds them, published in
     `/manifest.json`, and delivered to the DPC as part of the trusted
     provisioning payload. The DPC verifies against those.

   Without this, a trainer can be socially engineered into deploying a fake
   CoMapeo across a whole team.
2. **Human-readable failure surfacing.** Map installer status codes to messages
   a trainer can act on ("this APK is signed with a different key"), shown in the
   admin screen and included in the completion report. Silent failures in the
   field are unrecoverable.

Known risk to test early: if CoMapeo is ever delivered from Play as a split
install from an AAB, installing a single monolithic APK over it may fail. Verify
on a real device and report back before building around it.

### 5.7 Launcher UI

Deliberately minimal. Build from scratch in Compose — do not fork a
general-purpose launcher. Every feature a normal launcher has (app drawer,
widgets, folders, gestures, icon packs, wallpaper picker) is either dead weight
or an escape route.

- Single screen. No dock, no pinned row, no second page, no scrolling if
  avoidable (2–5 apps).
- Two-column grid, large icons (~96dp), label beneath in the deployment locale.
- Solid background colour. No wallpaper picker.
- Long-press on an icon: **no action**.
- Back button from the launcher: **no action**.
- Icons and labels come from `PackageManager` for packages listed in
  `visibleInLauncher` that are actually installed.

**Admin entry:** long-press a fixed, unlabelled screen region (e.g. top-left
corner) for 5 seconds → PIN entry. Must not be discoverable by accident. No
visual affordance.

### 5.8 Admin screen (v1 functions)

Behind the PIN. Keep it a plain list.

- Device info: model, manufacturer, Android version, kiosk version, CoMapeo
  version, deployment ID, Device Owner status.
- Show/hide installed apps in the launcher.
- Change admin PIN.
- Toggle Wi-Fi (`WifiManager.setWifiEnabled` still works for Device Owner apps).
- Re-apply policy set.
- Check for updates now.
- Install APK from URL (network-dependent; the offline path is v2).
- Temporarily exit lock task, with an automatic re-entry timer (e.g. 10 min) so
  a device can never be left unlocked by accident.
- **Un-provision**: `clearDeviceOwnerApp()`, behind a second confirmation.
  Returns the device to a normal, unmanaged state. Document that this cannot be
  undone without a factory reset.

Every destructive action must state its consequence in plain language in the
deployment locale.

### 5.9 Updater

Periodic `WorkManager` job. Polls the GitHub Releases API for
`digidem/comapeo-mobile`.

**Release convention** (confirmed): tag `v{X.Y}`, single asset named
`comapeo-v{X.Y}.apk`, e.g.

```
https://github.com/digidem/comapeo-mobile/releases/download/v13.0/comapeo-v13.0.apk
```

- Use the tag to decide whether a release is *candidate* for download. Do **not**
  trust the version name for ordering — after download, read `versionCode` via
  `PackageManager.getPackageArchiveInfo` and only install if it exceeds the
  installed `versionCode`.
- Confirmed: always a single **universal** APK (no per-ABI splits), and
  prereleases are never published to this repo. Still skip anything flagged
  `draft` or `prerelease` defensively.
- Constrain to unmetered networks by default; make it configurable, since some
  deployments are cellular-only and data costs matter.
- Verify pinned signing certificate before commit (§5.6).
- Exponential backoff. Unauthenticated GitHub API is rate-limited (60/hr/IP) —
  fine at this cadence, but handle 403 gracefully.
- Never prompt the user. Failures go to the admin screen and the next report.

### 5.10 Third-party app updates — known limitation

**Signal is dropped from v1.** Its non-Play builds expire (believed ~90 days),
after which the app refuses to send messages. Combined with the self-update
problem below, that risks a messaging app dying mid-deployment, which is worse
than shipping none. Revisit only if a reliable update path exists.

**Telegram** ships as a trainer-supplied APK and is expected to self-update. One
issue makes that uncertain:

> `REQUEST_INSTALL_PACKAGES` is an app-op, not a runtime permission. A Device
> Owner cannot pre-grant it with `setPermissionGrantState`. A self-updater
> lacking it will send the user into a Settings screen — exactly the confusing
> dead end this project exists to prevent.

`DISALLOW_INSTALL_UNKNOWN_SOURCES` is deliberately not set for this reason, but
that alone may not be sufficient. Verify on hardware (§10 test 3).

The stakes are lower than with Signal: a stale Telegram keeps working. If
self-update proves blocked, the fallback is to extend the updater (§5.9) to
manage the package from a configured URL. **Design the updater so the GitHub
channel is one strategy behind a small interface**, rather than hardcoding
GitHub throughout — this keeps the fallback, and the eventual offline channel,
cheap to add.

---

## 6. `comapeo-provision` — the admin app

Runs on a trainer's own (unmanaged) phone.

### 6.1 APK library

**The library is trainer-populated.** The app ships with the kiosk APK and can
fetch CoMapeo from GitHub Releases when online. Everything else — Signal,
Telegram, anything a future deployment needs — is added by the trainer from
device storage. Awana does not redistribute third-party APKs, which removes a
trademark and policy question from the deployment process entirely.

For each APK in the library, on import: parse and display package name, version
name, `versionCode`, and **signing certificate SHA-256 fingerprint**. The
fingerprint is recorded, published in `/manifest.json`, and used by the DPC for
verification (§5.6). Show it in the UI so a trainer can confirm what they are
about to deploy across a whole team.

Library entries persist across sessions. Usable entirely offline once populated.

### 6.2 Hotspot

`WifiManager.startLocalOnlyHotspot()`, held in a **foreground service** for the
duration of the session — the hotspot dies when the reservation is released.

- The framework picks the SSID and passphrase; read them back from the
  `LocalOnlyHotspotReservation` (`getSoftApConfiguration()`, API 30+). You cannot
  set them without system permissions, and you don't need to — the QR is
  generated fresh each session from whatever the framework gave you.
- Requires `NEARBY_WIFI_DEVICES`.
- **Do not hardcode the gateway address.** Enumerate `NetworkInterface` and read
  the actual local address. It is commonly `192.168.43.1` but varies by OEM.
- Local-only means no internet upstream. That is correct and intended.
- **Fallback path required:** local-only hotspot behaviour on budget phones is
  inconsistent. Provide a manual mode where the trainer enables normal tethering
  themselves and enters the SSID and passphrase into the app.

### 6.3 HTTP server

Small embedded server (Ktor or NanoHTTPD — pick the smaller dependency).

```
GET  /dpc.apk              → kiosk APK
GET  /apks/{package}.apk   → payload APKs
GET  /manifest.json        → package list with versions and cert fingerprints
POST /report               → enrolment completion reports
```

Plain HTTP is correct here. Provisioning integrity is guaranteed by the
signature checksum in the QR, not by TLS; the DPC's own fetches verify pinned
certificates itself.

### 6.4 QR generation

ZXing. Payload:

```json
{
  "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME":
      "app.comapeo.kiosk/app.comapeo.kiosk.KioskDeviceAdminReceiver",
  "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION":
      "http://<local-ip>:8080/dpc.apk",
  "android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM": "<base64url SHA-256>",
  "android.app.extra.PROVISIONING_WIFI_SSID": "<from reservation>",
  "android.app.extra.PROVISIONING_WIFI_PASSWORD": "<from reservation>",
  "android.app.extra.PROVISIONING_WIFI_SECURITY_TYPE": "WPA",
  "android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED": true,
  "android.app.extra.PROVISIONING_SKIP_ENCRYPTION": true,
  "android.app.extra.PROVISIONING_LOCALE": "pt_BR",
  "android.app.extra.PROVISIONING_TIME_ZONE": "America/Manaus",
  "android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE": { "...": "strings only" }
}
```

Two notes that matter:

- Use `PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM`, **not**
  `PROVISIONING_DEVICE_ADMIN_PACKAGE_CHECKSUM`. The signature checksum is derived
  from the signing certificate and is therefore constant across builds; the
  package checksum is a file hash that changes with every release. From Android
  10 only SHA-256 is accepted.
- `LEAVE_ALL_SYSTEM_APPS_ENABLED: true` is required. Without it, provisioning
  disables non-required system apps, which can take out components you need.

### 6.5 Enrolment dashboard

Live view fed by `POST /report`. Each report carries device model, manufacturer,
Android version, kiosk version, packages installed with versions, policies
applied, any failures, and whether a hostile-OEM battery manager was detected.

Shows "4 of 6 provisioned" and tells the trainer when it is safe to stop the
hotspot. This is what turns a fiddly technical sequence into something a
non-engineer can run and report on. Treat it as a core feature, not telemetry.

### 6.6 Deployment profiles

Create, edit, duplicate, export, import. Fields: deployment name, admin PIN,
package list, launcher-visible list, locale, timezone, update channel, screen
timeout, **notification shade on/off** (default off — surface the §5.4 caveats
inline next to this toggle, not in separate documentation). Export/import as a
JSON file so profiles can be shared between trainers out of band.

### 6.7 Trainer-facing UX

The operators are partner trainers, not engineers. In-app step-by-step
provisioning walkthrough with the six-tap instruction illustrated. Fully
localisable, same target languages as CoMapeo. Errors phrased as actions, not
status codes.

---

## 7. Provisioning flow (end to end)

1. Trainer opens provisioning app, selects a deployment profile.
2. App starts local-only hotspot and HTTP server, reads back SSID / passphrase /
   local IP, generates and displays the QR.
3. Factory-reset client phone → welcome screen → tap six times → built-in QR
   reader opens (Android 9+; the app should state this requirement).
4. Device joins the hotspot, downloads and installs the kiosk APK, sets it as
   Device Owner.
5. `onProfileProvisioningComplete` fires: config parsed, policies applied, CoMapeo
   + Signal + Telegram installed from the same server, permissions pre-granted.
6. DPC reports completion; dashboard updates.
7. Device lands on the kiosk launcher in lock task mode.
8. Repeat for remaining devices, then stop the hotspot.

Bring a power bank. Soft AP plus file serving drains the trainer's phone fast —
this is the practical constraint on batch size, not throughput.

---

## 8. Phasing

Each phase independently testable. Phases 1–2 and most of 3 are automatable on
an emulator — see §8.1, which the agent should read before designing Phase 1,
because the required seam has to exist from the start.

**Phase 1 — policy core.** DPC installable and ADB-provisionable
(`adb shell dpm set-device-owner`). Full policy set applied. No launcher yet;
a debug screen is enough.
*Done when:* the shade cannot be pulled down, battery/signal are visible,
airplane mode is unavailable, location is on and cannot be turned off, and all
of it survives a reboot.

**Phase 2 — launcher and admin screen.** Icon grid, hidden admin entry, PIN,
all §5.8 functions including un-provision.
*Done when:* a device can be locked down and returned to normal entirely from
on-device UI.

**Phase 3 — provisioning app.** Hotspot, server, QR, dashboard, profiles.
*Done when:* a factory-reset phone goes from box to locked-down-with-all-apps
via one QR scan, with no internet anywhere in the loop.

**Phase 4 — updater.** GitHub Releases polling, cert pinning, silent install.
*Done when:* a new CoMapeo release installs unattended and the user sees nothing
but a system notification.

**Phase 5 — device matrix hardening.** Per-device fixes, OEM battery-manager
documentation, trainer walkthrough localisation.

### 8.1 Emulator and CI testability

The agent is expected to work unattended through phases 1–3. That is achievable,
but only if one architectural decision is made up front:

> **Decouple the provisioning trigger from the provisioning payload handling.**
> `onProfileProvisioningComplete` must be a thin wrapper around a
> `suspend fun provision(config: KioskConfig): ProvisionResult`. Instrumented
> tests call `provision()` directly with a synthesised config. This makes the
> entire post-scan path — download, verify, install, apply policy, report —
> testable without a camera or a setup wizard.

**Emulator setup.** Use an **AOSP** system image at API 30, not a Google Play
image. `dpm set-device-owner` fails if any account exists on the device, and
Play images acquire accounts readily. Provision with:

```
adb shell dpm set-device-owner app.comapeo.kiosk/.KioskDeviceAdminReceiver
```

**Phase 1 — fully automatable.** Every DPM call and its resulting state is
assertable: `getLockTaskPackages()`, `UserManager.hasUserRestriction()`,
`isLocationEnabled()`, `getPermissionGrantState()`. For shade behaviour, use
UiAutomator `UiDevice.openNotification()` as an automated proxy and assert on
what is (or is not) reachable. Reboot survival: `adb reboot`, then assert the
policy set and HOME activity are intact.

**Phase 2 — fully automatable.** Compose UI tests for the launcher grid and
admin screens; UiAutomator for HOME persistence, the hidden admin gesture, and
back-button suppression.

**Phase 3 — roughly 80% automatable.** Testable: HTTP server endpoints, QR
payload JSON generation (assert against a golden fixture), manifest generation,
fingerprint extraction, report ingestion, profile CRUD and export/import. For an
end-to-end run, use two emulators with `adb forward` / `adb reverse` to bridge
the HTTP server, and drive the client side through `provision()`.

**Not automatable, requires hardware:**

- `startLocalOnlyHotspot` — no soft AP on emulator. Stub it behind an interface
  with a fake implementation for tests.
- The six-tap setup-wizard QR scan — needs a real camera.
- Anything in §10. All six verification items are OEM- and SystemUI-dependent.

**Deliverable alongside phases 1–3:** a short scripted manual checklist covering
exactly the hardware-only steps, written for someone who is not the author.

---

## 9. Signing and key custody

- **One key for both apps, separate from CoMapeo's key.**
- **No Play App Signing.** Neither app goes to Play. Distribution is direct APK.
- **The kiosk signing key can never be lost.** If it is, no enrolled device can
  ever receive a DPC update — a Device Owner cannot be uninstalled, and a
  differently-signed APK cannot replace it. The only remedy would be factory
  resetting the entire fleet. The key is also baked into every provisioning QR
  via the signature checksum.
- Store offline, encrypted, with copies held by at least two people. Document
  custody in the repo.
- Enable **signing lineage (APK Signature Scheme v3 rotation)** from the very
  first release, so rotation remains possible later.
- Maintain a strictly separate debug key. A device provisioned with a
  debug-signed kiosk can never receive production updates — enforce that test
  and production fleets never mix, and make the build variant visible in the
  admin screen and completion report.

---

## 10. Empirical verification — do this first

Almost everything below is version- and OEM-dependent, and none of it should be
taken on trust from documentation. Before Phase 1 design is finalised, run
TestDPC (Google's open-source reference DPC) on representative hardware and
confirm:

1. **Shade blocked (the default path).** `SYSTEM_INFO` on, `NOTIFICATIONS` off —
   is quick settings genuinely unreachable, with battery and signal still
   visible? This is the primary configuration and the whole point of the
   project. Do it first.
2. **Shade allowed (the optional flag).** With `NOTIFICATIONS` on and the four
   `CONFIG`/`AIRPLANE` restrictions set: are the mobile data, Wi-Fi, location and
   airplane tiles inert? **Does the settings gear open the full Settings app?**
   If it does, drop the flag rather than shipping it.
3. **Telegram self-update** (§5.10) — does it complete, or dead-end in Settings?
4. **Background location grants** via `setPermissionGrantState`, foreground first.
5. **Settings intents.** What happens when an app fires a Settings deep-link in
   lock task — does it open, or fail silently? Both are bad UX; the answer
   determines how hard the CoMapeo-side gating workstream has to be.
6. **Share sheet.** Does the chooser open? Are non-allowlisted targets shown but
   non-functional? (Expected: yes — which is why CoMapeo needs its own filtered
   share picker in the separate workstream.)
7. **Document picker** (`ACTION_CREATE_DOCUMENT` / DocumentsUI) reachability.
8. **OEM battery managers** on the Xiaomi units — what manual steps remain.

Report findings before building around any of them.

### Device matrix

To be finalised — hardware acquisition is in progress.

- Must pass: budget Xiaomi (MIUI/HyperOS), at least one non-Xiaomi budget device.
- Best-effort: Huawei / non-GMS. Drop if it costs real time.
- Note: Device Owner provisioning cannot be tested on cloud device farms.
  Physical units are required.

MIUI/HyperOS specifically requires **"USB debugging (Security settings)"** enabled
for ADB provisioning to work — document this in the developer setup guide.

---

## 11. Non-goals for v1

- Offline update delivery (no network path).
- Post-provisioning policy push from the admin app to enrolled devices.
- Any MDM server.
- Any Google account, Play Services, or managed Google Play dependency.
- Remote wipe, remote lock, telemetry beyond enrolment reports.
- Support for devices below API 30.
- Changes inside CoMapeo itself.
