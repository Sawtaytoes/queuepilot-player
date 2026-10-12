#!/usr/bin/env bash
# A named, isolated Android TV lab. No host ports and no household ADB connection.
set -euo pipefail
lab_name=queuepilot-tv-lab
operation=${1:-status}
owned_lab() {
  [[ "$(docker inspect "$lab_name" --format '{{index .Config.Labels "dev.queuepilot.tv-lab"}}')" == owned ]]
}
case "$operation" in
  start)
    source_directory=${2:?Supply an absolute checkout path}
    cache_directory=${3:?Supply an absolute emulator cache path, separate from the build cache}
    fixture_directory=${4:?Supply an absolute generated-fixture directory}
    [[ "$source_directory" == /* && "$cache_directory" == /* && "$fixture_directory" == /* ]]
    [[ -f "$source_directory/android/app/build/outputs/apk/debug/app-debug.apk" ]]
    [[ -f "$fixture_directory/sample.mp4" && -f "$fixture_directory/high.jpg" && -f "$fixture_directory/ada.jpg" ]]
    [[ -f "$fixture_directory/tracks.mkv" && -f "$fixture_directory/tracks.mp4" ]] || {
      echo 'Regenerate fixtures before testing the playback menus'; exit 1;
    }
    mkdir -p "$cache_directory/android"
    if ! docker inspect "$lab_name" --format '{{.State.Running}}' 2>/dev/null | grep -q true; then rm -f "$cache_directory/adb-ready"; fi
    if docker inspect "$lab_name" >/dev/null 2>&1; then
      owned_lab || { echo 'Refusing to change a container without the lab label'; exit 1; }
      mounted_source=$(docker inspect "$lab_name" --format '{{range .Mounts}}{{if eq .Destination "/source"}}{{.Source}}{{end}}{{end}}')
      [[ "$mounted_source" == "$source_directory" ]] || { echo 'Lab uses another checkout; remove the owned lab before changing sources'; exit 1; }
      if [[ "$(docker inspect "$lab_name" --format '{{.State.Running}}')" != true ]]; then docker start "$lab_name"; fi
    else
      docker run -d --init --name "$lab_name" --label dev.queuepilot.tv-lab=owned \
        --device /dev/kvm --cpus 6 --memory 10g \
        --mount "type=bind,src=$source_directory,dst=/source,readonly" \
        --mount "type=bind,src=$cache_directory,dst=/cache" \
        --mount "type=bind,src=$cache_directory/android,dst=/root/.android" \
        --mount "type=bind,src=$fixture_directory,dst=/fixtures" \
        queuepilot-player-build:0.1 bash /source/tools/lab-entrypoint.sh
    fi
    # The entrypoint owns ADB startup. A concurrent `adb getprop` can race its
    # daemon bind and terminate the lab before the emulator is launched.
    for attempt in $(seq 1 60); do
      if docker exec "$lab_name" bash -c 'test -f /cache/adb-ready'; then break; fi
      [[ "$(docker inspect "$lab_name" --format '{{.State.Running}}')" == true ]] || { docker logs --tail 20 "$lab_name"; exit 1; }
      sleep 1
    done
    docker exec "$lab_name" test -f /cache/adb-ready
    echo 'ADB ready. Use status until Android reports boot_completed=1, then test.'
    ;;
  status)
    owned_lab || { echo 'No owned TV lab is configured'; exit 1; }
    docker inspect "$lab_name" --format 'Container: {{.State.Status}}; published ports: {{json .HostConfig.PortBindings}}'
    if [[ "$(docker inspect "$lab_name" --format '{{.State.Running}}')" == true ]]; then
      docker exec "$lab_name" adb -s emulator-5554 shell getprop sys.boot_completed || true
    fi
    ;;
  test)
    owned_lab || exit 1
    hardware=$(docker exec "$lab_name" adb -s emulator-5554 shell getprop ro.hardware | tr -d '\r')
    [[ "$hardware" == ranchu || "$hardware" == goldfish ]] || { echo 'Refusing a physical-device target'; exit 1; }
    [[ "$(docker exec "$lab_name" adb -s emulator-5554 shell getprop sys.boot_completed | tr -d '\r')" == 1 ]]
    # Android can report boot complete before emulator.sh finishes installing the viewer.
    for attempt in $(seq 1 30); do
      if docker exec "$lab_name" adb -s emulator-5554 shell pm path dev.queuepilot.player.prototype | grep -q '^package:'; then break; fi
      sleep 1
    done
    docker exec "$lab_name" adb -s emulator-5554 shell pm path dev.queuepilot.player.prototype | grep -q '^package:'
    docker exec "$lab_name" bash /source/tools/ui-inspector/build.sh /fixtures/inspector-build
    docker exec "$lab_name" adb -s emulator-5554 install -r /fixtures/inspector-build/inspector.apk
    docker exec "$lab_name" python3 -u /source/tools/smoke.py
    docker exec "$lab_name" python3 -u /source/tools/native_smoke.py
    docker exec "$lab_name" python3 -u /source/tools/panels_smoke.py
    docker exec "$lab_name" python3 -u /source/tools/layout_smoke.py
    docker exec "$lab_name" python3 -u /source/tools/layout_smoke.py --density-320
    ;;
  stop)
    owned_lab || exit 1
    docker stop "$lab_name"
    ;;
  remove)
    owned_lab || exit 1
    docker rm -f "$lab_name"
    ;;
  *) echo 'Usage: tv-lab.sh start <source> <emulator-cache> <fixtures> | status | test | stop | remove'; exit 2 ;;
esac
