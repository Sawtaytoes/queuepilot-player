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

Run the emulator checks in QueuePilot Test, from the Docker host that runs it. Build the APK
in the checkout first:

```sh
./tools/tv-lab.sh test                      # the canonical checkout, mounted at /source
./tools/tv-lab.sh test my-branch            # the worktree /source/.worktrees/my-branch
```

`tools/tv-lab.sh test` calls `queuepilot-test run` with `tools/tv-checks.sh` from the same
checkout. The environment takes its lock, so a second run waits instead of overlapping. It
saves an emulator snapshot of the person's session, shows the test banner on the page, and
blocks the browser remote. It then installs the checkout's debug APK, starts
`tools/fixture_server.py` with `FIXTURE_ROOT`, and runs the checks: the emulator-only hierarchy
inspector build and install, then `smoke.py`, `native_smoke.py`, `panels_smoke.py`,
`markers_smoke.py`, `labels_smoke.py`, `display_smoke.py` (density 240 and 320),
`rail_smoke.py` (240 and 320), `details_smoke.py`, `queue_history_smoke.py`, and
`layout_smoke.py` (240 and 320). Whatever the result, the snapshot and the release
APK are restored afterward. If the environment was stopped, the run starts it and stops it again.
`QUEUEPILOT_TEST_CONTAINER` names the container when it is not the TrueNAS app default.

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
queue action, the grid at 100% and 125% Display size (fewer columns at 125%), the Rules
queue's missing Start here and its reason, and the profile-switch notice. Then History:
`GET /api/native/history?days=30` grouped by queue and sitting, the "about" time of a play
recorded before exact times, the sitting panel, and the 7-day period. `rail_smoke.py` counts
History among the rail entries.

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
