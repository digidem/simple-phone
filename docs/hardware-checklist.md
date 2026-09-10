# Hardware verification checklist

Everything here is OEM- and SystemUI-dependent and cannot be settled on an
emulator. Work through it on each phone model before that model is used for a
real deployment.

You do not need to be the author of this code to run it. You need a phone you
can factory reset, a second phone with the Field Kiosk Setup app on it, and
about an hour per model.

**What the emulator already told us** is recorded against each item. An AOSP
emulator is a *floor*, not an answer: Xiaomi's HyperOS and similar skins replace
large parts of SystemUI, and that is exactly where these behaviours live.

Where an item says "the field app", use whichever app the deployment is built
around — the one people will spend their day in.

Record results in the table at the bottom and commit it.

---

## Before you start

There are two ways to get a phone into the state under test, and they are not
equivalent.

**By QR, from the setup app.** This is what a deployment actually does, and it
is what item 15 checks. A phone provisioned this way has USB debugging switched
off and no way to reach Developer options, so ADB is not available to you
afterwards.

**By ADB, on a lab phone.** Faster for items 1 to 14, and it lets you keep a
shell. Enable USB debugging first. **On Xiaomi (MIUI/HyperOS) you must also
enable "USB debugging (Security settings)"**, a separate switch further down the
same Developer options screen; without it `dpm set-device-owner` fails and QR
provisioning may too. The phone must be freshly reset with no accounts added.

```sh
adb install kiosk/app/build/outputs/apk/debug/app-debug.apk
adb shell dpm set-device-owner org.awana.kiosk/.KioskDeviceAdminReceiver
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

## 3. A third-party self-updater

1. Install an old build of an app that updates itself — Telegram is the usual
   case — as part of a deployment.
2. Open it and let it offer to update itself.

**Pass:** the update completes.

**Fail:** it sends the user into a Settings screen — exactly the confusing dead
end this project exists to prevent. `REQUEST_INSTALL_PACKAGES` is an app-op, not
a runtime permission, so a Device Owner cannot pre-grant it.

If it fails, the fallback is the admin screen's install-from-URL, or an updater
in the kiosk that manages the app from a configured source.

> Emulator: **not testable.** Needs the real app and a real update.

## 4. Background location

1. Provision, then open the field app and start recording a track.
2. Lock the screen and leave the phone for ten minutes.
3. Come back and check the track is continuous.

**Pass:** no gap in the track.

> Emulator: **partly.** `setPermissionGrantState` is confirmed to grant
> `ACCESS_FINE_LOCATION` before `ACCESS_BACKGROUND_LOCATION`, and the grants
> take. Whether the OEM then actually lets the app run in the background is item
> 8, not this one.

## 5. Settings deep links

1. In the field app, trigger anything that opens a Settings screen.

**Record which happens:** the Settings screen opens, or nothing happens at all.
Both are bad for the user; the answer decides how much work the app-side gating
workstream needs. Write down exactly which screens you tried.

> Emulator: untested on real SystemUI.

## 6. The share sheet

1. In the field app, share something.

**Expected:** the chooser opens and shows targets that are not in the deployment,
which do nothing when tapped. This is why a field app needs its own filtered
share picker — a separate workstream.

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
   Apps → *the app* → Battery saver, plus a separate Autostart toggle.
3. Set the field app to unrestricted, and enable autostart.
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

1. Provision, then reboot the phone.
2. Wait for it to come back.

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

Confirm you can get out, on this exact model. **ADB is not part of this.** A
phone provisioned by QR has USB debugging off and Developer options unreachable
behind the lock, so a shell is not available to you in the field however the
policy is configured. There are two routes and both must work:

1. Admin screen → **Remove the lock from this device**, behind the admin PIN.
   Check the phone is a normal phone afterwards: a launcher of its own, a
   reachable shade, apps that can be uninstalled.
2. A factory reset from the phone's own bootloader or recovery menu, for when
   the PIN has been lost. Find the key combination for this model and write it
   down. `DISALLOW_FACTORY_RESET` is deliberately not set precisely so this
   works.

**Record:** the recovery key combination, and whether the reset left the phone
usable or demanded an account it does not have.

## 12. The clock, with no network and no SIM

The kiosk turns on automatic time and time zone, but automatic time needs a
network — NITZ from a mobile operator, or NTP over an internet connection.
Android does **not** take the time from GPS. A phone that has never seen either
can be days out, and every timestamp it records is wrong with it.

1. Provision a phone with no SIM in it, over the trainer's hotspot, which has no
   internet.
2. Check the clock against a known-good phone straight away.
3. Leave it off any network for a few days and check again.

**Record:** how far out it was at each check, whether it corrected itself the
first time it reached a real network or a SIM, and whether a trainer can fix it
by hand — from the temporary unlock in the admin screen, or not at all.

## 13. Peer-to-peer sync over Wi-Fi

Field apps sync device to device over a local network, and the deployed phones
cannot configure Wi-Fi themselves. The networks in the deployment are joined at
provisioning for exactly this reason, so this item checks that path end to end.

1. Set up a deployment with the team's sync network listed, and provision two
   phones from it.
2. With no trainer's phone present, check both phones are on that network.
3. Sync between them in the field app.
4. Reboot one, and take the other out of range and back, and check both rejoin
   without anyone touching Wi-Fi settings.

**Record:** whether the pre-approved networks were joined and are rejoined
automatically, and — if the field app syncs over Wi-Fi Direct or a hotspot of
its own rather than a shared network — whether that still works with
`DISALLOW_CONFIG_WIFI` set.

## 14. Getting data off a device over USB

Plugging a phone into a computer is the obvious way to collect data from a
deployment, but the USB-mode chooser lives in the notification shade, which is
unreachable with the shade off.

1. Plug a provisioned phone into a computer.
2. Try to switch it from charging to file transfer.

**Record:** whether the chooser can be reached at all, whether the computer sees
the phone's storage, and if not, which route is left — the share sheet to
another app on the device, or the temporary unlock in the admin screen.

## 15. The whole QR provisioning handoff

Items 1 to 14 can be set up over ADB. This one is the real thing, and it is the
only item that tests the parts of the flow the platform owns.

1. Factory reset the phone.
2. Tap the middle of the first "Hello" screen six times.
3. Scan the QR from the setup app.
4. Watch it through to the kiosk home screen without touching it.

**Record at each stage:**

- whether the six-tap gesture opens a QR reader on this skin at all;
- whether the setup wizard downloads the kiosk APK over a hotspot with **no**
  internet, or whether the OEM wizard insists on a connectivity check first;
- whether the provisioning-mode screen appears and offers device ownership, and
  what happens if the user is offered a choice;
- whether the policy-compliance screen appears while the payload installs, and
  whether it lets the user continue when it finishes;
- how long the whole thing takes, and whether the phone appears on the setup
  app's dashboard with no failures against it.

**Fail:** anything that needs a Google account, an internet connection, or a tap
the trainer walkthrough does not mention.

---

## Results

| Item | Model | Android | Result | Notes |
|---|---|---|---|---|
| 1. Shade blocked | | | | |
| 2. Shade allowed / gear | | | | |
| 3. Self-updater | | | | |
| 4. Background location | | | | |
| 5. Settings deep links | | | | |
| 6. Share sheet | | | | |
| 7. Document picker | | | | |
| 8. OEM battery manager | | | | |
| 9. Reboot survival | | | | |
| 10. First launch speed | | | | |
| 11. Recovery | | | | |
| 12. Clock with no network | | | | |
| 13. Peer-to-peer sync | | | | |
| 14. Data off over USB | | | | |
| 15. QR provisioning | | | | |

Device matrix: must pass on a budget Xiaomi (MIUI/HyperOS) and at least one
non-Xiaomi budget phone. Huawei and other non-GMS devices are best-effort.

Device Owner provisioning cannot be tested on cloud device farms — physical
phones are required.
