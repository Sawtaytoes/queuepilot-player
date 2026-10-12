#!/usr/bin/env bash
set -euo pipefail
output=${1:?Supply a scratch output directory}
mkdir -p "$output/classes"
sdk=${ANDROID_HOME:?}
java_api="$sdk/platforms/android-36/android.jar"
build="$sdk/build-tools/36.0.0"
javac -source 8 -target 8 -cp "$java_api" -d "$output/classes" /source/tools/ui-inspector/Dump.java
"$build/d8" --lib "$java_api" --output "$output" "$output/classes/dev/queuepilot/inspector/Dump.class"
"$build/aapt2" link -o "$output/inspector-unsigned.apk" --manifest /source/tools/ui-inspector/AndroidManifest.xml -I "$java_api"
cd "$output"
jar uf inspector-unsigned.apk classes.dex
if [[ ! -f inspector-key.jks ]];then keytool -genkeypair -keystore inspector-key.jks -storepass android -keypass android -alias inspector -dname 'CN=Emulator Inspector' -keyalg RSA -validity 3650 >/dev/null;fi
"$build/apksigner" sign --ks inspector-key.jks --ks-pass pass:android --out inspector.apk inspector-unsigned.apk
