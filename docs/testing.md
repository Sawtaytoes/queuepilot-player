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
and a generated 1280 × 720 H.264/AAC test clip. It uses no library media or photographs.
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
cover audience persistence, profile-bound plans, cast faces/roles, D-pad seek focus, three-second
resume rewind, encrypted offline progress retry, next-item playback and natural completion.
