# Test environment

The prototype is tested without waking or modifying a household TV. The build image runs as
a disposable Docker job, and the emulator checks run in the shared
[QueuePilot Test environment](../test-environment/README.md) on a Linux host with usable KVM. No ADB, emulator
console, or fixture port is published to the host network. Persistent cache holds the SDK
work, debug signing key, AVD, and generated synthetic fixtures; it is machine-local,
ignored, and never added to Git.

The rest of this section describes `tools/emulator.sh`, which is now used only for the 4K
experiment below; QueuePilot Test boots its own persistent AVD.

The Android TV API 30 image needs explicit TV hardware dimensions. `emulator.sh` writes
1920 × 1080 and landscape orientation before boot, starts the ADB server first, and resets
logical overrides. The build job must persist `/root/.android` to keep the APK signing key. Give each
emulator its own persistent `.android` and AVD cache, so concurrent jobs do not share
emulator registration files or locks.

The emulator script uses a disposable read-only AVD session and wipes its guest data on startup. Use this only for the disposable
`queuepilot-tv` AVD. A test clears the prototype's application data, never another package.

## Generate synthetic fixtures

Install Playwright Core 1.64.0 outside the checkout and use the host's ffmpeg. Set NODE_PATH
to that installation's node_modules and CHROMIUM_EXECUTABLE to the Chromium binary.

```sh
NODE_PATH=/absolute/path/to/node_modules \
CHROMIUM_EXECUTABLE=/absolute/path/to/chromium \
node tools/generate-fixtures.mjs /absolute/path/to/fixtures
```

This creates independently drawn fictional artwork at 240 × 360 and 1440 × 2160,
and a generated 1280 × 720 H.264/AAC test clip. A second fixture contains two named
audio tracks and two English subtitle tracks for the actual playback pickers, plus an MP4
variant whose duplicate names require numbered labels. Regenerate existing fixture folders
when updating to this version. The build container uses two CPUs, a 6 GiB memory limit and
at most two Gradle workers. It uses no library media or photographs.
The fixture server returns Plex-shaped XML, scoped invented tokens, byte-range video
responses, and a QueuePilot-shaped registry. Its request history deliberately omits tokens.

Run the emulator checks in QueuePilot Test, from the Docker host that runs it. Build the APKs
in the checkout first:

```sh
./tools/tv-lab.sh test                      # the canonical checkout, mounted at /source
./tools/tv-lab.sh test my-branch            # the worktree /source/.worktrees/my-branch
QUEUEPILOT_VARIANT=debug ./tools/tv-lab.sh test my-branch   # the debug APK instead
```

The checks test the release APK, the one TVs get, unless `QUEUEPILOT_VARIANT=debug`. A release
build is not debuggable, so `run-as` cannot read its files, and the lab's emulator image is a
production build: it has no `su`, and it refuses `adb root`. So `smoke.app_file()` returns
nothing for a release build, and the checks skip what they would read there: the saved Display
size, the queue-people filter, and the encrypted tokens and progress outbox. Each skip prints a
`SKIP:` line. What the screen shows is still checked. Run the debug APK too
(`QUEUEPILOT_VARIANT=debug`) to check the files; R8 is the only difference between the two.

`tools/tv-lab.sh test` calls `queuepilot-test run` with `tools/tv-checks.sh` from the same
checkout. The environment takes its lock, so a second run waits instead of overlapping, and waiting
runs start in the order they arrived. It
saves an emulator snapshot of the person's session, shows the test banner on the page, and
blocks the browser remote. It then installs the checkout's APK, starts
`tools/fixture_server.py` with `FIXTURE_ROOT`, and runs the checks: the emulator-only hierarchy
inspector build and install, then `smoke.py`, `native_smoke.py`, `panels_smoke.py`,
`markers_smoke.py`, `labels_smoke.py`, `display_smoke.py` (density 240 and 320),
`rail_smoke.py` (240 and 320), `details_smoke.py`, `queue_history_smoke.py`, `household_smoke.py`,
`cold_start_smoke.py`, `restyle_smoke.py`, `library_smoke.py`, `title_smoke.py`, `playback_smoke.py`, `conversion_smoke.py`, and `layout_smoke.py` (240 and 320). Whatever the result, the snapshot and the release
APK are restored afterward. If the environment was stopped, the run starts it and stops it again.
`QUEUEPILOT_TEST_CONTAINER` names the container when it is not the TrueNAS app default.

Before a release APK goes to a TV, `tools/upgrade_smoke.py` confirms that it installs over the
debug build the TVs have and keeps the sign-in and settings. Build that debug APK from the
commit the TVs run, in its own worktree, then run:

```sh
docker exec ix-queuepilot-test-queuepilot-test-1 queuepilot-test run \
  --source /source/.worktrees/my-branch \
  --apk /source/.worktrees/my-branch/android/app/build/outputs/apk/release/app-release.apk \
  --label 'my-branch upgrade' -- python3 -u /source/.worktrees/my-branch/tools/upgrade_smoke.py \
  /source/.worktrees/tv-build/android/app/build/outputs/apk/debug/app-debug.apk \
  /source/.worktrees/my-branch/android/app/build/outputs/apk/release/app-release.apk
```

The guest reaches the container's fixture server through the emulator's standard
`10.0.2.2` host alias. The source contains only that standard alias and invented data.
The server outage is an origin failure, not an assertion that a physical Plex Home
profile can renew authentication without internet.

The checks cover code-based Plex sign-in, cancellation and expiry, incorrect/protected
profile PIN handling, automatic server discovery, encrypted credentials, server-token
separation, cached profiles/catalogs, direct playback, cast, Back/D-pad navigation, and
demo story-order persistence. The test explicitly refuses physical devices. Its `fixture`
launch extra only changes networking on an Android emulator. The browser design preview has its
own four-window-plus-4K checks in the private project notes.

The layout check captures the emulator’s physical display, verifies PNG dimensions, and
checks remote focus and artwork browsing. Run it after the functional smoke test, never
concurrently. A 4K run cannot use the shared environment. Start a separate disposable
container from `queuepilot-player-build:0.1` with `--device /dev/kvm`, six CPUs, a 16 GiB
memory limit, no published ports, the checkout at `/source` (read-only), a toolchain cache at
`/cache`, that cache's own `android` directory at `/root/.android`, the fixtures at `/fixtures`,
`-e TV_WIDTH=3840 -e TV_HEIGHT=2160 -e TV_DENSITY=480`, and the command
`bash -c 'python3 /source/tools/fixture_server.py & exec bash /source/tools/emulator.sh'`.
Then run `tools/layout_smoke.py` in it, and remove the container afterward. This experiment currently fails the strict
resolution assertion: the API 30 TV window surface remains 1080p despite reporting the
4K physical mode. It is an unresolved experiment, not a passing release check. Merely
changing `wm size` is also insufficient. The default boot is 1080p.

`tools/layout_smoke.py --density-320` checks 1080p at density 320 as a separate TV scale.
Default 1080p uses density 240. The environment's restore resets the density afterward.

Hardware-specific HDR, passthrough, rendering buffers, large styled subtitles, real Plex
Home enrollment, and Roku require separate device evidence.

The standalone inspector reads the accessibility tree without waiting for playback animations
to become globally idle. It targets its own test package and never restarts the player.
Install it only in the guarded emulator; it is not part of the viewer APK. Native checks
cover audience persistence, profile-bound plans, cast faces/roles, D-pad seek focus, resume
rewind (Off by default, then a stored three-second choice), encrypted offline progress retry,
next-item playback and natural completion. The fixture episodes carry no Role entries of their
own; their cast comes from the show, and one portrait is an external HTTPS URL that only the
fixture server's transcoder can serve. A cast card is described as `Photo of <name>` only after
its portrait has been decoded and shown, which is what the checks wait for.

The resume rewind is measured from the playback events: the position reported at the pause,
then the one reported at the resume. When the app pauses, Media3 reports the position as of the
playback thread's last update, a moment before the player really stops; the resume reports
where it stopped. Playback does not jump, but the measured step back is the rewind minus that
pause delay, which is never negative. On the busy lab emulator on 2026-11-18 the delay reached
581 ms for 0.5.9 and 659 ms for 0.6.2, in eight pauses each, so the old limit of 650 ms either
way was too tight and could not catch a one-second rewind. `smoke.is_rewind` accepts a step
back for a rewind of R seconds when `R*1000 - 1000 < step back <= R*1000 + 150`: up to one second
of delay, and 150 ms past for rounding. A one-second rewind still fails the Off check unless the
delay passes 850 ms. `tools/smoke_rewind_test.py` tests the bound. The measurements are in
Docket `task_cqi9gl0fmwlt3mgg`.

`markers_smoke.py` runs after the native check, which connects the fixture QueuePilot server.
It enables intro, mid-credits and final-credits markers on one episode and checks both
library and queue playback: each skip button takes focus, Enter lands after the marker, and
final credits produce a stopped Plex timeline past 90% of the duration or a completed queue
item followed by the next item. The eight-second auto-hide is not asserted
on the emulator.
`labels_smoke.py` follows it: Home lists every queue grouped by the profile it plays as, a
people filter says how many queues it shows and clears in one press, queues sit above the
continue cards, and the player names the show. `display_smoke.py` then sets Display size to
100%, 70% and 125% through Settings and checks that the invented Films grid gains columns at
the smaller size, the choice survives a restart, a focused poster still draws its outline,
and every visible label stays on screen. It also opens a film with a long title and summary:
the title is two lines, the summary is four, and More opens the full text. Run it once at the
emulator's density and once with `--density-320`.
`rail_smoke.py` checks the navigation rail at both densities: it is collapsed to icons while
focus is in the content, every entry has an icon and a name, Left lands on the selected entry
and expands the rail, the profile avatar stays reachable, Right collapses it again, and Back
from the content returns to the rail. `details_smoke.py` opens the invented film Glass City
and checks its facts line, genres, scores, time left, credits, and cast photos; checks the
show facts above Orbit Nine's seasons; checks that every visible player control is a named
icon button; and seeks the time bar with the D-pad. The fixture serves a seven-frame BIF for
the episode's Part, so the preview must show the frame for the time it names, sit above the
bar inside the screen, and be fetched once, on the first seek and not before.

`queue_history_smoke.py` follows the details check. The fixture serves three invented queues
through `GET /api/native/queues/:setId/full`: an episode queue (list layout) with a special,
an authored section and a Plex-history entry, a movie queue (grid layout), and a Rules queue
(its lineup only). It checks that the list opens at the next item with three watched items
folded, the order of the lineup start, its end line and the following entry, the
watched/in-progress/next badges, the Plex-history entry's refusal to mark, a watched mark and
its reversal through `POST .../watched`, **Play only this item** and **Start here, then
continue** with the exact `start` body sent to `POST /api/native/sessions`, the player's Full
queue action, the grid at 100% and 125% Display size (fewer columns at 125%, and at least three
posters from different one-film entries sharing a row at both, with no per-entry heading and no
row-breaking next marker), the Rules queue's missing Start here and its reason, and a queue that
plays as Grace opening with no profile switch while Ada is chosen. Then History:
`GET /api/native/history?days=30` grouped by queue and sitting, the "about" time of a play
recorded before exact times, the count of locked queues left out, the sitting panel, and the
7-day period.

`household_smoke.py` follows. Each fixture queue plays as a named profile, and one is locked on
the TV with the invented PIN 1357. With Grace chosen, it plays Ada's queue with no profile
question and checks in the fixture's request log that the items streamed with Ada's grant from
the session, while a library title played outside a queue used Grace's token. It reads
`dumpsys media_session` for the one-line title ("Orbit Nine · S1 E9 · The signal") and checks
the poster fetch. It then opens the locked queue: the number pad, four wrong PINs counting down,
the fifth one's 60-second wait, and the right PIN playing the queue with Morgan's grant and the
unlock header. Finally it leaves the app with Home and returns, and the PIN is asked again. `rail_smoke.py` counts
History among the rail entries.

`cold_start_smoke.py` runs right after `household_smoke.py`, which leaves the fixture QueuePilot
connected; it cannot run after `layout_smoke.py`, which clears the app's data and opens the demo.
(For a day it ran last to stay under the emulator's memory limit, and in that place it could not
pass; the test environment's [Memory](../test-environment/README.md#memory) section has the
fix.) It resets the density, then force-stops the app and launches it: Home
must show the live queues and never the heading "Saved queues · reconnect to start playback".
Then it turns the emulator's Wi-Fi off before a cold start, which is the state at boot (the
shared emulator's Wi-Fi connected 23 s after the app opened): Home shows the saved queues, the
`QueuePilotQueues` log has the `java.net.ConnectException`, and when Wi-Fi returns the live
queues replace the saved ones with no key press. It then makes the fixture's
`/api/native/queues` answer 503 for a cold start and restores it: the timed retry (2 s, then 4,
8, 15 and 30 s) draws the live queues. Last, it leaves the app with Home while QueuePilot is
down, waits until the next timed retry is 8 s away, restores QueuePilot, and returns: the resume
retries at once. Wi-Fi is turned back on whatever the result, and the run's snapshot restore
resets the guest afterward.

`library_smoke.py` follows the queue and history check. The fixture's **Archive** library
(section 3) holds 305 invented films across the alphabet, with no X and four titles under "#".
The fixture honors `sort`, `X-Plex-Container-Start` and `-Size`, returns `totalSize`, and serves
`/library/sections/<key>/firstCharacter`, `/hubs/search` (with `sectionId`), and
`POST /api/native/queues/:setId/items`; every expected order is read from the fixture. The
check covers: title order, the count line, and a letter bar matching the first-character index
and fitting on screen; Down through more than one page with focus on the expected title in
every row, a second page requested, and no paging buttons; a jump to M that keeps the title on
screen while the page above arrives, and Up into it; Back from a title deep in the library
returning focus to it; Add to queue listing every Picks queue (the locked one with its lock),
with the Rules note, an add and a duplicate, and the request body; each of the four
other sorts against the fixture's order, without the letter bar, with Recently added kept across
a restart; search from the rail grouped as Movies and Episodes, an episode added as its show,
and Back to the same results; a search within the Archive sending `sectionId=3`; and the letter
bar at 1080p density 320. `rail_smoke.py` now counts seven entries, Search among them.

### Google TV launcher promotions

On the lab emulator (`com.google.android.tvlauncher` 4.1.9, `com.google.android.tvrecommendations`
4.1.8), the launcher sometimes comes to the front over the app after the app is stopped and
started. Observed so far:

- On 2026-11-17, a suite run failed in `display_smoke.py` when a launcher window covered the app
  with "Free live TV channels" and the buttons See what's on and Continue.
- Another agent's runs on the same day saw "Buy and rent movies on your TV" (Go to Shop, Dismiss)
  and the launcher's home screen with Dismiss cards and Home, Live, and Shop tabs.

What the checks do about it:

- `tools/tv-checks.sh` disables `com.google.android.tvrecommendations` for each run. That alone
  does not prevent the promotions: the suite run on 2026-11-17 at `a964b66` still showed both,
  each time in `com.google.android.tvlauncher/.dialog.ShowDialogsActivity`. Disabling only that
  activity does not work on the lab image: `pm disable` from the shell fails with "Shell cannot
  change component state", and `adb root` fails with "adbd cannot run as root in production
  builds" (run on 2026-11-17). The run's snapshot restore returns the emulator to its state from
  before the run, and the first line of each run's output shows what was disabled before it.
- `smoke.screen()` handles the launcher when it is in front: see the comment above
  `PROMOTION_BUTTONS` in `tools/smoke.py`. When it acts, it logs the focused window at that moment
  ("Launcher window: …") as a diagnostic sample. A sample names the focused window, not
  necessarily a component that is safe or useful to disable.

A probe on 2026-11-17 stopped and started the app 12 times with the recommendations package
enabled and 12 times with it disabled. Each time it read the focused window 8 seconds after the
launch; all 24 samples showed the app's own screen. That does not show that no promotion appeared
at other moments, and it does not show whether disabling the package prevents them. Its component
list for the launcher has these activities: `.MainActivity`, `.appsview.AppsViewActivity`,
`.appsview.AddAppLinkActivity`, `.appsview.RemoveAppLinkActivity`, `.inputs.InputsPanelActivity`,
`.notifications.NotificationsSidePanelActivity`, `.settings.HomeScreenSettingsActivity`, and
`.settings.OpenSourceActivity`, with receivers and a provider. The recommendations package includes
`.promos.config.ConfigurationProvider` and `.promos.server.LocaleChangeReceiver`. Which component
shows the promotions was not known then; the suite run above named `.dialog.ShowDialogsActivity`,
which `dumpsys package` had not listed.

`playback_smoke.py` follows the library check, while the fixture QueuePilot is still connected,
and before `layout_smoke.py` clears the app's data. The fixture's episode 102 can carry Plex
stream metadata for `tracks.mkv` (`POST /_plex_tracks/<mode>`): `selected` marks Commentary and
Signs and songs, `external` marks a subtitle file served at `/library/streams/9005`, and
`account` marks nothing, so the account's plex.tv `profile` (English audio, English subtitles
with foreign audio) decides. `PUT /library/parts/1102` records each save (`GET /_stream_saves`)
and changes the selection, as Plex does. In those modes the episode has three chapters with
chapter images. The check covers: Settings → Playback and its defaults, a stored Skip back of
15 seconds shown on the player's rewind control beside a forward skip of 10 for the 30-second
episode, and the caption settings link; the resume question with Resume focused, and Start from
the beginning starting at 0; Plex's selected audio and subtitles, a changed audio track and
subtitles Off saved for Ada with `allParts=1`, and both coming back from Plex on the next play;
the frame-rate request in the `QueuePilotFrameRate` log, and its clearing when Match frame rate
is turned off from the player's options; chapters opening below their top-bar control with every
chapter image shown, and a chapter seek; the sidecar subtitle loaded with Ada's token, and the
show's trailer played next never loading it; the account
languages choosing the full English subtitles for Japanese audio; confirming the track that is
already playing saving nothing; queues using the plan's `languages` when Plex selects nothing
(Ada's queue shows the full English subtitles, Grace's only the forced signs, which is Grace's own
setting); and a queue that plays as Grace saving its choice with Grace's grant while Ada is
chosen. Every check that presses Play on
the episode now answers the resume question.

`conversion_smoke.py` follows it. `POST /_unplayable` makes episode 102 a WMV file in an ASF
container (`unplayable.wmv`), which Media3 cannot open, with Plex's `container="asf"` facts. The
fixture answers Plex's universal transcoder: `decision` returns `generalDecisionCode="1001"`, or
2000 with a reason after `POST /_conversion_refused`; `start.m3u8` returns a master playlist that
names the session's media playlist, served from the HLS copy of `sample.mp4` in
`fixtures/conversion/`; `ping` and `stop` are recorded. The check covers: the setting's default of
Ask; the panel with the reason and both buttons; conversion from the resume point, as Ada, with the
same parameters for `decision` and `start.m3u8`, no `offset`, and no token in the address;
`stop` with the same session on leaving; Stop so I can fix the file, with no conversion; the Stop
and Convert on the server settings; Plex's refusal text; and a queue that plays as Grace converting
with Grace's grant, reporting to its own QueuePilot session (the fixture tags each event with its
queue) and sending no Plex timeline of its own. `tools/generate-fixtures.mjs` makes both fixtures.

## Performance probe

`tools/perf_probe.py` measures start time, frame timing, memory, and StrictMode findings in the
shared emulator, and compares builds in one run. Results and their limits are in
[performance](performance-2026-11.md).

```sh
queuepilot-test run --source /source/.worktrees/<branch> --label '<branch> perf' -- \
  python3 -u /source/.worktrees/<branch>/tools/perf_probe.py \
    --apk before=/source/.worktrees/<branch>/<saved main APK> \
    --apk after=/source/.worktrees/<branch>/android/app/build/outputs/apk/debug/app-debug.apk --strict
```

Each `--apk` is installed over the app in turn (both must be signed with the same debug key) and
measured the same way; the results go to `/fixtures/perf-<label>.json`, and a table compares them.
`--quick` takes two starts of each kind and skips the 50-row memory reading. `--strict-only` signs
in and runs only the StrictMode pass, keeping the raw log in `/fixtures/perf-strictmode.txt`.
StrictMode is on only for a debuggable build on the emulator launched with `--ez strict-mode true`
(`DebugChecks.java`): the preview APKs that TVs run are debuggable too. The probe signs in from a
clean app, so run it through `queuepilot-test run`, which restores the person's session.

### A lost tap in the Display size panel

On 2026-11-17, three suite runs on different branches failed the same way: a check tapped 125%
in the Display size panel, and the panel stayed open with the old size still checked. Two were in
`display_smoke.py` and one in `queue_history_smoke.py`. The logs show that the tap was ignored;
they do not show why. A first fix tapped again while the panel stayed open, three times at most.
In its own suite run, `queue_history_smoke.py` then had all three taps on 125% ignored, over
several seconds, while the screen listed 125% and the Cancel button below it. So in some state
of the app a tap on that row does nothing, and a retry does not help. Why is still not known.

People use the remote, so `tools/panel_input_probe.py` asked whether a remote choice can be lost
the same way. On the lab emulator on 2026-11-17, from Settings, it focused Display size with the
D-pad, pressed OK, moved Down five times from the checked 100% to 125%, and pressed OK:

- Focus was on the checked 100% at both readings, the first taken as soon as the screen could be
  read after OK and the second after a further 1.5-second pause, in 10 of 10 trials. Reading the
  screen takes time that is not measured, and two readings cannot rule out a brief focus change
  between them.
- With a person-like pace (OK, a one-second pause, then each key as its own `input` command
  with a 0.25-second pause after it, plus each command's unmeasured start-up time), 10 of 10
  choices landed.
- With the keys sent as soon as a second `input` command starts, 10 of 10 landed.
- With `--gaps`, the second `input` command, with the Downs and OK, was started 50, 100, 150,
  200, or 300 ms after the one with OK: 40 of 40 landed, 8 at each delay. These are delays
  between starting the two commands, not measured times between the keys reaching the app;
  each command's start-up time varies.
- Keys sent together with OK were lost or reached the Settings screen behind the panel:
  10 of 10 when all the keys were in one `input` command, and 7 of 8 when both commands started
  at once, where the Downs can even arrive before OK. That fits a new window taking key input a
  moment after it opens.
- Taps through the sequence the checks used then (tap the Display size row, wait until the
  panel's text can be read, then tap 125%) landed in 10 of 10 trials, so the lost tap did not
  happen in the probe.

In these emulator runs, no remote choice was lost unless its keys were sent together with OK.
That does not prove a fast remote press can never be lost, but no evidence points to a defect
for the remote. The ignored taps may be an app defect for touch input; a TV has no touch screen,
so the panel is unchanged. The checks that choose a size
(`display_smoke.py`, `queue_history_smoke.py`, and `upgrade_smoke.py`) now do it as a person
does, through `smoke.choose_display_size()`: Up or Down from the focused row until the size has
focus, then OK. No check retries a choice. It reads the focused row after each key, and fails if focus cannot reach the
size or the panel stays open after OK. The probe also tried to read, from the system's event
log, when the panel's window took key input. It could not determine that time, so it is not
known.

## Playback stats probe

`tools/stats_probe.py` checks the playback stats and is run on its own, not by `tv-checks.sh`,
because it slows the shared emulator's network for a minute:

```sh
queuepilot-test run --source /source/.worktrees/<branch> --label '<branch> stats' -- \
  python3 -u /source/.worktrees/<branch>/tools/stats_probe.py
```

It turns the hidden readout on and plays the fixture episode: about one sample a second is
logged under the `PlaybackStats` tag, the average video and audio rates add up to the file's
own size over its 30 seconds (within 10%), and the verdict is sustainable. Paused, it still
samples, at most once a second. It sends the app to the background and checks that sampling
stops. It has the fixture server send the video at 3 Mb/s (`POST /_video_kbps/3000`, under the
clip's 4.8 Mb/s) and expects "not sustainable", then restores full speed whatever the result. The
emulator console's `network speed` does not slow the guest's Wi-Fi, so the probe paces the
source instead. Last, it turns the
readout off: no samples and no readout. It prints the engine's CPU time per sample and the app's
CPU time over 15 seconds of playback with the readout on and off.

## Lucide icons

The app's icons are Lucide icons converted to Android vector drawables. `tools/lucide/icons.txt`
lists the icons in use. To add or refresh one, edit that list and run:

```sh
tools/lucide/fetch.sh            # downloads the pinned lucide-static release and checks its hash
python3 tools/lucide/generate.py # writes res/drawable/lucide_*.xml
```

`tools/lucide/LICENSE` carries the Lucide ISC license and the MIT license of the Feather icons
it derives from. Do not hand-edit a generated drawable.

## Shared test environment

`tools/tv-lab.sh` no longer creates a container of its own. It drives the QueuePilot Test
environment that people also use from a browser:

```sh
./tools/tv-lab.sh status
./tools/tv-lab.sh start
./tools/tv-lab.sh test [<checkout or worktree name>] [<label>]
./tools/tv-lab.sh stop
```

`start` and `stop` act on the shared emulator, so only stop it when nobody is using the page;
it stops by itself after the idle period. To let a person try a branch build by hand, use
`queuepilot-test install` inside the environment, described in its
[README](../test-environment/README.md#branch-builds-for-people).

`title_smoke.py` follows the library check. The fixture gives Glass City seven extras of six
kinds, listed out of order, and puts it in two collections; Paper Moons has one featurette and
plays the 30-second clip from its start; Orbit Nine has a trailer and one collection. The check
covers: the extras rows in their fixed order with no empty Scenes or Shorts row; Play trailer
beside Play, and Back to the same title with focus on it; an extra played to its end returning
to the title with focus on that extra, with no `/:/timeline`, `/:/scrobble`, or queue event for
any extra; Go to collection asking between two collections and opening the chosen one through
`/library/collections/<key>/children`; Paper Moons played to its end returning to its title with
focus on its featurette, after a stopped timeline; the show's single collection opening at once;
Mark watched's panel text, Cancel sending nothing, and Watched and Unwatched sending `/:/scrobble`
and `/:/unscrobble` for the show with Ada's token and updating the facts line; a season marked
on its own; and an episode's Go to season (looked up from the episode's record, because the
fixture's listing leaves the season key out) and Go to show. `markers_smoke.py` no longer presses
Back after the final credits: the title returns to its title screen by itself.
