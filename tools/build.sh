#!/usr/bin/env bash
set -euo pipefail
source_directory=${1:?Supply an absolute checkout path}
cache_directory=${2:?Supply an absolute persistent cache path}
[[ "$source_directory" == /* && "$cache_directory" == /* ]]
mkdir -p "$cache_directory/android"
docker run --rm --name queuepilot-player-build-check \
  -v "$source_directory:/source" \
  -v "$cache_directory:/cache" \
  -v "$cache_directory/android:/root/.android" \
  queuepilot-player-build:0.1
