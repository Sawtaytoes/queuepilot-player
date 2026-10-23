# QueuePilot Test environment

One Android TV emulator, reachable from a browser, that people and automated tests share.
A person opens the page to start it, drives it with an on-screen remote or the keyboard,
and stays signed in between sessions. An automated test borrows the same emulator: it
takes a lock, the page shows that a test is running and blocks input, and the person's
session is restored exactly when the test finishes.

The image builds on the player toolchain image from `tools/Dockerfile` (Google Android
SDK, emulator, Android TV API 30 x86 system image).

```sh
docker build -t queuepilot-player-build:0.1 tools
docker build -t queuepilot-test test-environment
```

## How it works

| Part | Role |
| --- | --- |
| `supervisor.py` | Always running. Serves the page, starts and stops the emulator, carries input, stops after an idle period, coordinates automated tests. About 30 MB when the emulator is stopped. |
| Xvfb + the emulator window | The emulator draws its normal window, 1:1 at 1920 x 1080, into a virtual X display. |
| x11vnc | Reads that display. Loopback only, view-only: it accepts no input. |
| noVNC | Draws the display in the browser. The supervisor carries its WebSocket, so websockify is not used. |
| `queuepilot-test` | The command-line tool for tests and branch builds, run inside the container. |

The supervisor has no Docker socket and no host access. It controls only its own child
processes. Opening the page sends a start request; requesting the HTML alone, as a link
checker does, starts nothing. After `QPT_IDLE_MINUTES` without remote input (30 by default)
and with no automated test running, the emulator stops. The page warns five minutes before.

### Remote input

D-pad, OK, Home and the typed keys travel as X key events into the emulator window, which
forwards them to the guest keyboard. Back is the emulator's Ctrl+Backspace shortcut and Menu
is Ctrl+M. Play/Pause, Rewind and Fast-forward use `input keyevent` on a persistent ADB shell.
Text uses `input text`, which handles punctuation the window does not translate.
`sendevent` is refused to the shell user on this system image, and the emulator console's
`event send` does not reach it.

| Keyboard | Remote |
| --- | --- |
| Arrow keys | D-pad |
| Enter | OK |
| Esc or Backspace | Back |
| H or Home | Home |
| Space or K | Play/Pause |
| `,` and `.` | Rewind and Fast-forward |
| M | Menu |

The text box on the remote types into the field that has focus in Android.

### Persistence

The AVD lives in the state directory and is not wiped. Sign-in, profiles and settings
survive a stop, a start and a container restart. Each start is a cold boot
(`-no-snapshot`); booting the stored AVD takes about 30 seconds on KVM.

On every start the supervisor installs the newest `queuepilot-lab-<version>.apk` from
the artifacts directory when it differs from the installed APK. It installs over the
existing app (`install -r -d`), which keeps the app's data, and it never uninstalls.

## Automated tests

Tests use `queuepilot-test run`. It is the only supported path, and it never overlaps
another run:

1. It takes the lock file `/state/test.lock` (a second run waits, 30 minutes by default).
2. The supervisor starts the emulator if needed and saves an emulator snapshot of the
   shared session. The page shows the automated-test banner and refuses input.
3. It installs the test APK, starts `tools/fixture_server.py` from the given checkout,
   and runs the command.
4. Whatever the result, the supervisor loads the snapshot and deletes it, then reinstalls
   the release APK if it differs. If the test had to start the emulator, it stops it again.

A snapshot restores the whole guest, including the Android Keystore entries that
`pm clear` deletes. Copying the app's data directory cannot restore those keys, so a
data backup would sign the person out.

If the test process dies, the kernel releases the lock and the supervisor restores the
session on its own. If the container stops during a test, the next start boots from the
saved snapshot.

```sh
queuepilot-test run --source /source/.worktrees/my-branch --label "my-branch smoke" -- \
  python3 -u /source/.worktrees/my-branch/tools/smoke.py
```

`--source` defaults the APK to that checkout's `android/app/build/outputs/apk/debug/app-debug.apk`;
`--apk` overrides it. The command receives `QUEUEPILOT_TEST_SOURCE` and `FIXTURE_ROOT`.
The guest reaches the fixture server at the emulator's standard `10.0.2.2` alias.

## Branch builds for people

```sh
queuepilot-test install /source/.worktrees/my-branch/android/app/build/outputs/apk/debug/app-debug.apk --label "my-branch"
queuepilot-test install --release
```

`install` copies the APK into the state directory, installs it over the app and keeps it
on later starts. The page labels it as a test build. A build signed with a different key
is refused instead of uninstalling the signed-in app. `--release` returns to the newest
release.

## Other commands

```sh
queuepilot-test status
queuepilot-test start
queuepilot-test stop
queuepilot-test idle 5    # change the idle stop until the container restarts
```

## Container settings

| Mount | Purpose |
| --- | --- |
| `/state` (read-write) | AVD, Android home, logs, lock and pinned build. |
| `/fixtures` (read-write) | Generated fixtures; the checks write screenshots here. |
| `/artifacts` (read-only) | Release APKs named `queuepilot-lab-<version>.apk`. |
| `/source` (read-only) | The player checkout; worktrees appear beneath it. |

Give the container `/dev/kvm`, six CPUs and a 10 GiB memory limit, the same as the
emulator checks in [testing](../docs/testing.md). Publish only port 8080. ADB, the
emulator console and VNC listen on loopback inside the container.

| Variable | Default | Meaning |
| --- | --- | --- |
| `QPT_IDLE_MINUTES` | `30` | Minutes without input before the emulator stops. |
| `QPT_AUTHZ_URL` | empty | A forward-auth endpoint (`/api/authz/auth-request` style). When set, every request must carry a session that it accepts, so the published port is not an unauthenticated side door. |
| `QPT_PUBLIC_URL` | empty | The public origin sent to that endpoint as `X-Original-URL`. |
| `QPT_EMULATOR_MEMORY`, `QPT_EMULATOR_CORES` | `4096`, `4` | Guest RAM in MiB and vCPUs. |

## Limits

- Audio is not streamed to the browser yet; the emulator runs with `-no-audio`.
- The 4K experiment in [testing](../docs/testing.md) needs a different boot and is not
  available in the shared environment.
- Each automated test writes a snapshot of the guest's memory to the state directory and
  deletes it afterward.
