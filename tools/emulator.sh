#!/usr/bin/env bash
set -euo pipefail
export ANDROID_AVD_HOME=/cache/avd
export TV_WIDTH=${TV_WIDTH:-1920} TV_HEIGHT=${TV_HEIGHT:-1080} TV_DENSITY=${TV_DENSITY:-240}
[[ "$TV_WIDTH,$TV_HEIGHT,$TV_DENSITY" == "1920,1080,240" || "$TV_WIDTH,$TV_HEIGHT,$TV_DENSITY" == "3840,2160,480" ]]
mkdir -p "$ANDROID_AVD_HOME"
if [[ ! -f "$ANDROID_AVD_HOME/queuepilot-tv.ini" ]]; then
  printf 'no\n' | avdmanager create avd --name queuepilot-tv --package 'system-images;android-30;android-tv;x86' --force
fi
python3 - <<'PYAVD'
from pathlib import Path
import os
path=Path(os.environ['ANDROID_AVD_HOME'])/'queuepilot-tv.avd/config.ini'
values=dict(line.split('=',1) for line in path.read_text().splitlines() if '=' in line)
width,height,density=(os.environ[key] for key in ('TV_WIDTH','TV_HEIGHT','TV_DENSITY'))
values.update({'hw.lcd.width':width,'hw.lcd.height':height,'hw.lcd.density':density,'hw.initialOrientation':'landscape','skin.name':width+'x'+height,'skin.path':width+'x'+height,'hw.keyboard':'yes'})
path.write_text(''.join(f'{key}={value}\n' for key,value in values.items()))
PYAVD
rm -f /cache/adb-ready
adb start-server
touch /cache/adb-ready
emulator -avd queuepilot-tv -read-only -skin "${TV_WIDTH}x${TV_HEIGHT}" -wipe-data -no-window -no-audio -no-boot-anim -gpu swiftshader -no-snapshot -memory 4096 -cores 4 > /cache/emulator.log 2>&1 &
emulator_pid=$!
trap 'kill "$emulator_pid" 2>/dev/null || true' EXIT
adb reconnect offline
for attempt in $(seq 1 300); do
  kill -0 "$emulator_pid" 2>/dev/null || { tail -n 30 /cache/emulator.log; exit 1; }
  if adb devices | grep -q "emulator-.*device$"; then break; fi
  sleep 1
done
timeout 10 adb wait-for-device
for attempt in $(seq 1 300); do
  if [[ "$(adb shell getprop sys.boot_completed | tr -d '\r')" == '1' ]]; then break; fi
  sleep 2
done
[[ "$(adb shell getprop sys.boot_completed | tr -d '\r')" == '1' ]]
adb shell wm size reset
adb shell wm density reset
adb install -r /source/android/app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n dev.queuepilot.player.prototype/dev.queuepilot.player.MainActivity
wait "$emulator_pid"
