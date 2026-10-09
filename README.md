# QueuePilot Player

An experimental Android TV playback harness for testing a QueuePilot player without
controlling another app through ADB. The APK runs its own Media3 player. The finished
TV interface and native QueuePilot session integration are separate next steps.

## What the first APK can test

- Enroll explicit Plex profile tokens against a server origin, with credentials encrypted
  using Android Keystore. Switch among enrolled profiles without a cloud lookup.
- Browse Plex libraries, shows, seasons, and episodes. Listings cached by profile and
  server remain available when the origin request fails.
- Request 720-pixel artwork for the listing and compare 240-pixel and 1440-pixel requests
  side by side. The comparison SurfaceView requests a 3840 × 2160 buffer and reports
  the dimensions received. This is not proof that every UI element or final HDMI frame
  renders natively in 4K.
- Play the first media part directly with Media3, display decoded video dimensions, and
  open title cast metadata while video continues.
- Read the existing QueuePilot queue registry. This is read-only: native queue starts
  need the session contract in [Native sessions](docs/native-sessions.md).
- Exercise a fictional Episode 9 → OVA → Episode 10 sequence with persistent, isolated
  profile progress and unchanged provider identity.

The app is labeled **QueuePilot Lab**, uses the distinct application ID
`dev.queuepilot.player.prototype`, and requires Android 11 or newer. It is a diagnostic
screen, not the proposed final interface.

[Prototype validation and screenshots](docs/verification.md) record the tested APK and
its limits.

## Limits of this build

Plex enrollment uses a supplied token; Plex PIN login and automatic Plex Home enrollment
are not implemented. A name does not change a token's access rights. An owner token still
grants owner access. Offline profile selection cannot renew an expired token, create a new
login, or make an unreachable media server playable. Catalog cache storage is disposable;
artwork and media downloads are not cached yet.

Playback uses the first part and the platform/Media3 decoder path. It does not negotiate
server transcoding, import ordering manifests, update Plex watch history, or launch an
actual QueuePilot queue. Full libass styling, attached fonts, the large-subtitle regression,
HDR conversion, Dolby Vision, and audio passthrough need explicit device tests before
claiming support. Do not select this as the family player's default yet.

Only the first 40 results of a listing are shown to keep this harness bounded. Paging,
search, edition selection, collection composition, and production navigation are future UI
work. The demo sequence is a separate model test and does not rearrange real Plex items.

## Build and test

The toolchain image uses the official Eclipse Temurin base, Google Android SDK, Android
TV API 30 image, Gradle 8.13, Android Gradle Plugin 8.13.2, and Google Media3 1.11.1. Build
the image on a Linux host with Docker; the emulator also requires usable `/dev/kvm`.

```sh
docker build -t queuepilot-player-build:0.1 tools
```

Run `tools/build.sh` with absolute paths for the checkout and a persistent machine-local
cache. The cache preserves the debug signing key, so successive test APKs can update an
existing prototype installation. This is a development key, not a production release key.

```sh
./tools/build.sh /absolute/path/to/queuepilot-player /absolute/path/to/build-cache
```

The default command runs unit tests, Android lint, and `assembleDebug`. The APK is under
`android/app/build/outputs/apk/debug/app-debug.apk`.

```sh
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n dev.queuepilot.player.prototype/dev.queuepilot.player.MainActivity
```

ADB is a deployment and testing tool; playback has no runtime ADB dependency.

The emulator script runs headlessly and publishes no network port. Use a disposable
container with `/dev/kvm`, the source mounted at `/source`, and the cache mounted at
`/cache`. `tools/smoke.py` must only target that emulator: it clears the prototype's data
and uses invented credentials. The fixture server and generator provide catalog, artwork,
and real video data. See [Test environment](docs/testing.md).

## Client foundation and Roku

This is independent Java/Android code, not a Plezy fork. Plezy remains a useful reference
for Plex/Jellyfin adapters and player behavior, pending its full dependency provenance
review. Its presentation is not a required design constraint.

Roku needs a separate BrightScript/SceneGraph client; this APK cannot run there. The
shared assets will be the provider-neutral viewing sequence, QueuePilot session contract,
fixture data, and behavioral tests. See [Roku evaluation](docs/roku.md).

The preferred conversion location depends on the client: preserve direct playback and
on-device conversion for a capable Shield; negotiate GPU transcoding and HDR-to-SDR
conversion with Plex/Jellyfin for clients that require it. This harness proves neither
conversion quality nor the final family-device compatibility matrix.
