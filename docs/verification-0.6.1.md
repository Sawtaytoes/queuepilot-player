# When a file can't play directly

Version 0.6.1-preview, version code 16. Verified 2026-11-17.

The application ID is unchanged, so this updates the installed preview in place. Checks use
invented fixture data only.

## What changed

1. **Settings → Playback → When a file can't play directly**: **Ask** (the default), **Convert on
   the server**, or **Stop**. It applies when Media3 cannot open the file's container, when its
   decoder fails, or when the file opens but has no video or audio track this TV can decode. A
   network failure is never offered for conversion.
2. **Ask** shows a panel titled "This file can't play directly", with the reason in words (for
   example "This TV can't open WMV files like this one.") and two buttons: **Convert on the
   server** and **Stop so I can fix the file**.
3. **Conversion** uses Plex's universal transcoder. The app first asks
   `/video/:/transcode/universal/decision`, then plays `start.m3u8` with the same parameters:
   `protocol=hls`, `directPlay=0`, `directStream=1`, a new `session` and
   `X-Plex-Session-Identifier`, and a client profile that asks for H.264 video with AAC, AC3,
   E-AC3, or MP3 audio in MPEG-TS. There is no `offset`; the player seeks inside the stream to
   where it was. Plex's selected audio stream is converted. A subtitle from inside the file is
   burned into the picture (`subtitles=burn`). Plex burns the part's stored subtitle, so the app
   first saves the one it shows, or, when the TV could not open the file, the one it planned, with
   `PUT /library/parts` for the playing profile. If Plex does not accept that save, nothing is
   converted and the viewer reads why. A subtitle file beside the media stays as the viewer had
   it, including Off. The token travels as a header to the playing
   server only, never in the address. A decision code of 2000 or more is a refusal, and Plex's
   reason is shown.
4. **The same profile.** A library title converts with the chosen profile's token; a queue item
   converts with the queue profile's grant, so the timeline, watch progress, and scrobbling reach
   the right account. A paused conversion is kept alive with `ping`; leaving the player, or a
   failure of the converted stream, sends `stop` for the session.

## Gates

- **Unit tests**: 146 with 0 failures, including `ServerConversionTest` (7) and
  `PlaybackTracksTest` (5).
- **Lint**: 0 errors; Android lint warnings are in categories `main` already had. ktlint passed.
- **`tools/size_check.py`** and **`tools/theme_check.py`**: passed.
- **Fixtures**: `tools/generate-fixtures.mjs` makes `unplayable.wmv` (WMV 8 video and WMA audio
  in an ASF container) and `conversion/` (an HLS copy of `sample.mp4`). Running the generator into
  an empty folder reproduced the HLS files byte for byte and the WMV file with the same codecs
  and size (ASF headers carry a creation time and a random file ID).
- **Emulator**: the full suite (`tools/tv-checks.sh`) passed every check on the shared QueuePilot
  Test emulator on 2026-11-17, with this change's code and APK on top of 0.6.0 (#26). The change
  was then rebased onto #24 and #30 with no code conflict (only text in `README.md` and
  `docs/testing.md`), and the suite was not run again. `conversion_smoke.py` covered the
  Ask panel and its reason; conversion from the resume point as Ada, with the same parameters for
  `decision` and `start.m3u8`, no `offset`, and no token in the address; `stop` on leaving; Stop so
  I can fix the file; the Stop and Convert on the server settings; Plex's refusal; and a queue that
  plays as Grace converting with Grace's grant, reporting to its own QueuePilot session, with no
  Plex timeline of its own.

Not verified: conversion against a real Plex Media Server, and any physical Android TV. The
emulator fixture's WMV file has no subtitle streams, so saving a burned subtitle first, a refused
save, and reading the viewer's current sidecar from the player are not tested; unit tests cover
only which planned subtitle is burned and which sidecar a conversion keeps. The
fixture stands in for Plex's transcoder by serving a prepared HLS copy, so the emulator proves
the requests, the profile, and the player's handling, not Plex's transcoding itself.
