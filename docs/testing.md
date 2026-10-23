# Test environment

The prototype is tested without waking or modifying a household TV. The build image and
emulator run as disposable Docker jobs on a Linux host with usable KVM. No ADB, emulator
console, or fixture port is published to the host network. Persistent cache holds the SDK
work, debug signing key, AVD, and generated synthetic fixtures; it is machine-local,
ignored, and never added to Git.

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

Start an emulator container with the following mounts and settings:

- `--device /dev/kvm`, six CPUs, and a 10 GiB memory limit. The software renderer exceeded a 6 GiB
  container limit during repeated playback tests; guest RAM alone is not the process budget.
- The checkout at `/source` and persistent toolchain cache at `/cache`.
- The emulator cache's own `android` directory at `/root/.android` (separate from the build signing cache).
- Generated fixture files at `/fixtures`.
- The image command `/source/tools/emulator.sh` and no published ports.

After the emulator finishes booting and installs the APK, run the fixture server in that
same container, then build the emulator-only hierarchy inspector and run the checks:

```sh
docker exec -d queuepilot-player-emulator python3 /source/tools/fixture_server.py
docker exec queuepilot-player-emulator bash /source/tools/ui-inspector/build.sh /fixtures/inspector-build
docker exec queuepilot-player-emulator adb -s emulator-5554 install -r /fixtures/inspector-build/inspector.apk
docker exec queuepilot-player-emulator python3 -u /source/tools/smoke.py
docker exec queuepilot-player-emulator python3 -u /source/tools/native_smoke.py
docker exec queuepilot-player-emulator python3 -u /source/tools/panels_smoke.py
docker exec queuepilot-player-emulator python3 -u /source/tools/markers_smoke.py
docker exec queuepilot-player-emulator python3 -u /source/tools/labels_smoke.py
docker exec queuepilot-player-emulator python3 -u /source/tools/display_smoke.py
docker exec queuepilot-player-emulator python3 -u /source/tools/display_smoke.py --density-320
docker exec queuepilot-player-emulator python3 -u /source/tools/rail_smoke.py
docker exec queuepilot-player-emulator python3 -u /source/tools/rail_smoke.py --density-320
docker exec queuepilot-player-emulator python3 -u /source/tools/details_smoke.py
docker exec queuepilot-player-emulator python3 -u /source/tools/layout_smoke.py
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
concurrently. For a separate 4K run, restart the disposable container with
`-e TV_WIDTH=3840 -e TV_HEIGHT=2160 -e TV_DENSITY=480` and a 16 GiB memory limit,
then run `tools/layout_smoke.py` again. This experiment currently fails the strict
resolution assertion: the API 30 TV window surface remains 1080p despite reporting the
4K physical mode. It is an unresolved experiment, not a passing release check. Merely
changing `wm size` is also insufficient. The default boot is 1080p.

`tools/layout_smoke.py --density-320` checks 1080p at density 320 as a separate TV scale;
it changes only the disposable emulator. Default 1080p uses density 240.

Stop the disposable container when testing ends. Recreate it from the canonical checkout
for another run; do not keep a running process bound to a worktree that will be removed.
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

## Lucide icons

The app's icons are Lucide icons converted to Android vector drawables. `tools/lucide/icons.txt`
lists the icons in use. To add or refresh one, edit that list and run:

```sh
tools/lucide/fetch.sh            # downloads the pinned lucide-static release and checks its hash
python3 tools/lucide/generate.py # writes res/drawable/lucide_*.xml
```

`tools/lucide/LICENSE` carries the Lucide ISC license and the MIT license of the Feather icons
it derives from. Do not hand-edit a generated drawable.

## Repeatable Docker lab

`tools/tv-lab.sh` manages the named `queuepilot-tv-lab` container. Build the APK and generate
fixtures above, then start it with absolute paths:

```sh
./tools/tv-lab.sh start /absolute/path/to/queuepilot-player /absolute/path/to/emulator-cache /absolute/path/to/fixtures
./tools/tv-lab.sh status
./tools/tv-lab.sh test
./tools/tv-lab.sh stop
```

The launcher runs the fixture service beside the headless Google Android TV emulator,
mounts source read-only, gives the emulator its own Android home, and publishes no ports.
It does not use the build signing cache or a household ADB connection. Wait for status to
report `boot_completed=1` before testing. Start a fresh lab for each full run: one pass of the
whole suite takes the emulator to about 8 GiB of the container's 10 GiB, and a second pass
in the same session was killed by the memory limit on 2026-10-23. The test command runs the guarded sign-in,
native playback and two-scale layout checks. Stop leaves the container configured for the
next session; each emulator start wipes only its disposable guest. `remove` deletes only
a container carrying the expected lab ownership label. Remove it before switching to
another checkout, and before removing a source worktree.
