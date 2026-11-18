# TVs get a release build signed with the key of their debug installs

**Status:** Accepted

**Date:** 2026-11-17

**Type:** Build and release

**Supersedes:** None

**Superseded by:** None

## Decision

1. **TVs get the release APK**, `android/app/build/outputs/apk/release/app-release.apk`. R8
   removes unused code and resources and shortens names. The build is not debuggable.
2. **The release APK is signed with the key that signed every debug build the TVs had:**
   the build cache's `android/debug.keystore`, alias `androiddebugkey`, certificate SHA-256
   `E5:72:63:40:52:29:22:D9:4A:CA:22:EA:17:D3:6C:02:ED:67:1A:E7:78:D5:B4:0F:18:1A:46:8E:C3:4D:C0:77`.
   So `adb install -r` replaces the debug app in place, and Android keeps its data: the
   encrypted Plex tokens, the chosen profile, and the settings.
3. **The debug APK stays for development.** `tools/build.sh` and CI build both.
4. **The emulator checks test the release APK** unless `QUEUEPILOT_VARIANT=debug`. Before a
   TV gets a release APK, the full emulator suite and `tools/upgrade_smoke.py` must pass against
   it. `upgrade_smoke.py` installs the release APK over the debug build the TVs run and checks
   that the sign-in and settings remain.
5. **The build cache's `android` folder must be kept and backed up.** If the key is lost, no
   new build can update the TVs. Each TV would then need to uninstall the app, which deletes
   its sign-in and settings.

## Context

Through 0.5.9, the TVs ran `app-debug.apk`. The build had no release type and no release
signing. A debug build is debuggable. Android does not compile a debuggable app ahead of time
from a profile, and R8 does not run on it. The toolchain change (Kotlin and Compose for TV)
doubled the debug APK, from 23.3 MB to about 46 MB. The two standard Android fixes, R8 and a
Baseline Profile, help only a release build. The owner asked for mainstream Android practice.
The coordinator decided on 2026-11-17 that the household switches to the release build,
signed with the same key, and that the coordinator installs it on the TVs.

## Why

- **A release build is what Android expects an app to ship.** Google's guidance is to ship
  and measure release builds. A debuggable app runs with extra checks and is not compiled
  ahead of time, so it starts and runs slower than the same code as a release.
- **R8 shrinks the code the TV loads.** Most of Compose, Media3, and AndroidX is unused by
  any one app. R8 removes what is unused and shortens names, and resource shrinking removes
  unused resources. Each library ships its own R8 rules. The app needs no rules of its own:
  the only Java the native ASS library reaches is `AssNative`'s native methods, which
  Android's default rules keep, and Android's own `Bitmap`.
- **The same key, not a new release key.** A store app would get its own release key. Here,
  a new key would make every TV uninstall the app and sign in again, because Android
  installs an update only when the key matches. The app is installed only on the household's
  TVs, and the key file stays in the private build cache. So the key that signed the debug
  builds also signs the release build.
- **The checks test what the TVs run.** R8 can remove code an app reaches by reflection, and
  the checks would not see that on a debug build. So the emulator suite runs on the release
  APK by default. A release build is not debuggable, so `run-as` cannot read the app's files,
  and the lab's emulator image refuses `adb root`. The checks therefore skip their file reads on
  a release build and check what the screen shows; a debug run checks the files.

## Evidence

- The build cache's `debug.keystore` has the certificate SHA-256 above. The 0.5.1 debug APK
  in the canonical checkout, built through `tools/build.sh`, has the same signer digest. This
  check did not read a TV. If a TV's app has a different key, Android refuses the install and
  leaves the installed app and its data as they were.
- APK sizes, in bytes. R8 and resource shrinking cut the APK by more than half and its code
  by about seven eighths:

  | APK | Whole APK | Code (DEX) |
  | --- | --- | --- |
  | 0.5.9 debug, what the TVs run | 22,933,053 | 15,452,824 |
  | 0.5.9 built as release | 9,176,532 | 1,935,008 |
  | This change, debug (Kotlin and Compose added) | 40,748,370 | 33,083,012 |
  | This change, release | 9,342,648 | 1,967,596 |

- Start time and memory, from `tools/perf_probe.py` (the performance agent's probe; see
  [performance](../performance-2026-11.md#release-build-pull-request-27)) in the lab emulator on 2026-11-17. Medians;
  the first cold start of each set is dropped. The emulator renders in software on a busy host,
  so these numbers compare builds measured one after the other in the same run; they are not
  a Shield's.

  | APK | Cold start | Hot start | Home, total PSS |
  | --- | --- | --- | --- |
  | 0.5.9 debug, what the TVs run | 1,148 ms | 460 ms | 66.2 MB |
  | 0.5.9 built as release | 1,125 ms | 480 ms | 54.0 MB |
  | This change, debug | 1,268 ms | 411 ms | 95.1 MB |
  | This change, release | 903 ms | 180 ms | 53.4 MB |

  An earlier run the same day measured 1,506 ms cold for the 0.5.9 debug APK and 1,090 ms for
  0.5.9 built as release, so a single run varies by a few hundred milliseconds. In both runs, a
  release build started faster than its debug build. The Baseline Profile that the coordinator
  asked for comes in a separate change, with its own before and after.
- Upgrade, on 2026-11-17, in the lab emulator: `tools/upgrade_smoke.py` installed the 0.5.9
  debug APK, signed in, and set Display size to 70%. It then installed the release APK of this
  change (`f190499`, on `main` with #35, 0.6.1-preview) over it with `adb install -r`. The
  install time stayed the same, so Android updated the app in place. The release build opened
  signed in, loaded a library title that exists only on the fixture server (so it decrypted its
  token), and kept 70%: "PASS: the release build installs over the debug build in place".
- Emulator suite on that release APK, the same run: "PASS: every emulator check", with no crash
  logged. That includes the playback (#26) and conversion (#35) checks, so R8 kept what they
  need. On a release build, the checks skip their reads of the app's files and check the screen.
