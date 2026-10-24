# Live queues after a cold start

Version 0.5.8-preview, version code 13. Verified 2026-10-24.

The application ID is unchanged, so this updates the installed preview in place. Screenshots and
checks use invented fixture data only.

## The defect

From 0.5.4 to 0.5.7, a cold start could leave Home on the saved queues: the heading "Saved
queues · reconnect to start playback", placeholder faces, and no retry. A moment later the
Watching screen loaded the same list live, with photos and "Plays as".

## Root cause

Home asked QueuePilot for its queues once, in `onCreate`. When the request failed,
`fetchQueues` read the saved copy and marked it `cached`, and nothing asked again until the app
was restarted.

The request failed because the app opened before Android had a default network. The shared
emulator's own log of its last start shows it:

| Time | Event |
| --- | --- |
| 06:29:24 | `BOOT_COMPLETED` |
| 06:29:30 | The app is started (`Start proc … for pre-top-activity`), and Home sends its queue request |
| 06:29:52 | `ConnectivityService`: Wi-Fi `CONNECTED`, "Switching to new default network" |

For 23 seconds there was no network. Opening Watching after that worked. A TV that wakes from
sleep has the same gap while its Wi-Fi reconnects.

The failure was swallowed. 0.5.8 logs it under the tag `QueuePilotQueues`. With the emulator's
Wi-Fi off before a cold start, the log shows (here against the fixture origin; the household
origin gave the same exception for its own address):

```text
W QueuePilotQueues: Queue list failed, showing the saved copy: /api/native/queues?people=
W QueuePilotQueues: java.net.ConnectException: Failed to connect to /10.0.2.2:9010
	at com.android.okhttp.internal.io.RealConnection.connectSocket(RealConnection.java:147)
I QueuePilotQueues: Queues not live; retry in 2000 ms
I QueuePilotQueues: Queues not live; retry in 4000 ms
I QueuePilotQueues: Live queue retry now: network available
I QueuePilotQueues: Live queues after a retry
```

Without the fix, Home still showed "Saved queues" 20 seconds after the network returned.

Force-stopping and launching the app while the network is up does not fail: nine such cold
starts against a live QueuePilot loaded the live queues every time.

## The fix

1. `loadLiveQueueLists` wraps the queue list for Home and Watching. When the answer is the saved
   copy, or nothing, the screen keeps asking:
   - after 2, 4, 8, 15, and 30 seconds (`QueueList.liveRetryDelayMs`, 59 s in all);
   - at once when Android reports a new default network
     (`ConnectivityManager.registerDefaultNetworkCallback`, which needs the normal
     `ACCESS_NETWORK_STATE` permission); this is the boot case;
   - at once when the app resumes.

   A network change or a resume starts the backoff again. Leaving the screen cancels the retry.
2. The live answer replaces the saved cards. Focus follows only when it was on the replaced
   cards, on the card Home focused, or nowhere. A viewer who moved to the library or the rail
   keeps their place. The same saved copy is not drawn again on each failed retry.
3. Each failure, and each retry, is logged under `QueuePilotQueues`.

## Gates

- **Unit tests**: `QueueListTest` checks the backoff: 2, 4, 8, 15, and 30 seconds, then only a
  network or a resume, and a total longer than the 23 s boot gap.
- **`tools/cold_start_smoke.py`** (new, after `household_smoke.py`):
  1. A plain cold start shows the live queues and never the saved heading.
  2. With Wi-Fi off before the cold start, Home shows the saved queues and the log holds the
     `ConnectException`. When Wi-Fi returns, the live queues replace them with no key press.
  3. With the fixture's `/api/native/queues` answering 503 at the cold start, the timed retry
     draws the live queues after the fixture recovers.
  4. Leaving the app with Home while QueuePilot is down, and returning after it recovers, retries
     at once on resume.

Results on this branch, based on 0.5.7 (`d770be1`):

- `tools/build.sh`: 82 unit tests with 0 failures, lint 0 errors (119 warnings), and the debug
  APK built.
- `tools/tv-lab.sh test`: `cold_start_smoke.py` passed in two full runs, placed after
  `household_smoke.py`. Both runs then lost the emulator in `library_smoke.py`. The kernel
  killed `qemu-system-x86` at the container's 10 GiB memory limit (anon-rss 10.2 GiB). The
  emulator's memory grows about 0.3 GiB a minute through a run, and the new check adds about
  two minutes. With the check moved to the end, all 17 existing checks passed, and the emulator
  was killed at the same limit as the new check started. **The full suite with the new check has
  not passed in one run.** The memory limit is an open issue in the test environment.

Not verified: any physical Android TV. No physical device was used.
