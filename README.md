# QueuePilot TV

A native Android TV preview with a queue-first home screen, Plex device-link sign-in,
profile selection, artwork browsing, and a Media3 player. It uses the existing
`dev.queuepilot.player.prototype` application ID and updates QueuePilot Lab in place.

## Connect your library

Choose **Sign in with Plex**, open **plex.tv/link** on a phone or computer, and enter the
four-character code displayed on the TV. The app then shows Plex Home profiles and discovers
their servers. A single available server connects automatically; multiple servers appear as
cards. Protected profiles use their existing Plex Home PIN. No API token, profile name, or
Plex server address needs to be entered on the television.

The home screen follows the approved queue-first presentation: a left navigation rail,
queue cards at the top, continuation below, and library browsing. D-pad focus outlines the
selected card. Back returns to the preceding screen. Plex libraries use artwork cards,
and configuration dialogs use bounded widths. **Explore the demo** opens a fictional
catalog for reviewing the same native layout without an account.

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
with people portraits and the existing required/optional audience filter. A queue starts
inside the app using an engine-produced plan. The actual Plex Home profile must match the
queue binding; a mismatched profile is offered an explicit switch before playback.
Native playback never sends `/api/play` or controls another app or the room.

Playback sends profile-scoped Plex progress and resume history. Queue starts also preserve
QueuePilot-owned history and authored section boundaries. Pending native progress is encrypted
locally and retried in order; invalid or expired grants are retained for review rather than
silently borrowing owner privileges. Queue snapshots and the people filter survive restart;
starting a new queue still requires the server. An active lineup is not restored after a crash.

The Cast overlay shows available portraits, actor names and roles from Plex metadata.
It is the title cast, not scene-by-scene actor recognition. Profiles also show their avatars.
**Rewind on resume** defaults to three seconds and can be disabled or changed in Settings.
Player focus highlights are rounded, including the seek buttons with interval labels.
Options use QueuePilot panels with visible selections and D-pad focus. Browsing/settings
use a side panel; playback audio, subtitle and speed menus stay above their launching
controls. Back closes a panel and returns focus; playback controls stay visible while
a menu is open. The subtitle picker does not add faithful ASS rendering.
See [native sessions](docs/native-sessions.md).

This preview does not negotiate transcoding, import real story
ordering manifests, or download media. Faithful ASS rendering, attached fonts, large
subtitles, HDR conversion, Dolby Vision, and passthrough still need explicit device evidence.
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
See [test environment](docs/testing.md) and [verification](docs/verification-0.4.0.md).
The repeatable `tools/tv-lab.sh` launcher keeps a named headless Docker lab available
without involving a household TV; start/status/test/stop commands are documented there.

## Foundation

This is independent Java/Android code, not a Plezy fork. Plezy is a reference for the Plex
protocol, pending its dependency provenance review. The standard Plex authorization flow
does not require adopting Plezy. Roku needs a separate BrightScript/SceneGraph client;
see [Roku evaluation](docs/roku.md).
