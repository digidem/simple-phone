# Signing keys and custody

One key signs both `comapeo-kiosk` and `comapeo-provision`. It is **separate
from CoMapeo's key**, and neither app goes to Google Play, so there is no Play
App Signing and no recovery path through Google.

## Why this key cannot be lost

A Device Owner cannot be uninstalled, and Android will not replace an installed
APK with one signed by a different key. So if the kiosk signing key is lost:

- no enrolled device can ever receive a kiosk update again, and
- the only remedy is factory resetting every device in every fleet.

The key is also baked into every provisioning QR, via
`EXTRA_PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM`. Losing it invalidates
every printed or saved QR as well.

Store it offline and encrypted, with copies held by at least two people, and
record who holds them below.

## Custody

| Holder | Role | Medium | Last verified |
|---|---|---|---|
| _to be filled in_ | | | |

## Debug and production keys must not mix

A device provisioned with a debug-signed kiosk can never receive production
updates — the keys differ, so the update is refused. Test and production fleets
must stay strictly separate.

The signing key is visible in the admin screen and in every enrolment report as
`buildVariant`, which reads `sig:<first 16 hex of the cert SHA-256>`. Check it
before handing a device to a partner organisation.

## Creating the production key

Run this on an offline machine. Do not commit the result.

```sh
keytool -genkeypair -v \
  -keystore comapeo-kiosk-release.jks \
  -alias kiosk \
  -keyalg RSA -keysize 4096 -validity 10950 \
  -dname "CN=Field Kiosk, O=Awana Digital, C=US"
```

Then create `keystore.properties` in the repository root — it is gitignored:

```properties
storeFile=comapeo-kiosk-release.jks
storePassword=…
keyAlias=kiosk
keyPassword=…
```

Without that file the release build falls back to debug signing rather than
failing, so **check `buildVariant` on the built APK** rather than assuming.

## Signing lineage (v3 key rotation)

A lineage is created once, from the first release, so that rotating to a new key
later remains possible. Without it, rotation is impossible and the constraint
above is permanent.

```sh
# One-off: create the lineage by "rotating" the original key to its successor.
apksigner rotate \
  --out comapeo-kiosk.lineage \
  --old-signer --ks comapeo-kiosk-release.jks --ks-key-alias kiosk \
  --new-signer --ks comapeo-kiosk-next.jks   --ks-key-alias kiosk-next
```

Sign releases with the lineage attached:

```sh
apksigner sign \
  --ks comapeo-kiosk-release.jks --ks-key-alias kiosk \
  --lineage comapeo-kiosk.lineage \
  --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true \
  app-release.apk
```

`Certificates.matches` checks the whole certificate history rather than only the
current signer, so a package that has rotated still verifies against a
fingerprint recorded before the rotation.

The lineage file is not a secret, but losing it costs the ability to rotate.
Keep it alongside the keystore.

## Development

`tools/dev-keys.sh` generates a local development keystore and lineage. Both are
gitignored. They are for emulator and test-fleet work only and must never sign
anything a partner organisation receives.
