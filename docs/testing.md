# Test environment

The prototype is tested without waking or modifying a household TV. The build image and
emulator run as disposable Docker jobs on a Linux host with usable KVM. No ADB, emulator
console, or fixture port is published to the host network. Persistent cache holds the SDK
work, debug signing key, AVD, and generated synthetic fixtures; it is machine-local,
ignored, and never added to Git.

The Android TV API 30 image needs explicit TV hardware dimensions. `emulator.sh` writes
1920 × 1080 and landscape orientation before boot, starts the ADB server first, and resets
logical overrides. The caller must persist `/root/.android` for both build and emulator
jobs, so debug signatures and ADB authorization survive container replacement.

The emulator script wipes its own AVD on startup. Use this only for the disposable
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

- `--device /dev/kvm`, four CPUs, and a 6 GiB memory limit.
- The checkout at `/source` and persistent toolchain cache at `/cache`.
- The cache's `android` directory at `/root/.android`.
- Generated fixture files at `/fixtures`.
- The image command `/source/tools/emulator.sh` and no published ports.

After the emulator finishes booting and installs the APK, run the fixture server in that
same container, then the smoke test:

```sh
docker exec -d queuepilot-player-emulator python3 /source/tools/fixture_server.py
docker exec queuepilot-player-emulator python3 -u /source/tools/smoke.py
```

The guest reaches the container's fixture server through the emulator's standard
`10.0.2.2` host alias. The source contains only that standard alias and invented data.
The server outage is an origin failure, not an assertion that a physical Plex Home
profile can renew authentication without internet.

The checks cover profile enrollment, encrypted credential storage, profile-scoped
catalog requests and caches, artwork request dimensions, real direct playback, cast,
process restart, and demo viewing-order isolation. The browser design preview has its
own four-window-plus-4K checks in the private project notes.

Stop the disposable container when testing ends. Recreate it from the canonical checkout
for another run; do not keep a running process bound to a worktree that will be removed.
Hardware-specific HDR, passthrough, rendering buffers, large styled subtitles, real Plex
Home enrollment, and Roku require separate device evidence.
