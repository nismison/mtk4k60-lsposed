#!/system/bin/sh
# Phase 2 (Android shell, root): package manifest + resources + classes.dex.
# aapt here is an Android-arm64 binary and cannot run inside the Linux chroot,
# while javac/d8/apksigner only exist in the chroot.
set -e

PROJ=${PROJ:-$(cd "$(dirname "$0")/.." && pwd)}
AAPT=${AAPT:-/data/adb/modules/TA_enhanced/bin/arm64-v8a/aapt}
ANDROID_JAR=${ANDROID_JAR:-/data/local/tmp/plat29/android-10/android.jar}

[ -x "$AAPT" ] || { echo "missing aapt: $AAPT" >&2; exit 1; }
[ -f "$PROJ/out/dex/classes.dex" ] || { echo "run scripts/build-dex.sh first" >&2; exit 1; }

cd "$PROJ"
rm -f out/base.apk
"$AAPT" package -f -M AndroidManifest.xml -S res -A assets -I "$ANDROID_JAR" -F out/base.apk
cd out/dex
"$AAPT" add ../base.apk classes.dex
cd "$PROJ"
echo "unsigned apk: $PROJ/out/base.apk"
echo "next (Linux chroot): sh $PROJ/scripts/sign.sh"
