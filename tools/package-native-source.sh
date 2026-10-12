#!/usr/bin/env bash
# Ship corresponding source and relinkable static objects beside an APK with native code.
set -euo pipefail
source_directory=${1:?Source checkout}
cache_directory=${2:?Build cache}
output=${3:?Output archive path}
fingerprint=$(cat "$source_directory/tools/native-build.sh" "$source_directory/tools/native-sources.json" "$source_directory/android/app/src/main/cpp/ass_bridge.c" | sha256sum | cut -d' ' -f1)
native_cache="$cache_directory/native-ass"
[[ -f "$native_cache/$fingerprint/complete" ]]
staging=$(mktemp -d)
trap 'rm -rf "$staging"' EXIT
mkdir -p "$staging/upstream" "$staging/relink"
git -C "$source_directory" archive HEAD | tar -xf - -C "$staging"
cp "$native_cache"/sources/*.tar.gz "$staging/upstream/"
for abi in arm64-v8a armeabi-v7a x86; do
  mkdir -p "$staging/relink/$abi"
  cp "$native_cache/$fingerprint/$abi/ass_bridge.o" "$staging/relink/$abi/"
  cp "$native_cache/$fingerprint/$abi/install/lib/"*.a "$staging/relink/$abi/"
done
mkdir -p "$(dirname "$output")"
tar -czf "$output" -C "$staging" .
sha256sum "$output"
