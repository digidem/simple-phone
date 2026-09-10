# Hardware verification checklist

Everything here is OEM- and SystemUI-dependent and cannot be settled on an
emulator. Work through it on each phone model before that model is used for a
real deployment.

You do not need to be the author of this code to run it. You need a phone you
can factory reset, a second phone with the CoMapeo Setup app on it, and about an
hour per model.

**What the emulator already told us** is recorded against each item. An AOSP
emulator is a *floor*, not an answer: Xiaomi's HyperOS and similar skins replace
large parts of SystemUI, and that is exactly where these behaviours live.

Record results in the table at the bottom and commit it.

---

## Before you start

Enable USB debugging on the phone under test. **On Xiaomi (MIUI/HyperOS) you
must also enable "USB debugging (Security settings)"**, which is a separate
switch further down the same Developer options screen. Without it
`dpm set-device-owner` fails and QR provisioning may too.

Provision the phone either by QR from the setup app, or over ADB on a
freshly-reset phone with no accounts added:

```sh
adb install app-debug.apk
adb shell dpm set-device-owner app.comapeo.kiosk/.KioskDeviceAdminReceiver
```

---

## 1. The shade is unreachable — the default, and the whole point

Do this one first. If it fails, nothing else matters.

1. Provision with the notification shade **off** (the default).
2. Swipe down from the top of the screen. Repeat from the very top edge, from
   the middle, and with two fingers.

**Pass:** nothing pulls down. The clock, signal and battery icons stay visible at
the top.

**Fail:** any part of the shade appears.

> Emulator (AOSP 11): **passes.** Quick settings and the notification list are
> both unreachable in lock task, and the status bar clock stays visible.
> Automated as `LockTaskTest.quickSettingsCannotBeReachedWithTheShadeOff` and
> `batteryAndSignalStayVisible`.

## 2. The shade is allowed — the optional flag

Only needed if a deployment wants notifications. **This test decides whether the
flag ships at all.**

1. Provision with the notification shade **on**.
2. Pull the shade down and try each of: mobile data, Wi-Fi, location, airplane
   mode.
3. **Then tap the settings gear in the shade.**

**Pass:** all four tiles are greyed out or show a "blocked by your admin"
message, and the gear does *not* open the full Settings app.

**Fail — remove the flag rather than shipping it:** the gear opens Settings.
That is a far larger escape hatch than any tile, because system apps can run in
lock task without being allowlisted.

> Emulator: **not conclusive.** The four restrictions are confirmed set
> (`LockTaskTest.shadeFlagTurnsNotificationsOn`), but whether a real SystemUI
> greys the tiles, and what its gear does, is untested. This is the single most
> important item on the list.

## 3. Telegram's self-updater

1. Install an old Telegram build as part of a deployment.
2. Open it and let it offer to update itself.

**Pass:** the update completes.

**Fail:** it sends the user into a Settings screen — exactly the confusing dead
end this project exists to prevent. `REQUEST_INSTALL_PACKAGES` is an app-op, not
a runtime permission, so a Device Owner cannot pre-grant it.

If it fails, the fallback is to have the kiosk's updater manage Telegram from a
configured URL. The updater is written so a second source is a small addition
rather than a rewrite.

> Emulator: **not testable.** Needs the real app and a real update.

## 4. Background location

1. Provision, then open CoMapeo and record a track.
2. Lock the screen and leave the phone for ten minutes.
3. Come back and check the track is continuous.

**Pass:** no gap in the track.

> Emulator: **partly.** `setPermissionGrantState` is confirmed to grant
> `ACCESS_FINE_LOCATION` before `ACCESS_BACKGROUND_LOCATION`, and the grants
> take. Whether the OEM then actually lets the app run in the background is item
> 8, not this one.

## 5. Settings deep links

1. In CoMapeo, trigger anything that opens a Settings screen.

**Record which happens:** the Settings screen opens, or nothing happens at all.
Both are bad for the user; the answer decides how much work the CoMapeo-side
gating workstream needs. Write down exactly which screens you tried.

> Emulator: untested on real SystemUI.

## 6. The share sheet

1. In CoMapeo, share something.

**Expected:** the chooser opens and shows targets that are not in the deployment,
which do nothing when tapped. This is why CoMapeo needs its own filtered share
picker — a separate workstream.

**Record:** whether the chooser opens at all, and what tapping a non-deployed
target does.

## 7. The document picker

1. Trigger anything that opens the file picker (`ACTION_CREATE_DOCUMENT`).

**Record:** whether DocumentsUI opens, and whether the user can navigate out of
it into anything else.

## 8. The OEM battery manager

This is the one that quietly ruins deployments weeks later.

1. Provision the phone.
2. Find the vendor's battery or autostart screen — on Xiaomi it is Settings →
   Apps → CoMapeo → Battery saver, plus a separate Autostart toggle.
3. Set CoMapeo to unrestricted, and enable autostart.
4. **Write down the exact taps**, for the trainer walkthrough.
5. Leave the phone recording a track overnight and check it in the morning.

There is no DPM API for any of this at any Android version — not for doze, not
for app-standby, and nothing at all that reaches vendor battery managers. The
kiosk detects known-hostile manufacturers and flags them in the enrolment
report, so the setup app tells the trainer a manual step is needed. It does not
try to automate the vendor screens, and should not.

> Emulator: the detection is confirmed to reach the report. The manual steps
> themselves are per-vendor and must be written down here.

## 9. Reboot survival

1. Provision, then `adb reboot` or hold the power button.
2. Wait for the phone to come back.

**Pass:** it lands on the kiosk home screen, locked, without anyone touching it.
The shade is still unreachable.

> Emulator: **passes.** After reboot the launcher is the resumed activity with
> lock task LOCKED, and the boot receiver re-applies the policy set. No code
> starts an activity at boot — the launcher is the persistent preferred HOME
> activity, and `lockTaskMode="if_whitelisted"` re-locks it as it starts.

## 10. First launch is fast enough

Watch the phone come up from cold after a reboot.

**Pass:** the home screen appears and responds to a tap within a few seconds.

**Fail:** a black screen for more than five seconds, or the launcher restarts
itself. Anything that delays the HOME activity's first window past the five
seconds the system waits before declaring "does not have a focused window" gets
the launcher ANR-killed at boot. This already happened once — WorkManager's
automatic initialiser was doing it — so treat any new startup work as suspect on
the slowest phone in the fleet.

## 11. Recovery

Confirm you can get out, on this exact model:

1. Admin screen → **Remove the lock from this device**, behind the PIN.
2. Check the phone is a normal phone afterwards.
3. Also confirm a factory reset works from the phone's own recovery menu.
   `DISALLOW_FACTORY_RESET` is deliberately not set — a phone that cannot be
   recovered in the field is worse than any escape it prevents.

---

## Results

| Item | Model | Android | Result | Notes |
|---|---|---|---|---|
| 1. Shade blocked | | | | |
| 2. Shade allowed / gear | | | | |
| 3. Telegram self-update | | | | |
| 4. Background location | | | | |
| 5. Settings deep links | | | | |
| 6. Share sheet | | | | |
| 7. Document picker | | | | |
| 8. OEM battery manager | | | | |
| 9. Reboot survival | | | | |
| 10. First launch speed | | | | |
| 11. Recovery | | | | |

Device matrix: must pass on a budget Xiaomi (MIUI/HyperOS) and at least one
non-Xiaomi budget phone. Huawei and other non-GMS devices are best-effort.

Device Owner provisioning cannot be tested on cloud device farms — physical
phones are required.
