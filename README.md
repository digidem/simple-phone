# comapeo-kiosk

An Android **Device Owner** app that turns a budget phone into a locked-down
CoMapeo device: a small launcher showing two to five apps, no quick-settings
shade, no way to uninstall anything, and no Google account or internet needed to
set it up.

Companion to [`comapeo-provision`](../comapeo-provision), the trainer-phone app
that provisions devices over a local hotspot.

## Why Device Owner rather than Android Enterprise

Device Owner is plain AOSP. It needs no Google account, no Play Services and no
network at provisioning time — which is the whole constraint. This is
deliberately *not* Android Enterprise, AMAPI or managed Google Play.

## Modules

```
policy/     DevicePolicyManager wrapper, provisioning, installer, updater
launcher/   HOME activity, icon grid, admin screen
app/        assembly and wiring
```

Wiring is manual. There is not enough of it to justify a DI framework, and the
team maintaining this works in React Native rather than Kotlin. Prefer boring,
obvious code; every added dependency is a maintenance liability.

## Requirements

- minSdk 30 (Android 11), targetSdk 36
- JDK 17
- An **AOSP** emulator image, not a Google Play one — `dpm set-device-owner`
  fails if any account exists on the device, and Play images acquire accounts
  readily.

## Building

```sh
./gradlew :app:assembleDebug
```

For a release build, see [KEYS.md](KEYS.md) — without `keystore.properties` the
release build silently falls back to debug signing.

## Testing on an emulator

```sh
# One-off: an AOSP image with no accounts.
sdkmanager "system-images;android-30;default;arm64-v8a"
avdmanager create avd -n kiosk_aosp_30 \
  -k "system-images;android-30;default;arm64-v8a" -d pixel_5

emulator -avd kiosk_aosp_30 -no-snapshot -no-boot-anim -no-audio &

./gradlew :app:installDebug
adb shell dpm set-device-owner app.comapeo.kiosk/.KioskDeviceAdminReceiver
./gradlew :app:connectedDebugAndroidTest
```

To undo, either use **Remove the lock from this device** in the admin screen or:

```sh
adb shell dpm remove-active-admin app.comapeo.kiosk/.KioskDeviceAdminReceiver
```

## Testing on hardware

Provisioning is done by QR from `comapeo-provision`, or over ADB as above.
MIUI/HyperOS additionally requires **"USB debugging (Security settings)"** to be
enabled before `dpm set-device-owner` will work.

Everything that is OEM- and SystemUI-dependent is listed in
[docs/hardware-checklist.md](docs/hardware-checklist.md), with the emulator
findings recorded next to each item so it is clear which questions are already
answered and which still need a real device.

## What is deliberately not locked down

`DISALLOW_FACTORY_RESET` is **not** set: a device that cannot be recovered in the
field is worse than any escape it prevents. `DISALLOW_DEBUGGING_FEATURES` is not
set either, keeping ADB as a recovery route. `DISALLOW_INSTALL_UNKNOWN_SOURCES`
is not set because it would block third-party apps' self-updaters.

## Licence

GPL-3.0. See [LICENSE](LICENSE).
