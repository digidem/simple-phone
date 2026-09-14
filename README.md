# Field Kiosk

Android Device Owner management for field deployments: setup Android phones locked down to specific apps and configurations.

**Field Kiosk** turns an Android phone into a locked-down device: a home screen with a handful of apps on it, no quick-settings shade, nothing that can be uninstalled by accident, and no Google account or internet connection needed to set it up. It is an Android **Device Owner**, the strongest form of management the platform offers.

**Field Kiosk Setup** is for setting up field phones with Field Kiosk. A device owner app must be configured after a factory reset, and needs a QR code and server for install. The Field Kiosk Setup app holds the APKs for a deployment, brings up a hotspot and a small web server, shows a provisioning QR code, and fills in a dashboard as each phone finishes.

Neither app is tied to any particular field app. The trainer adds the APKs a deployment needs — a mapping app, a messaging app, whatever the team uses — and the kiosk installs and protects exactly those.

## Who it is for

Organisations that buy a batch of phones for a team and want every phone to come out of the box the same, set up in the field, by someone who is not an engineer. The lockdown mode is for users who are non-technical or confused by the many options on a regular Android device. A locked down device never ends up in Settings, in a Google sign-in, or in an app the deployment did not put there.

## Why Device Owner rather than Android Enterprise

Android Enterprise, AMAPI and managed Google Play all need a Google account, Play Services and a working internet connection at the moment a device is enrolled. Setting up Google Accounts in a remote location with limited connectivity can be challenging and often impractical.

Device Owner provisioning is plain AOSP (Android Open Source Project). A factory-reset phone reads a QR code, joins a local hotspot, downloads the management app from the trainer's phone and becomes managed — with no account, no Play Services and no internet at any point. There is also no cloud service to pay for, keep running, or lose access to years later.

## Requirements

- Field phones: Android 11 (API 30) or newer, factory reset, with or without Google services.
- The trainer's phone: Android 11 or newer, able to share a hotspot. If it cannot start one on its own, the app falls back to the trainer turning on ordinary tethering in Settings and typing the name and password in.
- To build the apps: JDK 17 and the Android SDK.

## Building

```sh
./gradlew :provision:assembleDebug    # the trainer's app
./gradlew :kiosk:app:assembleDebug    # the device app
```

The APKs land at:

```
provision/build/outputs/apk/debug/provision-debug.apk
kiosk/app/build/outputs/apk/debug/app-debug.apk
```

Building the trainer's app builds the kiosk app too and bundles it inside, so the trainer's phone is the only one anything has to be installed on by hand. Everything the field phones need arrives over the hotspot.

Swap `Debug` for `Release` for a release build. Read [KEYS.md](KEYS.md) first: without a `keystore.properties` at the repository root the release build falls back to debug signing rather than failing, and a fleet provisioned with a debug-signed kiosk can never be updated from a production build. Check `buildVariant` on the device's admin screen, or in an enrolment report, rather than assuming.

## How a deployment runs

The trainer adds each APK to the app's library from the phone's storage, and the app shows the package name, version and signing fingerprint for each one.

They then make a deployment — a name, an admin PIN, which apps to install, which of those appear on the home screen, any Wi-Fi networks the phones should join, the language, the time zone — and tap start. The phone brings up a hotspot and a web server and shows a QR code.

Setup each field phone:

1. Factory reset it, or take it out of the box.
2. On the first "Hello" screen, tap the middle of the screen six times.
3. The phone opens a camera. Point it at the QR code.
4. It joins the trainer's hotspot, installs the kiosk app and makes it Device Owner, then fetches the deployment's settings from the same phone and checks them against a fingerprint the QR carried.
5. It installs the deployment's apps, grants them the permissions they need, joins the Wi-Fi networks the deployment lists, applies the lock, and reports back.
6. The trainer app shows progress and any errors encountered during the setup.

Leave the two phones near each other while a phone is enrolling, and bring a power bank. Running the hotspot and sending a large APK to each phone drains the trainer's phone quickly; that, rather than speed, is what limits how many phones can be done in one go.

Afterwards the phones need nothing from the trainer's phone. A long press on the home screen and the admin PIN open a settings screen where a trainer can change which apps are shown, add a Wi-Fi network, unlock the device for a few minutes, install an app from a URL, or remove the lock entirely.

## What is deliberately not locked down

Factory reset stays available. A phone that cannot be recovered in the field is worse than any escape it prevents, and a reset from the phone's own recovery menu is the last resort when the admin PIN has been lost.

Installing from unknown sources stays available, because blocking it would break the self-updaters that third-party apps rely on.

The share sheet and the document picker keep working. Locking the device out of them would stop people getting their own data off it.

The battery, signal and clock indicators stay visible even though the shade cannot be pulled down. People need to know whether the phone has signal.

Debugging restrictions are not applied either, but do not mistake that for a recovery route: a phone provisioned by QR has USB debugging switched off and no way to reach Developer options, so ADB cannot help you in the field. Recovery is the admin PIN, or a factory reset from the phone's recovery menu.

## Before deploying to a phone model you have not used

Everything that depends on the manufacturer's own software — the shade, the battery manager, the share sheet, the file picker — has to be checked on real hardware. [docs/hardware-checklist.md](docs/hardware-checklist.md) is the list, with the emulator findings recorded against each item so it is clear which questions are already answered.

## Licence

GPL-3.0. See [LICENSE](LICENSE).
