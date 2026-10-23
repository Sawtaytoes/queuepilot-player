# QueuePilot TV

A native Android TV preview with a queue-first home screen, Plex device-link sign-in,
profile selection, artwork browsing, and a Media3 player. The launcher label is
**QueuePilot**. It keeps the existing `dev.queuepilot.player.prototype` application ID, so
it updates the app formerly labeled QueuePilot Lab in place.

## Connect your library

Choose **Sign in with Plex**, open **plex.tv/link** on a phone or computer, and enter the
four-character code displayed on the TV. The app then shows Plex Home profiles and discovers
their servers. A single available server connects automatically; multiple servers appear as
cards. Protected profiles use their existing Plex Home PIN. No API token, profile name, or
Plex server address needs to be entered on the television. The profile picker marks a
protected profile **🔒 PIN** and the account owner **Owner**; other profiles carry no label.

The home screen follows the approved queue-first presentation: a left navigation rail,
queues first, continuation below, and library browsing. The rail shows icons only while
focus is in the content and expands with labels when focus moves left onto it; Left lands on
the selected entry, and Back from a top-level screen returns to the rail. Home lists every queue, the ones
that play as the active profile first and those that switch profile after, each in the
server's order. A card names its audience as text ("Ada, Grace") beside the faces, and a
switching card names the profile it switches to. While a people filter is active, Home and
Watching say "Showing N of M queues" with a one-press **Clear**. The continue cards name
the queue or the media server they come from; an episode is named by its show, then
"S1 · E9 · Title". The rail's avatar switches profile. D-pad focus outlines the selected
card. Back returns to the preceding screen. Libraries use square-cornered artwork cards,
and configuration dialogs use bounded widths. **Explore the demo** opens a fictional
catalog for reviewing the same native layout without an account.

**Display size** in Settings scales the whole interface to 70, 80, 90, 100, 110 or 125
percent, and applies at once. Grids gain columns at smaller sizes. The Media3 control groups
are scaled about their anchored edge. The title screen keeps a long title to two lines and a
summary to four, with **More** for the full text.

A title screen shows what Plex sends about the title: year, content rating, length or season
and episode counts, studio, genres, critic and audience scores, time left or episodes
watched, director and writers, and a cast row with portraits. A show's season list and a
season's episode list start with the same facts for that show or season.

Icons are [Lucide](https://lucide.dev) icons, vendored as Android vector drawables from
the pinned `lucide-static` release under its ISC license (`tools/lucide/LICENSE`). They mark
the rail, the player controls, Skip, the Settings rows, the queue card labels, and the title
actions. Each one sits beside a text label or carries a spoken name.

Credentials and the profile roster are encrypted with Android Keystore. Previously connected
unprotected profiles can use their saved server credentials and catalog cache without a
Plex cloud lookup. Protected-profile switching currently requires an online PIN check.
An unreachable server still cannot stream media, and cached tokens cannot renew themselves.
Account credentials go only to Plex; discovered server credentials go only to direct server
connections. Redirects and relays are refused, and cleartext server connections are limited
to local private IP addresses.

## Playback and current limits

The player opens supported files directly with Media3, reports decoded video dimensions,
and offers a Cast overlay. Artwork cards request a 720-pixel derivative; title artwork
requests 1440 pixels. The detail screen can compare actual 240px and 1440px responses.
The interface does not claim native 4K composition on every Android TV device.

Connect a QueuePilot server in Settings or Watching. The TV lists **Plex queues only**,
with people portraits, the audience as text, and the existing required/optional audience
filter. A queue starts
inside the app using an engine-produced plan. The actual Plex Home profile must match the
queue binding; a mismatched profile is offered an explicit switch before playback.
Native playback never sends `/api/play` or controls another app or the room.

Playback sends profile-scoped Plex progress and resume history. Queue starts also preserve
QueuePilot-owned history and authored section boundaries. Pending native progress is encrypted
locally and retried in order; invalid or expired grants are retained for review rather than
silently borrowing owner privileges. Queue snapshots and the people filter survive restart;
starting a new queue still requires the server. An active lineup is not restored after a crash.

The Cast overlay shows available portraits, actor names and roles from Plex metadata.
It is the title cast, not scene-by-scene actor recognition. An episode lists its own credits
first, then its season's and show's, once each: many libraries keep the cast only on the show.
Portraits load through the connected server's image transcoder, including external portrait
URLs; a portrait the server cannot fetch is requested directly without credentials.
Profiles also show their avatars.
**Rewind on resume** is Off by default. Choose 1, 3, 5 or 10 seconds in Settings; a stored
choice is kept.
Plex intro and credits markers show **Skip Intro** or **Skip Credits** at the bottom right
while playback is inside the marker. The button takes D-pad focus without pausing; Enter
seeks to the marker end. It hides after eight seconds unless the controls are showing, and
when playback leaves the marker. Final credits (Plex's `final` flag, or credits ending within
two seconds of the end) finish the title instead: Plex receives a finished timeline and a
queue moves to its next item. Skips never leave an authored queue section, and nothing is
skipped automatically.
While the time bar is moved, by touch or with the D-pad, a preview of the frame at that time
appears above the bar with the time beneath it. The thumbnails come from Plex's seek index
(`/library/parts/<id>/indexes/sd`, a BIF file), fetched on the first seek of a title and
only when Plex reports one; a file over 32 MB or one that does not parse is ignored, and the
time is shown alone. Queue playback keeps the preview inside the authored section.
Player focus highlights are rounded, including the seek buttons with interval labels.
Options use QueuePilot panels with visible selections and D-pad focus. Browsing/settings
use a side panel; playback audio, subtitle and speed menus stay above their launching
controls. Back closes a panel and returns focus; playback controls stay visible while
a menu is open. Embedded Matroska ASS uses libass with attached fonts, authored
styles, positioned signs, drawings and animation. See [ASS rendering](docs/ass-rendering.md)
for its resource bounds and seek limitations.
See [native sessions](docs/native-sessions.md).

This preview does not negotiate transcoding, import real story
ordering manifests, or download media. External ASS and ASS in other containers remain
outside the custom renderer. HDR conversion, Dolby Vision, and passthrough still need
explicit device evidence.
Library grids request pages of 30 items. Demo Episode 9 → OVA → Episode 10 ordering and
progress remain separate from real Plex items. Use this for testing rather than the family
player's default viewing sessions.

## Build and test

The toolchain uses official Eclipse Temurin, Google Android SDK, Android TV API 30,
Gradle 8.13, AGP 8.13.2, and Google Media3 1.11.1. The app requires Android 11 or newer.
Build the Docker toolchain with `docker build -t queuepilot-player-build:0.1 tools`.

```sh
./tools/build.sh /absolute/path/to/queuepilot-player /absolute/path/to/build-cache
```

The build runs unit tests, lint, and `assembleDebug`. Preserve the cache's `android`
directory so test updates retain their signing key. The APK is
`android/app/build/outputs/apk/debug/app-debug.apk`.

```sh
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n dev.queuepilot.player.prototype/dev.queuepilot.player.MainActivity
```

ADB is only a deployment/testing tool. The app performs its own authentication and playback.
The disposable emulator fixture checks code authorization, cancellation, expiry, profile PINs,
discovery, encrypted storage, media playback, and remote navigation. It uses invented data,
refuses physical-device targets, and accepts fixture routing only on an emulator.
See [test environment](docs/testing.md) and [verification](docs/verification-0.5.3.md).
The repeatable `tools/tv-lab.sh` launcher keeps a named headless Docker lab available
without involving a household TV; start/status/test/stop commands are documented there.

## Foundation

This is independent Java/Android code, not a Plezy fork. Plezy is a reference for the Plex
protocol, pending its dependency provenance review. The standard Plex authorization flow
does not require adopting Plezy. Roku needs a separate BrightScript/SceneGraph client;
see [Roku evaluation](docs/roku.md).
