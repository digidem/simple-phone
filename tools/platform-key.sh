#!/usr/bin/env bash
# Fetches the public AOSP platform test key and writes it as a PKCS12 keystore
# at build/platform-key/platform.p12 (password "android", alias "platform").
#
# AOSP emulator images ("default" targets) are signed with this key, so an app
# signed with it holds the framework's signature-level permissions on them.
# :handshake needs DISPATCH_PROVISIONING_MESSAGE to start ManagedProvisioning
# the way the setup wizard does. Nothing about the key is secret; it is in the
# AOSP source tree. It is fetched rather than committed so the binary never
# ends up near the real signing key.
set -euo pipefail

cd "$(dirname "$0")/.."
out=build/platform-key
mkdir -p "$out"

base=https://android.googlesource.com/platform/build/+/refs/heads/main/target/product/security
for f in platform.pk8 platform.x509.pem; do
  if [ ! -s "$out/$f" ]; then
    curl -sf "$base/$f?format=TEXT" | base64 -d > "$out/$f"
  fi
done

openssl pkcs8 -in "$out/platform.pk8" -inform DER -nocrypt -out "$out/platform.key.pem"
openssl pkcs12 -export -in "$out/platform.x509.pem" -inkey "$out/platform.key.pem" \
  -out "$out/platform.p12" -name platform -passout pass:android
rm -f "$out/platform.key.pem"

# The emulator's framework must be signed with the same certificate, or the
# permission is silently not granted. Print the digest to compare with
# `apksigner verify --print-certs /system/framework/framework-res.apk`.
openssl x509 -in "$out/platform.x509.pem" -noout -fingerprint -sha256
echo "wrote $out/platform.p12"
