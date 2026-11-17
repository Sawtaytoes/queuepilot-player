# The TV app moves to Kotlin and Compose for TV screen by screen, and no source file grows past a size limit

**Status:** Accepted

**Date:** 2026-11-17

**Type:** Code structure and toolchain

**Supersedes:** None

**Superseded by:** None

## Decision

1. **No source file passes 400 lines.** This covers every Java, Kotlin, Python, shell,
   JavaScript, and C file in the repository, including tests. `tools/size_check.py` enforces
   the limit in CI. A file over the limit must be listed in `tools/size-exceptions.txt` with a
   ceiling and a reason. A listed file may shrink but never pass its ceiling, and its entry is
   removed in the change that brings it under 400 lines. An exception is rare and temporary.
2. **`MainActivity.java` is split first, one area at a time, by moving code only.** Each
   screen moves to its own class or small set of classes, and shared helpers move to small,
   focused files. A move changes no behavior. A move keeps the code in Java; converting a
   file to Kotlin is a separate commit, and only where the conversion is simple and safe. At
   the end, `MainActivity` only connects the screens and stays under the limit.
3. **New and redesigned screens are written in Kotlin with Jetpack Compose for TV.** An
   existing View screen stays in Java until it is redesigned or rewritten. Screens move one
   at a time, and the app works after every step, because a Compose screen and a View screen
   can live in the same activity.
4. **The toolchain:** Kotlin 2.4.10 with its Compose compiler plugin; the Compose bill of
   materials 2026.06.01 (Compose 1.11), which picks versions of the Compose libraries that
   were tested together; `androidx.tv:tv-material` 1.1.0; and `androidx.activity:activity-compose`
   1.13.0. ktlint 1.8.0, run through the ktlint Gradle plugin 14.2.0 with Android's Kotlin
   style and the Compose rules 0.6.8, checks Kotlin formatting. Android lint stays the main
   correctness check for Java and Kotlin. CI runs ktlint with the unit tests and Android lint.
5. **Compose screens take every color from `QueuePilotTvTheme.kt`**, which reads the tokens
   in `TvTheme.java`. `tools/theme_check.py` now checks Kotlin files too, so a color literal
   in a Compose screen fails CI like one in a Java file. The check reads text and cannot see
   types, so it refuses any `copy()` call that sets `red`, `green`, or `blue`. For that reason,
   no Kotlin class in this app names a property `red`, `green`, or `blue`.

## Context

`MainActivity.java` held 5,937 of the app's 10,161 lines of Java source on 2026-11-17: nearly
every screen, the player, and the network calls. On that day the owner wrote about the file:
"Yes, this has to be broken up fast. That's a MASSIVE waste of tokens." An agent reads a file
to change it, so every change to any screen paid to read the whole app.

The owner also chose Compose for TV for the TV-first redesign: "Yes. Let's do that if it
helps." The owner does not read Java or Kotlin: "I dunno anything about Java, so you're on
your own here. Do the best practices as much as we can." So this record explains each choice
in plain words.

Three other changes were in progress in the same file when this began: playback features,
the title screen and library, and playback statistics. A designer is also preparing Home
mockups for the redesign.

## Why

- **Why a size limit.** A short file costs an agent less time and money to read, and it is
  easier for a person to follow. One Java line is about ten tokens, so a 400-line file is
  about 4,000 tokens, and the old `MainActivity` was about 60,000 (242 KB). 400 lines fits one screen
  with its helpers. It is below detekt's well-known default of 600 lines for a large class,
  and it is the size that the owner's request suggested. A limit only helps if it is checked,
  so CI checks it.
- **Why ceilings instead of a hard stop for the large files.** Two feature changes were
  adding code to `MainActivity` and `PlexCatalog` while the split began. A ceiling at today's
  size would have failed their CI. Each ceiling leaves room for that work, and each split
  lowers the ceiling.
- **Why move first and convert later.** A move that changes nothing else can be checked
  mechanically: `git diff --color-moved` shows every moved line unchanged. A move combined
  with a rewrite cannot be checked that way, and a mistake would hide in it.
- **Why Kotlin and Compose for TV.** Google recommends Kotlin for new Android code and
  Compose as Android's modern way to build screens. For TV, Google's Compose for TV
  libraries replace the older Leanback library. Compose describes a screen in less code than
  Views, and its TV components handle remote-control focus, which this app now does by hand.
- **Why the bill of materials.** It pins all Compose libraries to one tested set, so one line
  updates them together.
- **Why Kotlin 2.4.10 and Compose 1.11.** The build uses Android Gradle plugin 8.13.2 and
  compiles against Android 16 (API 36). Kotlin's compatibility table gives 2.4.0 to 2.4.10 the
  plugin range 8.5.2 to 9.1.0, and 2.4.20 to 2.4.21 the range 8.5.2 to 9.3.1; both include
  8.13.2. This change was built and tested with 2.4.10; moving to 2.4.21 is a routine update
  for a later change. Compose 1.12 (bill of materials 2026.08.00 and later) requires plugin
  9.1 and API 37; the first build with it failed for that reason. Moving to plugin 9 is a
  separate change with its own risks, so this change takes the newest Compose that fits the
  current build.
- **Why every TV Material color role is set.** A role left out takes Material's own default
  color, which is not a QueuePilot color. Every role takes a `TvTheme` token. The error roles
  need Charcuterie's danger colors, so `TvTheme` gains them.
- **Why ktlint and not detekt as well.** ktlint keeps Kotlin formatting consistent without a
  person reviewing it, which matters because the owner cannot review the code. Android lint
  already finds correctness problems in Kotlin and Compose. detekt overlaps with both, so it
  is not added now. It can be added later if a need appears.
- **Provenance.** The workspace avoids Chinese-origin software. Kotlin and its Compose
  compiler come from JetBrains. Compose, `tv-material`, and `activity-compose` are Google's
  AndroidX libraries. ktlint is MIT-licensed and published under Pinterest's package name,
  now maintained by the ktlint organization. The ktlint Gradle plugin is MIT-licensed, by a
  developer in Boston. The Compose rules are Apache-licensed, started at Twitter and
  maintained by a developer in Spain. None is of Chinese origin.

## Evidence

- Line counts on `main` at `57b2227`: `MainActivity.java` 5,937; `test-environment/
  supervisor.py` 815; `tools/fixture_server.py` 417; `PlexCatalog.java` 411; every other
  source file 396 or fewer. The four larger files are the first entries in
  `tools/size-exceptions.txt`.
- The toolchain change adds no screen and changes no behavior. Its build passed 103 unit tests
  with 0 failures, Android lint with 0 errors (118 warnings; 0.5.9 had 117, and the new ones
  name newer dependency versions), and ktlint. The emulator suite results are on the pull
  request that adds this record.
- The first build with the Compose bill of materials 2026.09.00 failed: "Dependency
  'androidx.compose.animation:animation-core-android:1.12.1' requires libraries and
  applications that depend on it to compile against version 37 or later of the Android APIs"
  and "requires Android Gradle plugin 9.1.0 or higher". Compose 1.11.4's libraries require
  API 35 and plugin 8.6, and `activity-compose` 1.13.0 requires API 36 and plugin 8.9.1.
- Compose makes the debug APK, which is the APK the TV installs, larger: its code (DEX) grew
  from 15.5 MB to 32.9 MB, and the APK from 22.9 MB to about 46 MB. The Compose preview
  tooling is left out until a screen uses it; it added another 5.6 MB of code. Code that is
  never loaded does not slow the app. Turning on code shrinking (R8) for the installed build
  is a step for the first change that ships a Compose screen, as Google recommends for
  Compose.
