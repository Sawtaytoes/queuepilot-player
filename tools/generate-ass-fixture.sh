#!/usr/bin/env bash
set -euo pipefail
fixtures=${1:?Fixture directory}
source_dir=$(cd "$(dirname "$0")" && pwd)
ffmpeg -hide_banner -loglevel error -f lavfi -i 'color=c=0x555555:size=1280x720:rate=24' -f lavfi -i 'sine=frequency=440:sample_rate=48000' \
  -map 0:v -map 1:a -t 180 -c:v libx264 -threads 2 -preset ultrafast -crf 28 -c:a aac -y "$fixtures/styled-base.mp4"
# Mux the overlapping ASS events with MKVToolNix. FFmpeg's transcoding scheduler
# defers subsequent equal-timestamp long cues until the first cue ends.
mkvmerge -q -o "$fixtures/styled.mkv" --language 1:jpn "$fixtures/styled-base.mp4" \
  --language 0:eng --track-name '0:Authored ASS' --default-track-flag 0:yes "$source_dir/fixtures/styled.ass" \
  --language 0:eng --track-name '0:Plain text' --default-track-flag 0:no "$fixtures/signs.srt" \
  --attachment-mime-type application/x-truetype-font --attachment-name ABeeZee-Regular.ttf \
  --attach-file "$source_dir/fixtures/ABeeZee-Regular.ttf"
ffmpeg -hide_banner -loglevel error -i "$fixtures/styled.mkv" -vf "scale=1920:1080,ass=$source_dir/fixtures/styled.ass:fontsdir=$source_dir/fixtures" -ss 4 -frames:v 1 -threads 1 -y "$fixtures/styled-reference.png"
