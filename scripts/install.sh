#!/system/bin/sh
# Install (or reinstall) the signed module.
set -e
PROJ=${PROJ:-$(cd "$(dirname "$0")/.." && pwd)}
APK="$PROJ/out/mtk4k60-signed.apk"
[ -f "$APK" ] || { echo "missing $APK" >&2; exit 1; }
pm install -r "$APK"
echo "installed. Now enable the module + scope com.kimcy929.secretvideorecorder in LSPosed, then reboot."
