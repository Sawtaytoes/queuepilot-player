# A bounded event outbox, off the main thread

Version 0.5.9-preview, version code 14. Verified 2026-11-13.

The application ID is unchanged, so this updates the installed preview in place. Checks use
invented fixture data only.

## The defect

Every 10 seconds during queue playback, `reportPlayback` ran on the main thread. It decrypted
the whole pending-event array, added an event, encrypted the array again, and wrote it with
`commit()`. Only consecutive `playing` reports were merged, so a paused TV added one event on
every tick. Events that QueuePilot refused were marked `failed` and never removed. The outbox
grew without bound, and each tick did more main-thread work than the one before.

## The fix

1. `PendingEvents.java` holds the outbox rules, with no Android dependency:
   - A progress report (`playing` or `paused`) replaces a waiting report of the same state for
     the same item and session. Transitions such as `paused` then `playing` are kept. The
     replacement keeps the age and the server errors of the report it replaces, so merging never
     restarts the retry limits below.
   - At most 200 events wait. The oldest is dropped first.
   - A 4xx answer other than 408 and 429 refuses the session. Its events are kept for review as
     the session's latest event, for the last 20 sessions, and are never sent again.
   - A server error (5xx, 408, or 429) is retried 20 times. An event that still fails after
     24 hours, the server's session lifetime, stops being retried. An unreachable server is
     retried until then.
   - An outbox saved by 0.5.8 is compacted with the same rules on its first read.
2. One single-thread executor for the process adds events, in order; the existing progress
   executor sends them. The main thread never encrypts or writes the outbox. A send first waits
   for every event reported before it, so the server still receives `ended` before the queue
   continues. `PendingEvents.drain` lets one sender run at a time in the whole process, so a
   recreated activity's sender cannot send a later report before an earlier one that is still
   in flight (QueuePilot would refuse the earlier one as stale).
3. Progress reports are written with `apply()`. Terminal events, the pause when the app stops,
   and credentials keep `commit()`, because the process may end right after them. When the app
   stops or is destroyed, the main thread waits up to one second for those events to be written.

## Gates

- **`tools/build.sh`**: 103 unit tests with 0 failures, including 16 in `PendingEventsTest`
  (merging, the cap, refusals, retry limits across merged reports and a restart, an event
  replaced while it is being sent and whose failure must reach its own replacement, two senders
  at once, compaction of an old outbox, and profile isolation). Lint: 0 errors, 117 warnings, all in categories that 0.5.8 already had. The debug
  APK built.
- **CI** (`.forgejo/workflows/ci.yml`, new): `theme_check.py`, then unit tests, lint, and
  `assembleDebug` through `tools/ci-build.sh`. It does not build the native ASS library.
- **Emulator**: `theme_check.py`, `smoke.py`, and `native_smoke.py` passed in a disposable
  Android TV API 30 container started from the build image, as [testing](testing.md) describes
  for the 4K experiment. `native_smoke.py` now also pauses playback while the fixture
  QueuePilot is offline and reads the encrypted outbox before and after 25 seconds: it went
  from 944 to 945 characters, where 0.5.8 added about 700 characters per tick. No crash was
  logged. In the same container, five other `native_smoke.py` runs on the final build failed,
  each at a different step. Three started from screen state that an earlier failed run left
  (the Home control, the people filter, and the rewind setting). One found an empty screen
  after Back on its last step. One saw the first events after QueuePilot came back arrive
  after the check's 10-second wait; every event of that run, including `skip`, reached the
  fixture later, in order, and the outbox emptied.

The shared QueuePilot Test environment could not run these checks on 2026-11-13: its emulator
did not finish a cold boot in three attempts ("detected a hanging thread 'QEMU2 main loop'",
then "the emulator did not attach to ADB"). The disposable emulator used a hardware keyboard,
so `show_ime_with_hard_keyboard` was set before `native_smoke.py` to match the shared
environment's Back behavior on the connect screen.

Not verified: the full `tools/tv-checks.sh` suite, and any physical Android TV. No physical
device was used.
