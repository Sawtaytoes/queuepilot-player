#!/usr/bin/env bash
set -euo pipefail
python3 /source/tools/fixture_server.py &
fixture_pid=$!
trap 'kill "$fixture_pid" 2>/dev/null || true' EXIT
bash /source/tools/emulator.sh
