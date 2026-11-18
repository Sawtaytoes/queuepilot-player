#!/usr/bin/env bash
# The emulator checks, run inside the QueuePilot Test environment by
# `queuepilot-test run`, which holds the lock, installs the checkout's APK, starts the
# fixture server and restores the person's session afterward. Never run it by itself.
set -euo pipefail
source_directory=${QUEUEPILOT_TEST_SOURCE:?Run this through queuepilot-test run --source <checkout>}
fixtures=${FIXTURE_ROOT:?Run this through queuepilot-test run}
# Google TV's recommendations service puts promotions over the app ("Free live TV channels",
# "Buy and rent movies on your TV"). Disable it for this run only: the run's snapshot restore
# returns the owner's emulator session as it was. The first line shows that the last run's
# restore did that.
echo "Disabled packages before this run: $(adb -s emulator-5554 shell pm list packages -d | tr -d '\r' | tr '\n' ' ')"
adb -s emulator-5554 shell pm disable-user --user 0 com.google.android.tvrecommendations
# The launcher still shows its promotions with that package disabled, and this emulator image
# lets neither the shell nor adbd as root disable the launcher's dialog activity
# (docs/testing.md). smoke.screen() answers the launcher instead.
python3 -u "$source_directory/tools/theme_check.py"
bash "$source_directory/tools/ui-inspector/build.sh" "$fixtures/inspector-build"
adb -s emulator-5554 install -r "$fixtures/inspector-build/inspector.apk"
# Every check runs, even after one fails, so one run shows every failure. A check that fails
# can leave the app in a state that makes later ones fail too; fix the first failure first.
failed=()
check() { python3 -u "$source_directory/tools/$1" "${@:2}" || failed+=("$*"); }
check smoke.py
check native_smoke.py
check panels_smoke.py
check markers_smoke.py
check labels_smoke.py
check display_smoke.py
check display_smoke.py --density-320
check rail_smoke.py
check rail_smoke.py --density-320
check details_smoke.py
check queue_history_smoke.py
check household_smoke.py
# Right after household_smoke.py, which leaves the fixture QueuePilot connected. layout_smoke.py
# clears the app's data and opens the demo, so this check cannot run after it.
check cold_start_smoke.py
check restyle_smoke.py
check library_smoke.py
check title_smoke.py
check playback_smoke.py
check conversion_smoke.py
check layout_smoke.py
check layout_smoke.py --density-320
if ((${#failed[@]})); then
  printf 'FAILED: %s\n' "${failed[@]}"
  exit 1
fi
echo "PASS: every emulator check"
