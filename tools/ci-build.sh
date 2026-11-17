#!/usr/bin/env bash
# The CI build: unit tests, Android lint, ktlint, and assembleDebug. It runs inside the pinned
# Eclipse Temurin image that tools/Dockerfile starts from, with the same Google Android SDK
# packages and Gradle, each verified by checksum. They are kept in /cache, a Docker volume that outlives the run.
#
# The native ASS library is not built here: tools/native-build.sh needs the NDK and a long
# compile. Its Java side still compiles and is tested. The APK this makes is a check, never a
# release; tools/build.sh makes release APKs.
set -euo pipefail
cache=/cache
source_directory=${1:-/source}
mkdir -p "$cache"
# Runs share the volume; one at a time installs into it and builds.
exec 9>"$cache/.lock"
flock 9
export ANDROID_HOME=$cache/android-sdk ANDROID_SDK_ROOT=$cache/android-sdk GRADLE_USER_HOME=$cache/gradle
tools_version=15859902
tools_sha256=4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583
gradle_version=8.13
if ! command -v curl >/dev/null || ! command -v unzip >/dev/null; then
  apt-get update -qq
  apt-get install -y -qq --no-install-recommends curl unzip >/dev/null
fi
if [[ ! -f "$ANDROID_HOME/cmdline-tools/$tools_version/bin/sdkmanager" ]]; then
  rm -rf "${ANDROID_HOME:?}/cmdline-tools"
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  curl -fsSLo /tmp/sdk.zip "https://dl.google.com/android/repository/commandlinetools-linux-${tools_version}_latest.zip"
  echo "$tools_sha256  /tmp/sdk.zip" | sha256sum -c -
  unzip -q /tmp/sdk.zip -d "$ANDROID_HOME/cmdline-tools"
  mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/$tools_version"
  rm /tmp/sdk.zip
fi
if [[ ! -x "$cache/gradle-$gradle_version/bin/gradle" ]]; then
  curl -fsSLo /tmp/gradle.zip "https://services.gradle.org/distributions/gradle-$gradle_version-bin.zip"
  curl -fsSLo /tmp/gradle.sha256 "https://services.gradle.org/distributions/gradle-$gradle_version-bin.zip.sha256"
  echo "$(cat /tmp/gradle.sha256)  /tmp/gradle.zip" | sha256sum -c -
  rm -rf "${cache:?}/gradle-$gradle_version"
  unzip -q /tmp/gradle.zip -d "$cache"
  rm /tmp/gradle.zip /tmp/gradle.sha256
fi
sdkmanager="$ANDROID_HOME/cmdline-tools/$tools_version/bin/sdkmanager"
# `yes` ends on SIGPIPE when sdkmanager stops reading, which pipefail would count as a failure.
{ yes || true; } | "$sdkmanager" --licenses >/dev/null
"$sdkmanager" 'platform-tools' 'platforms;android-36' 'build-tools;36.0.0' >/dev/null
cd "$source_directory/android"
"$cache/gradle-$gradle_version/bin/gradle" --no-daemon --max-workers=2 \
  testDebugUnitTest lintDebug ktlintCheck assembleDebug
