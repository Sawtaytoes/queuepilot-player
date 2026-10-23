#!/usr/bin/env bash
# Runs the emulator checks in the shared QueuePilot Test environment (test-environment/).
# There is no private lab container: every run takes the environment's lock, blocks the
# browser remote while it runs, and restores the person's signed-in session afterward.
# Run it on the Docker host that runs the environment.
set -euo pipefail
container=${QUEUEPILOT_TEST_CONTAINER:-ix-queuepilot-test-queuepilot-test-1}
operation=${1:-status}
in_environment() { docker exec "$container" queuepilot-test "$@"; }
case "$operation" in
  status|start|stop)
    in_environment "$operation"
    ;;
  test)
    # A checkout as the environment sees it: /source is the canonical checkout, and a
    # bare name is a worktree beneath it (/source/.worktrees/<name>).
    checkout=${2:-/source}
    [[ "$checkout" == /* ]] || checkout=/source/.worktrees/$checkout
    if [[ "$checkout" == /source ]]; then name='main checkout'; else name=$(basename "$checkout"); fi
    label=${3:-"tv-lab checks ($name)"}
    docker exec "$container" test -f "$checkout/android/app/build/outputs/apk/debug/app-debug.apk" \
      || { echo "No debug APK in $checkout; build it first"; exit 1; }
    in_environment run --source "$checkout" --label "$label" -- bash "$checkout/tools/tv-checks.sh"
    ;;
  *) echo 'Usage: tv-lab.sh status | start | stop | test [<checkout or worktree name>] [<label>]'; exit 2 ;;
esac
