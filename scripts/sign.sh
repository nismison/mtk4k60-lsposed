#!/bin/sh
# Phase 3 (Linux chroot): sign the packaged APK. apksigner is a JAR wrapper.
set -e

PROJ=$(cd "$(dirname "$0")/.." && pwd)
APKSIGNER=${APKSIGNER:-/data/local/tmp/bt/android-15/apksigner}
KS="$PROJ/test.keystore"
PASS=android

[ -f "$PROJ/out/base.apk" ] || { echo "run scripts/package.sh first" >&2; exit 1; }

if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -alias test -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass "$PASS" -keypass "$PASS" \
    -dname "CN=MTK4K60 LSPosed Module"
fi

"$APKSIGNER" sign --ks "$KS" --ks-pass "pass:$PASS" --key-pass "pass:$PASS" \
  --out "$PROJ/out/mtk4k60-signed.apk" "$PROJ/out/base.apk"

echo "signed apk: $PROJ/out/mtk4k60-signed.apk"
echo "install (Android shell): sh $PROJ/scripts/install.sh"
