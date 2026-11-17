# QueuePilot TV

A native Android TV preview with a queue-first home screen, Plex device-link sign-in,
profile selection, artwork browsing, and a Media3 player. The launcher label is
**QueuePilot**. It keeps the existing `dev.queuepilot.player.prototype` application ID, so
it updates the app formerly labeled QueuePilot Lab in place.

## Connect your library

Choose **Sign in with Plex**, open **plex.tv/link** on a phone or computer, and enter the
four-character code displayed on the TV. The app then opens the signed-in account's own Plex
Home profile and discovers its servers, with no profile choice. A single available server
connects automatically; multiple servers appear as cards. The rail's avatar opens the profile
picker, where protected profiles use their existing Plex Home PIN. No API token, profile name, or
Plex server address needs to be entered on the television. The profile picker marks a
protected profile **🔒 PIN** and the account owner **Owner**; other profiles carry no label.

The home screen follows the approved queue-first presentation: a left navigation rail,
queues first, continuation below, and library browsing. The rail shows icons only while
focus is in the content and expands with labels when focus moves left onto it; Left lands on
the selected entry, and Back from a top-level screen returns to the rail. Home lists every queue in the
server's order. A card names its audience as text ("Ada, Grace") beside the faces, and says
whom the queue plays as ("Plays as Grace") as information only: nobody chooses a profile to
use a queue, because QueuePilot plays each queue as the profile it names. A queue locked on the
TV carries a lock. When QueuePilot cannot be reached, Home and Watching show the last saved
queues under "Saved queues · reconnect to start playback" and keep asking: after 2, 4, 8, 15
and 30 seconds, as soon as Android reports a network, and when the app resumes. The live
queues replace the saved ones as soon as QueuePilot answers. While a people filter is active, Home and
Watching say "Showing N of M queues" with a one-press **Clear**. The continue cards name
the queue or the media server they come from; an episode is named by its show, then
"S1 · E9 · Title". The rail's avatar switches profile. The chosen profile is used for library browsing,
search, title screens, a single title played outside a queue, and the server's own continue
row; a queue always plays as its own profile. D-pad focus outlines the selected
card. Back returns to the preceding screen. Libraries use square-cornered artwork cards,
and configuration dialogs use bounded widths. **Explore the demo** opens a fictional
catalog for reviewing the same native layout without an account.

Every queue card, the Home resume card, and the player's queue controls have a **Full queue**
action. It lists the whole ordered queue from the QueuePilot engine, not only tonight's lineup.
The server chooses the layout from the content: a list for a queue of mostly episodes, an
artwork grid for a queue of mostly movies. Each item shows whether it is watched, in progress,
or not watched, and the next item is marked. A line shows where tonight's lineup ends. The
list opens at the next item with the watched items folded into one row; the grid keeps watched
cards in place, dimmed, and flows the items of every entry into one wrapping grid: an entry
gets a heading only when it holds more than one item, and the next item is marked by its own
badge. Select an item for **Start here, then continue**, **Play only this
item**, or **Mark watched** / **Mark unwatched**. Starting at an item never marks the earlier
items watched. A Rules queue chooses its own order, so it lists its current lineup and offers
no Start here. An item whose watched state the media server keeps cannot be marked from the
TV, and its panel says so.

**History** on the rail shows every queue's watching by queue, then by sitting (a queue's plays
less than 75 minutes apart), then what was watched in it. It includes plays in other apps only
when a queue tracks them. A time recorded before exact times were kept reads "about". Select a
sitting to continue its queue, play one of its items again, or open the full queue. Choose
7, 30, 90, or 365 days. A locked queue's history is left out until the TV PIN is entered, and
the screen says how many queues it left out.

**Locked queues.** A queue marked "Locked on TV" in QueuePilot asks for the household TV PIN
before it opens, plays, takes a title, or marks an item. The PIN is entered on a number pad with
the D-pad or the remote's number keys. A wrong PIN says how many tries are left; after five,
every try waits 60 seconds. A correct PIN keeps locked queues open until the app leaves the
foreground. QueuePilot enforces the lock (`423` until `POST /api/native/unlock`), not only the
TV, and with no PIN set nothing is locked.

**Display size** in Settings scales the whole interface and applies at once. **Auto**, the
default, sets the size from the screen's diagonal and the viewing distance, both entered in
the same panel (55 in and 9 ft until changed): the size is the square root of the ratio
between a 55 in screen's viewing angle at 10 ft (22.6°) and this screen's, rounded to 5% and
kept within 50% to 125%. A 97 in screen at 6.5 ft is **Auto · 65%**. A manual size from 50% to
125% in 5% steps overrides it. Grids gain columns at smaller sizes. The Media3 control groups
are scaled about their anchored edge. The title screen keeps a long title to two lines and a
summary to four, with **More** for the full text.

**Library browsing.** A library opens as one grid that keeps loading as focus moves down, with
no page buttons: the next page arrives before focus reaches the last loaded row, and focus
stays where it is while it arrives. **Sort** opens a side panel: Title, Recently added, Release
date, Rating, or Last watched. The choice is kept for that library and profile. In title order a
**# A–Z** bar runs down the right edge; a letter starts the grid at its first title and loads
the titles before it as focus moves up. Back from a title returns to that title, however deep
in the library it was. **Search** (on the rail, and in each library's header for that library
alone) takes the on-screen keyboard, and voice when the device has a speech recognizer, and
groups the results as Movies, Shows, and Episodes; a result opens its title screen.

**Add to queue** on a title screen, and on a show's season list, lists every Picks queue, each
with its audience ("Ada, Grace"); a locked queue carries a lock and asks for the TV PIN. The
title goes to the top of the chosen queue, and a title already in it is reported, not repeated.
An episode is added as its show. A Rules queue chooses its own titles and cannot take one; the
panel says so instead of offering it. This needs
QueuePilot's `POST /api/native/queues/:setId/items`.

A title screen shows what Plex sends about the title: year, content rating, length or season
and episode counts, studio, genres, critic and audience scores, time left or episodes
watched, director and writers, and a cast row with portraits. A show's season list and a
season's episode list start with the same facts for that show or season.

**Look.** The colors, radii, and type are the QueuePilot web app's: Charcuterie's `daylight`
variant in the dark scheme, with Charcuterie's categorical Lime as the accent and a 3 dp lime
focus ring. Every color is a named token in `TvTheme.java`, the only file allowed to hold a
color literal (`tools/theme_check.py`, which reads Java and Kotlin). Compose screens take the same
tokens through `QueuePilotTvTheme.kt`. The typeface is Charcuterie's Outfit, bundled from
`google/fonts` under the SIL Open Font License (`tools/fonts/OFL.txt`, `tools/fonts/fetch.sh`).

Icons are [Lucide](https://lucide.dev) icons, vendored as Android vector drawables from
the pinned `lucide-static` release under its ISC license (`tools/lucide/LICENSE`). They mark
the rail, the player controls, Skip, the Settings rows, the queue card labels, and the title
actions, the full queue and its item panel, and the history. Each one sits beside a text label
or carries a spoken name.

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
inside the app using an engine-produced plan. The TV is authorized as a household device: its
Plex profile must belong to the server owner's Plex Home, and it never has to match the queue.
The session hands the TV the queue profile's grant for each server the queue's items live on,
and the TV streams the queue's items with it, so a queue plays, scrobbles and records history as
the profile the queue names, whoever is holding the remote.

While anything plays, the Android media session names it in one line ("Orbit Nine · S1 E9 ·
The signal") and carries its poster, as image bytes and, for a title on QueuePilot's own Plex
server, as QueuePilot's tokenless poster address. Other devices that read the TV's
now-playing status, such as a universal remote through its Cast integration, show that title and
poster instead of an app icon. No Plex address, which would carry a token, is published.
Native playback never sends `/api/play` or controls another app or the room.

Playback sends profile-scoped Plex progress and resume history. Queue starts also preserve
QueuePilot-owned history and authored section boundaries. Pending native progress is encrypted
locally and retried in order, off the main thread. Repeated progress reports of the same state
keep only the latest, and at most 200 events wait. A session that QueuePilot refuses, and an
event that fails 20 times with a server error or for longer than a session lasts (24 hours), is
retained for review as its latest event, for the last 20 such sessions, rather than silently
borrowing owner privileges. Queue snapshots and the people filter survive restart;
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
Library grids request pages of about 48 items, always whole rows. Demo Episode 9 → OVA → Episode 10 ordering and
progress remain separate from real Plex items. Use this for testing rather than the family
player's default viewing sessions.

## Build and test

The toolchain uses official Eclipse Temurin, Google Android SDK, Android TV API 30,
Gradle 8.13, AGP 8.13.2, Google Media3 1.11.1, Kotlin 2.4.10, and Jetpack Compose for TV
(Compose bill of materials 2026.06.01, `androidx.tv:tv-material` 1.1.0). The app requires
Android 11 or newer. The screens are Java Views today; new and redesigned screens are Kotlin
and Compose for TV ([decision](docs/decisions/2026-11-17-the-tv-app-moves-to-kotlin-and-compose-for-tv-screen-by-screen-and-no-source-file-grows-past-a-size-limit.md)).
Build the Docker toolchain with `docker build -t queuepilot-player-build:0.1 tools`.

```sh
./tools/build.sh /absolute/path/to/queuepilot-player /absolute/path/to/build-cache
```

The build runs unit tests, Android lint, ktlint (Kotlin formatting), and `assembleDebug`. CI
(`.forgejo/workflows/ci.yml`) runs the same four, without the native ASS library, plus
`tools/theme_check.py` and `tools/size_check.py` on every pull request that changes more than
documentation. `size_check.py` fails any source file over 400 lines unless
`tools/size-exceptions.txt` lists it with a ceiling it may not pass. Preserve the cache's `android`
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
See [test environment](docs/testing.md) and [verification](docs/verification-0.5.9.md).
The emulator checks run in the shared, browser-reachable [QueuePilot Test environment](test-environment/README.md)
without involving a household TV; `tools/tv-lab.sh test` runs them there.

## Foundation

This is independent Java/Android code, not a Plezy fork. Plezy is a reference for the Plex
protocol, pending its dependency provenance review. The standard Plex authorization flow
does not require adopting Plezy. Roku needs a separate BrightScript/SceneGraph client;
see [Roku evaluation](docs/roku.md).
