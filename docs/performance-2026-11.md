# Performance audit, November 2026

Version 0.5.9-preview, measured 2026-11-17. The question: how does the app perform, can it be
tested locally, what are its performance problems, and how are they fixed?

## How to measure

`tools/perf_probe.py` runs in the shared QueuePilot Test emulator through `queuepilot-test run`
(see [testing](testing.md#performance-probe)). For each APK it is given, it signs in to the
invented fixtures and measures:

- **Start time.** `am start -W` after `am force-stop` (cold: a new process) and after Home
  (hot: the activity was only in the background). The first cold start of each set is dropped.
- **Frame timing.** `dumpsys gfxinfo <package> reset` before, and `dumpsys gfxinfo <package>
  framestats` after, each of four scenes: focus moving across Home and down its rows, 20 rows
  down the Archive library grid (305 invented films), focus moving on a film's title screen,
  and Back from the title screen to the grid.
- **Memory.** `dumpsys meminfo` on Home, after 20 and 50 grid rows, and on the title screen.
- **StrictMode** (`--strict`, or `--strict-only`): a launch with the `strict-mode` extra turns on
  `DebugChecks`, which logs disk and network work on the main thread and leaked closeables,
  registrations, and activities. The pass walks Home, the grid, a title, and eight seconds of
  playback, and groups the violations by the first app frame on their stacks.

**The emulator is not a TV.** It renders in software on the host CPU, and the host was busy
with other builds, so every frame time below is several times a Shield's and noisy. The
numbers compare two builds measured one after the other in the same run; they locate
main-thread work and memory growth. Absolute numbers come from a Shield (see [Measure on a
Shield](#measure-on-a-shield-read-only)).

## What the TVs show today

Read-only, on 2026-11-17, from an NVIDIA Shield where version 0.5.9-preview was already
running, cached in the background:

| Reading | Value |
| --- | --- |
| Display | 1920 x 1080, density 320 |
| `dalvik.vm.heapgrowthlimit` / `heapsize` | 192 MB / 512 MB |
| Total PSS | 132.7 MB |
| Native heap (decoded bitmaps live here) | 109.2 MB |
| Java heap | 11.3 MB |
| Views / Activities | 481 / 1 |
| Frames since the last reset | 0 (nothing drawn in the background) |

A background app holding 109 MB of native memory, almost all of it pictures, is first in line
for Android's low-memory killer and slows the next cold start.

## Findings

Before is `main` (`57b2227`); after is this pull request. One emulator run, 2026-11-17.

| # | Finding | Evidence | Impact | Fix | Status |
| --- | --- | --- | --- | --- | --- |
| 1 | Artwork was decoded at its full size, whatever the size of the card. | `MainActivity.artwork()` called `BitmapFactory.decodeByteArray` with no `inSampleSize`. A 720 x 1080 poster derivative is 3.1 MB decoded; the 1440 x 2160 title art is 12.4 MB, for a 360 x 540 view on a Shield. Emulator, 20 grid rows: native heap **1,872.8 MB before, 58.1 MB after**; 50 rows: **2,831.8 MB, 66.4 MB**. The fixture's transcoder answers any width over 240 with its 1440 x 2160 picture, so the emulator's "before" is four times what Plex's 720-pixel derivative costs: with Plex, about 3.1 MB per card before and 0.8 MB after at density 320, or roughly 470 MB and 120 MB for 150 cards. | Memory grows by the full picture size for every card shown, until the low-memory killer ends the app; decoding also takes CPU from the frames. | `ArtworkLoader.decode` subsamples to the smallest power of two that still covers the view (never upscaled). | Done |
| 2 | Scrolling a grid was slow while posters decoded. | 20 grid rows: median frame **908 ms before, 158 ms after**; p90 750 ms before, 150 ms after; 109 frames drawn before, 283 after over the same key presses; UI thread p95 14.8 ms before, 0.6 ms after. | Visible stalls while browsing a library. | Follows from 1 and 4. | Done |
| 3 | Every screen fetched and decoded its artwork again, Back included. | `render()` rebuilds the screen, and `loadArt` had no cache. Back from a title to the grid: 41 frames and UI thread p95 27.7 ms before; 14 frames and 12.6 ms after. | Back re-downloads a screen of posters from Plex's transcoder (server CPU, network) and redraws them as they arrive. | A 32 MB memory cache of decoded artwork, keyed by server, profile credential, picture, and size. It is emptied when Android reports the app in the background. | Done |
| 4 | Listings waited behind posters. | Artwork and listings shared `MainActivity.network`, four threads; a 48-poster page queued 48 downloads ahead of the next page's listing. | Paging and Home's queue lists could wait for a screenful of pictures. | Artwork has its own three background-priority threads; a request for a screen that is gone is dropped before it is fetched. | Done |
| 5 | Grids never recycle their cards. | `appendRows` adds every row to a `LinearLayout` in a `ScrollView`. Views: 1,198 after 20 rows and 2,158 after 50, in both builds. | Memory and layout time grow with every row browsed; a long library eventually holds thousands of views and their pictures. | A lazily composed grid (Compose `LazyVerticalGrid`, or a `RecyclerView`) that keeps only the visible rows. | Follow-up: the Compose move replaces these screens |
| 6 | Every navigation rebuilds the whole screen. | `render()` discards the view tree and builds the next one in code, including the rail. | Each screen change pays a full inflate, layout, and artwork pass. The cache in 3 removes most of the artwork part. | Screen state kept across navigation (Compose navigation with saved state). | Follow-up: Compose move |
| 7 | High-bitrate files keep only a few seconds of buffer. | `DefaultLoadControl` targets 32 MB and `setPrioritizeTimeOverSizeThresholds(false)` (chosen in `59e3f4e` for Java-heap headroom, see [ASS rendering](ass-rendering.md)). 32 MB is 3.4 s of an 80 Mb/s remux and 13 s of 20 Mb/s. The Shield's heap growth limit is 192 MB. | A brief network or server stall rebuffers a high-bitrate title. | Measure first: the playback stats readout (pull request #25) shows the seconds buffered and the verdict on a Shield. If remuxes run short, raise the target with `ActivityManager.getMemoryClass()` (for example a quarter of it, 48 MB at 192 MB) and re-check the heap during ASS playback. | Follow-up, needs Shield evidence |
| 8 | Connection reuse is not measured. | `PlexCatalog.get`, `PlexDataSource.close`, `AvatarImage`, and `NativeQueueApi` call `HttpURLConnection.disconnect()`. Android's documentation says a connection whose response body was read to the end may still be pooled after `disconnect()`, and `PlexCatalog.get` reads and closes its body first. `PlexDataSource.close` can disconnect in the middle of a body (a seek), which does end that connection, as it must. | Unknown. If connections were not reused, each poster and listing would pay a TCP connection, and a TLS handshake for an `https` server. | Count the connections the fixture server accepts over repeated listings and posters before changing anything. | Open question: measure first |
| 9 | Seek thumbnails decode on the main thread. | `showSeekPreview` calls `BitmapFactory.decodeByteArray` for each new BIF frame while the time bar moves. | A few milliseconds per frame change while seeking; not seen as jank on the emulator. | Decode the next frames on a background thread and keep a few decoded. | Follow-up: player work in progress |
| 10 | The app keeps its screen's pictures in the background. | Shield: 109 MB native heap in a cached background process. | The low-memory killer ends the app sooner, and the next start is cold. | 1 cuts each picture by 4 to 16 times. The rest needs the screen to release its pictures in `onStop` (or Compose, which drops off-screen content). | Partly done; rest with the Compose move |
| 11 | Focus changes allocate a new drawable. | `TvUi.focusable` builds a `GradientDrawable` on every focus change. | Two small objects per D-pad press; no measured jank (Home UI thread p95 1.0 ms in both builds). | Two prebuilt drawables per view. | No change: not measurable |
| 12 | Settings are read from disk on the main thread at startup. | StrictMode, below: four disk reads, all in `MainActivity.onCreate` (`TvUi`, `DisplaySize`, `ProfileStore`), none later. | One small file read each, before the first frame; part of the cold start. | None needed: the values are needed to lay out the first screen. If a Shield's cold start shows them, start loading the files in an `Application` subclass, before the activity. | No change: needed before the first frame |
| 13 | Chapter images bypass the artwork loader. | Found after the audit, in 0.6.0-preview: `ChapterMenu` fetches each chapter's 240-pixel image through `MainActivity.artwork()` on the shared `network` pool, not through `ArtworkLoader`. | Each time the menu opens, it fetches the images again, and they share threads with listings. A 240-pixel image is small, so memory is not the concern. | Load chapter images through `ArtworkLoader`, for its cache, its own threads, and decoding at the view's size. | Follow-up |

Unchanged by this pull request, within the emulator's noise: cold start (median **1,014 ms
before, 1,043 ms after**), Home (median frame 135 ms and 116 ms, UI thread p95 1.0 ms in both),
and the title screen (median frame 33 ms and 40 ms).

### Hot start recheck

The first run measured hot starts of 196 to 268 ms before and 269 to 488 ms after. Nothing in
this change runs on a hot start, which shows the screen that is already there, so a second run
measured the builds in the opposite order (`--quick`, after first). It repeated the main results
(20 grid rows: native heap 60.7 MB after, 1,872.9 MB before; median frame 194 ms after, 1,327 ms
before), and its hot starts were 373 to 380 ms for the build measured second, "before". Its
"after" hot starts were invalid: `am start` found the app still in front (LaunchState
`UNKNOWN`, no time), because Home had not yet taken effect. In both runs the build measured
second had the slower hot starts: the emulator's memory grows through a run (see the test
environment's [Memory](../test-environment/README.md#memory)). There is no evidence of a hot
start regression. The probe now waits until the app has left the screen before a hot start, and
leaves out starts that report no time.

### Main-thread work (StrictMode)

One pass on 2026-11-17 (`--strict-only`, this pull request's build) walked Home, the Archive
grid, a title, and eight seconds of playback with every check on. It logged four violations, all
during `MainActivity.onCreate`, and all disk reads of the app's own settings:

| Where | Count | What |
| --- | --- | --- |
| `TvUi.<init>` (from `onCreate`) | 2 | `getSharedPreferences` checks that a settings file exists |
| `DisplaySize.percent` (from `TvUi.<init>`) | 1 | waits for the display size settings to load |
| `ProfileStore.readSecret` (from `onCreate`) | 1 | opens the profile settings file |

StrictMode's `~duration` (85 to 94 ms) is the whole `onCreate` message, not the reads. Each read
is one small XML file, needed before the first frame can be laid out, so moving it off the main
thread would only move the wait. There was no network access on the main thread, no leaked
closeable, registration, or activity, and nothing while browsing the grid, opening a title, or
playing. Finding 12 records the reads. The raw log is `/fixtures/perf-strictmode.txt` in the
test environment.

### Release build (pull request #27)

The TVs ran debug builds through 0.5.9. A debuggable app is not compiled ahead of time from a
profile, and R8 does not run on it. Pull request #27 makes the release build the one the TVs run,
signed with the same key; the [decision record](decisions/2026-11-17-tvs-get-a-release-build-signed-with-the-key-of-their-debug-installs.md)
has the details. One run of this probe on 2026-11-17, medians, first cold start dropped:

| APK | Whole APK | Cold start | Hot start | Home, total PSS |
| --- | --- | --- | --- | --- |
| 0.5.9 debug, what the TVs run | 22.9 MB | 1,148 ms | 460 ms | 66.2 MB |
| 0.5.9 built as release | 9.2 MB | 1,125 ms | 480 ms | 54.0 MB |
| With Kotlin and Compose, debug | 40.7 MB | 1,268 ms | 411 ms | 95.1 MB |
| With Kotlin and Compose, release | 9.3 MB | 903 ms | 180 ms | 53.4 MB |

A single run varies by a few hundred milliseconds; an earlier run measured 1,506 ms and 1,090 ms
cold for the two 0.5.9 builds. In both runs the release build started faster than its debug
build. StrictMode runs only on a debuggable build, so the main-thread pass above was on a debug
build.

### What the measurements do not show

The emulator draws Home's frames in 116 to 135 ms with a UI thread under 1 ms: almost all of the
time is the software renderer, not the app. That is why Home and the title screen look the same
in both builds. On a Shield, the GPU renders, and the UI thread numbers are the ones that matter.

## Playback stats cost

`PlaybackStats` (pull request #25) costs nothing unless its hidden readout is on: without it the
player is built with Media3's own bandwidth meter and extractors, unchanged. With it on:

- **Counting**, on the loading thread: each demuxed sample is one short synchronized call that
  adds its size to a per-track, per-second counter, and each network read is one atomic add.
  The counters hold 15 tracks of 512 seconds, about 150 KB, kept while the player lives.
- **Sampling**, on the main thread, once a second and only while the readout can be seen. Seven
  `stats_probe` runs on the emulator (2026-11-17, about 20 samples each) measured a mean of
  94 to 232 µs of CPU time per sample, and a worst sample of 167 to 988 µs: about 0.02% of the
  main thread on average. The emulator was busy with other builds; a Shield should be faster.
- **The whole app, readout on and off.** One probe run measured 13.05 s of app CPU time over
  15 s of playback with the readout on, and 11.14 s with it off. The engine's samples account
  for about 4 ms of the difference. The rest is the readout's drawing over the video, other work
  on the busy emulator, or both: the emulator decodes and draws the video in software, and one
  pair of runs cannot tell them apart. The readout is for debugging, so this cost applies only
  while it is turned on.
- **Allocation**: a sample allocates nothing it keeps. `PlaybackStatsTest.aSampleAllocatesNothing`
  measures the thread's allocated bytes over 10,000 samples: 1,184 bytes in all, about 0.1 byte
  a sample, from the JVM's own first-call work; the test fails above 4,096.
- **When hidden**, behind another screen or with the app in the background, it samples nothing:
  the probe saw no sample for 6 seconds after Home. Only one stats object samples at a time: the
  next item's stats stop the previous item's before their first sample. A released player's stats
  also stop when Media3 reports the release, which it posts to the main thread, so one sample
  that was already due can run first. A queue moving on to its next item does not leave the old
  sampler running.

## Measure on a Shield (read-only)

Only when QueuePilot is already open on the TV, and only reads. Reset first, use the app for a
minute, then read:

```sh
adb -s <shield>:5555 shell pidof dev.queuepilot.player.prototype      # must print a pid
adb -s <shield>:5555 shell dumpsys gfxinfo dev.queuepilot.player.prototype reset
# ... a minute of browsing: Home, a library grid 20 rows down, a title, Back ...
adb -s <shield>:5555 shell dumpsys gfxinfo dev.queuepilot.player.prototype
adb -s <shield>:5555 shell dumpsys gfxinfo dev.queuepilot.player.prototype framestats
adb -s <shield>:5555 shell dumpsys meminfo dev.queuepilot.player.prototype
adb -s <shield>:5555 shell getprop dalvik.vm.heapgrowthlimit
```

`gfxinfo ... reset` clears the app's frame counters only; it changes nothing on screen. Compare
"Janky frames", the percentiles, and "Number Slow UI thread" with the emulator's ratios, and the
native heap after 20 grid rows with the emulator's 58 MB (expect more at density 320: about
0.8 MB per poster card). With the playback stats readout on (pull request #25), also read the
seconds buffered and the verdict during a high-bitrate title for finding 7.
