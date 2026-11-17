# Plex's track choices, the resume question, chapters, skip amounts, and frame-rate matching

Version 0.6.0-preview, version code 15. Verified 2026-11-17.

The application ID is unchanged, so this updates the installed preview in place. Checks use
invented fixture data only.

## What changed

1. **Audio and subtitles from Plex.** The player plays the streams that Plex marks
   `selected="1"` on the item's first part. Plex chooses them from the profile's saved choice and
   the account's settings. If Plex selected audio but no subtitle, subtitles are off. Only when
   Plex selected nothing does the app read the account's plex.tv `profile` (`defaultAudioLanguage`,
   `autoSelectAudio`, `defaultSubtitleLanguage`, `autoSelectSubtitle`: manual, with foreign audio,
   or always). A library title reads the chosen profile's settings. A queue item reads the queue
   profile's settings from the plan's optional `languages`, which the QueuePilot server can send
   because it holds that profile's verified plex.tv credential. Without them, a queue that plays
   as the chosen profile uses the chosen profile's settings, and another profile's queue keeps
   Plex's selection. The QueuePilot server sends `languages` from its pull request 389
   (https://github.com/Sawtaytoes/queuepilot/pull/389) on, read with the queue profile's own
   plex.tv proof; an older server omits them. Subtitle files beside the media (Plex streams with a `key`) load as
   sideloaded tracks. A change in the Audio or Subtitles panel is saved with
   `PUT /library/parts/{partId}?audioStreamID=…&allParts=1` or `subtitleStreamID=…` (0 is Off),
   with the token of the profile that plays the item. Automatic audio is not saved. Embedded ASS
   keeps the libass renderer.
2. **The resume question.** A title with a saved position asks **Resume from 12:34** (focused) or
   **Start from the beginning**. Resume still applies Rewind on resume. A queue continues without
   asking, because its button already says Resume queue.
3. **Chapters.** Metadata requests add `includeChapters=1`. When an item has chapters, the player's
   top bar shows **Chapters**. Its panel opens below that control, lists each chapter's start time
   and name with Plex's chapter image, and seeks to the chosen chapter.
4. **Skip amounts by length.** Forward is 10 seconds for items under one hour and 30 seconds for
   items one hour or longer; Back is 10 seconds. All three are in Settings → Playback.
5. **Match frame rate** (On by default). Plex's video `frameRate`, or the decoder's, chooses the
   display mode with the same resolution whose refresh rate is the frame rate or a whole multiple
   of it, through `WindowManager.LayoutParams.preferredDisplayModeId`. On Android 11 (the Shield),
   `Surface.setFrameRate` only allows seamless changes, and Android's TV guidance names
   `preferredDisplayModeId` for mode switches that blank the screen. The current mode is kept when
   it already matches. Leaving the player clears the request.
6. **Subtitle size and background** follow Android's caption style. Settings → Playback opens
   Android's caption settings when the device has them.
7. Settings → Playback holds these options, and the player's options menu opens the same panel.

## Gates

- **Unit tests**: 127 tests with 0 failures, including `PlexTracksTest` (15) and
  `PlaybackRulesTest` (9).
- **Lint**: 0 errors; 117 warnings and 1 hint, all in categories that `main` already had. ktlint
  passed.
- **`tools/size_check.py`** and **`tools/theme_check.py`**: passed.
- **Emulator**: the full suite (`tools/tv-checks.sh`) ran on the shared QueuePilot Test emulator
  on 2026-11-17, against commit 0e5e491 and its APK. Every check passed, including
  `playback_smoke.py` (Plex-selected tracks, saves per profile, a sidecar subtitle, account
  languages, the resume question, chapters, skip amounts, and frame-rate matching), except three.
  `display_smoke.py --density-320` tapped 125% in the Display size panel, and the panel stayed
  open at 70%. Three other branches failed the same way that day; pull request 33 adds a retry for
  the lost tap. Both `rail_smoke.py` runs that followed then failed only because the size was still
  70% (a rail 80 pixels wide where 114 was expected at 100%). This change does not alter the
  Display size panel or the rail; its only `TvPanel` change is an optional row decorator, which
  only the Chapters panel sets.
- **`bin/check-app-repo-for-personal-detail`**: no household detail in this branch's commits.

Not verified: any physical Android TV. Display-mode switching and the caption settings screen
were checked only on the Android TV emulator, which has one display mode, so the emulator proves
the request and its clearing, not a real refresh-rate change on a Shield.
