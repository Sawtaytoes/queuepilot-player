#!/usr/bin/env bash
# The emulator checks, run inside the QueuePilot Test environment by
# `queuepilot-test run`, which holds the lock, installs the checkout's APK, starts the
# fixture server and restores the person's session afterward. Never run it by itself.
set -euo pipefail
source_directory=${QUEUEPILOT_TEST_SOURCE:?Run this through queuepilot-test run --source <checkout>}
fixtures=${FIXTURE_ROOT:?Run this through queuepilot-test run}
python3 -u "$source_directory/tools/theme_check.py"
bash "$source_directory/tools/ui-inspector/build.sh" "$fixtures/inspector-build"
adb -s emulator-5554 install -r "$fixtures/inspector-build/inspector.apk"
python3 -u "$source_directory/tools/smoke.py"
python3 -u "$source_directory/tools/native_smoke.py"
python3 -u "$source_directory/tools/panels_smoke.py"
python3 -u "$source_directory/tools/markers_smoke.py"
python3 -u "$source_directory/tools/labels_smoke.py"
python3 -u "$source_directory/tools/display_smoke.py"
python3 -u "$source_directory/tools/display_smoke.py" --density-320
python3 -u "$source_directory/tools/rail_smoke.py"
python3 -u "$source_directory/tools/rail_smoke.py" --density-320
python3 -u "$source_directory/tools/details_smoke.py"
python3 -u "$source_directory/tools/queue_history_smoke.py"
python3 -u "$source_directory/tools/household_smoke.py"
python3 -u "$source_directory/tools/restyle_smoke.py"
python3 -u "$source_directory/tools/library_smoke.py"
python3 -u "$source_directory/tools/layout_smoke.py"
python3 -u "$source_directory/tools/layout_smoke.py" --density-320
