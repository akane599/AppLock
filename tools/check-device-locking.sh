#!/bin/sh
# Read-only platform checks plus credential migration in temporary synthetic preferences.
# Build :app:assembleDebug first. Does not install or replace AppLock on the device.
set -eu
cd "$(dirname "$0")/.."
audit_sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
: "${audit_sdk:?Set ANDROID_HOME or ANDROID_SDK_ROOT}"
audit_build_tools=${ANDROID_BUILD_TOOLS_VERSION:-36.0.0}
audit_platform=$audit_sdk/platforms/android-37
if [ ! -f "$audit_platform/android.jar" ]; then audit_platform=$audit_sdk/platforms/android-37.0; fi
audit_apk=${1:-app/build/outputs/apk/debug/app-debug.apk}
audit_stub=hidden-api/build/intermediates/compile_library_classes_jar/debug/bundleLibCompileToJarDebug/classes.jar
audit_tmp=$(mktemp -d)
audit_remote=/data/local/tmp/applock-audit-$(basename "$audit_tmp")
cleanup() {
    adb shell rm -f "$audit_remote.dex" "$audit_remote.apk" >/dev/null 2>&1 || true
    rm -rf "$audit_tmp"
}
trap cleanup EXIT HUP INT TERM
mkdir -p "$audit_tmp/classes" "$audit_tmp/dex"
javac -source 17 -target 17 -cp "$audit_platform/android.jar:$audit_stub" \
    -d "$audit_tmp/classes" tools/DeviceLockingProbe.java
java -cp "$audit_sdk/build-tools/$audit_build_tools/lib/d8.jar" com.android.tools.r8.D8 \
    --min-api 26 --output "$audit_tmp/dex" "$audit_tmp"/classes/*.class
adb push "$audit_tmp/dex/classes.dex" "$audit_remote.dex"
adb push "$audit_apk" "$audit_remote.apk"
adb shell "CLASSPATH=$audit_remote.dex:$audit_remote.apk app_process /system/bin DeviceLockingProbe $audit_remote.apk $audit_remote-prefs"
