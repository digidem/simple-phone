#!/usr/bin/env bash
# Generates a development keystore and a v3 signing lineage, then writes
# keystore.properties. Everything it produces is gitignored.
#
# These keys are for emulator and test-fleet work only. A device provisioned
# with a debug-signed kiosk can never receive production updates.
set -euo pipefail

cd "$(dirname "$0")/.."

STORE=comapeo-kiosk-dev.jks
NEXT=comapeo-kiosk-dev-next.jks
LINEAGE=comapeo-kiosk-dev.lineage
PASS=devdevdev
DNAME="CN=CoMapeo Kiosk (development), O=Awana Digital, C=US"

if [ -f "$STORE" ]; then
  echo "$STORE already exists; leaving it alone."
else
  keytool -genkeypair -v -keystore "$STORE" -alias kiosk \
    -keyalg RSA -keysize 4096 -validity 10950 \
    -storepass "$PASS" -keypass "$PASS" -dname "$DNAME"
fi

if [ -f "$NEXT" ]; then
  echo "$NEXT already exists; leaving it alone."
else
  keytool -genkeypair -v -keystore "$NEXT" -alias kiosk-next \
    -keyalg RSA -keysize 4096 -validity 10950 \
    -storepass "$PASS" -keypass "$PASS" -dname "$DNAME (rotated)"
fi

APKSIGNER=$(ls "${ANDROID_HOME:-$HOME/Library/Android/sdk}"/build-tools/*/apksigner | sort -V | tail -1)

if [ -f "$LINEAGE" ]; then
  echo "$LINEAGE already exists; leaving it alone."
else
  "$APKSIGNER" rotate --out "$LINEAGE" \
    --old-signer --ks "$STORE" --ks-key-alias kiosk --ks-pass "pass:$PASS" \
    --new-signer --ks "$NEXT" --ks-key-alias kiosk-next --ks-pass "pass:$PASS"
fi

cat > keystore.properties <<EOF
storeFile=$STORE
storePassword=$PASS
keyAlias=kiosk
keyPassword=$PASS
EOF

echo
echo "Development signing certificate:"
keytool -list -v -keystore "$STORE" -storepass "$PASS" -alias kiosk \
  | grep -i "SHA256:" | head -1
