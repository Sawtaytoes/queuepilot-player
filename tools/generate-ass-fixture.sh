#!/usr/bin/env bash
set -euo pipefail
fixtures=${1:?Fixture directory}
source_dir=$(cd "$(dirname "$0")" && pwd)
ffmpeg -hide_banner -loglevel error -f lavfi -i 'color=c=0x555555:size=1280x720:rate=24' -f lavfi -i 'sine=frequency=440:sample_rate=48000' \
  -i "$source_dir/fixtures/styled.ass" -i "$fixtures/signs.srt" -map 0:v -map 1:a -map 2 -map 3 -t 180 \
  -c:v libx264 -threads 2 -preset ultrafast -crf 28 -c:a aac -c:s copy \
  -metadata:s:a:0 language=jpn -metadata:s:s:0 language=eng -metadata:s:s:0 title='Authored ASS' \
  -metadata:s:s:1 language=eng -metadata:s:s:1 title='Plain text' \
  -disposition:s:0 default -attach "$source_dir/fixtures/ABeeZee-Regular.ttf" \
  -metadata:s:t:0 mimetype=application/x-truetype-font -metadata:s:t:0 filename=ABeeZee-Regular.ttf \
  -y "$fixtures/styled.mkv"
ffmpeg -hide_banner -loglevel error -i "$fixtures/styled.mkv" -vf "scale=1920:1080,ass=$source_dir/fixtures/styled.ass:fontsdir=$source_dir/fixtures" -ss 4 -frames:v 1 -threads 1 -y "$fixtures/styled-reference.png"
