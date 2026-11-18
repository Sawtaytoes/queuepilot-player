#!/usr/bin/env bash
set -euo pipefail
source_directory=${1:?Supply an absolute checkout path}
cache_directory=${2:?Supply an absolute persistent cache path}
[[ "$source_directory" == /* && "$cache_directory" == /* ]]
mkdir -p "$cache_directory/android"
# One build container per checkout, so builds of different worktrees can run side by side. A
# Docker name allows only letters, digits, '_', '.' and '-'; the path's checksum keeps two
# checkouts with the same folder name apart.
checkout_name=$(basename "$source_directory")
checkout_name=$(printf '%s' "$checkout_name" | tr -c 'A-Za-z0-9_.-' '-' | cut -c1-40)
checkout_sum=$(printf '%s' "$source_directory" | cksum | cut -d' ' -f1)
docker run --rm --cpus 2 --memory 6g --name "queuepilot-player-build-$checkout_name-$checkout_sum" \
  -v "$source_directory:/source" \
  -v "$cache_directory:/cache" \
  -v "$cache_directory/android:/root/.android" \
  queuepilot-player-build:0.1 bash -c 'bash /source/tools/native-build.sh && gradle --no-daemon --max-workers=2 testDebugUnitTest lintDebug ktlintCheck assembleDebug assembleRelease'
