#!/bin/sh
# Phase 1 (Linux chroot): compile Java sources and build classes.dex.
# The Android aapt tool cannot run inside the chroot, so packaging is done by
# scripts/package.sh in the Android shell.
set -e

PROJ=$(cd "$(dirname "$0")/.." && pwd)
ANDROID_JAR=${ANDROID_JAR:-/data/local/tmp/plat29/android-10/android.jar}
D8=${D8:-/data/local/tmp/bt/android-15/d8}

[ -f "$ANDROID_JAR" ] || { echo "missing android.jar: $ANDROID_JAR" >&2; exit 1; }
[ -x "$D8" ] || { echo "missing d8: $D8" >&2; exit 1; }

cd "$PROJ"
rm -rf out
mkdir -p out/classes out/dex

find src -name '*.java' > out/sources.txt
javac -nowarn -source 8 -target 8 -bootclasspath "$ANDROID_JAR" \
    -d out/classes @out/sources.txt

# Package only the module classes, never the de.robv.android.xposed compile-time stubs.
find out/classes -name '*.class' | grep -v '/de/robv/android/xposed/' > out/module_classes.txt
[ -s out/module_classes.txt ] || { echo "no module classes" >&2; exit 1; }

$D8 --lib "$ANDROID_JAR" --min-api 29 --output out/dex \
    $(tr '\n' ' ' < out/module_classes.txt)

echo "dex built: $PROJ/out/dex/classes.dex"
echo "next (Android shell): sh $PROJ/scripts/package.sh"
