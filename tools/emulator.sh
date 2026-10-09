#!/usr/bin/env bash
set -euo pipefail
export ANDROID_AVD_HOME=/cache/avd
mkdir -p "$ANDROID_AVD_HOME"
if [[ ! -f "$ANDROID_AVD_HOME/queuepilot-tv.ini" ]]; then
  printf 'no\n' | avdmanager create avd --name queuepilot-tv --package 'system-images;android-30;android-tv;x86' --force
fi
python3 - <<'PYAVD'
from pathlib import Path
import os
path=Path(os.environ['ANDROID_AVD_HOME'])/'queuepilot-tv.avd/config.ini'
values=dict(line.split('=',1) for line in path.read_text().splitlines() if '=' in line)
values.update({'hw.lcd.width':'1920','hw.lcd.height':'1080','hw.lcd.density':'240','hw.initialOrientation':'landscape','skin.name':'1920x1080','skin.path':'1920x1080','hw.keyboard':'yes'})
path.write_text(''.join(f'{key}={value}\n' for key,value in values.items()))
PYAVD
adb start-server
emulator -avd queuepilot-tv -skin 1920x1080 -wipe-data -no-window -no-audio -no-boot-anim -gpu swiftshader -no-snapshot -memory 4096 -cores 4 > /cache/emulator.log 2>&1 &
emulator_pid=$!
trap 'kill "$emulator_pid" 2>/dev/null || true' EXIT
adb reconnect offline
adb wait-for-device
for attempt in $(seq 1 120); do
  if [[ "$(adb shell getprop sys.boot_completed | tr -d '\r')" == '1' ]]; then break; fi
  sleep 2
done
[[ "$(adb shell getprop sys.boot_completed | tr -d '\r')" == '1' ]]
adb shell wm size reset
adb shell wm density reset
adb install -r /source/android/app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n dev.queuepilot.player.prototype/dev.queuepilot.player.MainActivity
wait "$emulator_pid"
