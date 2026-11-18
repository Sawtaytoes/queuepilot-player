# Title screens: extras, links, and watched marks

Version 0.6.2-preview, version code 17. Verified 2026-11-17.

The application ID is unchanged, so this updates the installed preview in place. Checks use
invented fixture data only.

## What changed

1. **Extras by kind.** A movie's or show's title screen reads
   `/library/metadata/<key>/extras` and shows one row per kind, in this order: Trailers, Behind
   the scenes, Deleted scenes, Featurettes, Interviews, Scenes, Shorts, and Other. An empty kind
   has no row. Plex's numbered `extraType` decides the kind; the `subtype` word is used only when
   the number is missing or unknown. Music video kinds go to Other. **Play trailer** sits beside
   Play and starts the first trailer (`TitleExtras.java`, `TitleExtrasRow.java`).
2. **Returning to the title.** An extra plays as a single title. It never sends a `/:/timeline`
   report, a scrobble, or a queue event, and it never changes a queue. When an extra ends, or
   Back leaves it, the same title screen returns with focus on that extra. When a movie or
   episode played outside a queue ends, its title screen returns with focus on the first extra
   (or Play when it has none), even when another button had focus before Play. Queue playback is
   unchanged: it still advances to the next item. An extra's card keeps its own identity for
   focus, so two extras with the same name are never confused.
3. **Go to show, season, and collection.** An episode's title screen has **Go to show** and
   **Go to season**. When the episode's listing lacks a parent key, the episode's own record is
   read for it. A movie or show in one collection has **Go to collection**, which opens it in
   the collection's order (`/library/collections/<key>/children`). A title in several
   collections opens a side panel to choose one. When the section's collection list does not
   name the collection, the section filtered to the collection tag is shown instead
   (`TitleNavigation.java`, `TitleLinks.java`). Back from a collection returns to Go to
   collection, even when the title's record arrives after the screen first takes focus.
4. **Mark watched.** A show's or season's header has **Mark watched**. It opens a side panel
   that says how many episodes change, for which profile, and how many are watched now. It
   offers Watched, Unwatched, and Cancel. Watched sends `/:/scrobble` and Unwatched sends
   `/:/unscrobble` with the show's or season's key, as the chosen profile. The header's counts
   are read again afterward.

`MainActivity.java` keeps thin call sites. The new logic is in new files, behind the
`ScreenHost` interface.

Main moved to 0.6.0-preview (the playback features in pull request 26), then to 0.6.1-preview
(server conversion in pull request 35), while this change was in review, so this change was
squashed onto that main as 0.6.2-preview. Its `MainActivity.java`
call sites are the same lines as before; the direct-play start keeps main's resume question and
marks the item as not an extra. An extra plays its file's own tracks: it never keeps the last
title's Plex track choices or subtitle files, and a track change during an extra is not saved.
`playback_smoke.py` plays the show's trailer right after an episode with a subtitle file and
checks that the trailer never loads that file.

## Gates

- **`tools/build.sh`**: 157 unit tests with 0 failures, including 5 in `TitleExtrasTest` (kinds
  by number and by word, row order, the first trailer, and spoken names) and 6 in
  `TitleLinksTest` (collection tags, collection lookup by name, path validation, and the
  watched panel's text). ktlint passed. Lint: 0 errors, 120 warnings, and 1 hint; none is in a
  file this change adds. The debug APK built. `tools/theme_check.py` and `tools/size_check.py`
  passed.
- **CI**: all required checks passed on the pull request.
- **Emulator**: the full `tools/tv-checks.sh` suite passed in the shared QueuePilot Test
  environment on 2026-11-17, about 6:00 to 6:45 PM CST ("PASS: every emulator check"). It ran
  this branch at 7773a25: the APK built from 18c1391, whose app code is the same, with the checks
  of 7773a25. Every check passed: `theme_check.py`, `smoke.py`, `native_smoke.py`,
  `panels_smoke.py`, `markers_smoke.py`, `labels_smoke.py`, `display_smoke.py` and
  `rail_smoke.py` (each at native size and 320 dpi), `details_smoke.py`,
  `queue_history_smoke.py`, `household_smoke.py`, `cold_start_smoke.py`, `restyle_smoke.py`,
  `library_smoke.py`, `title_smoke.py`, `playback_smoke.py`, `conversion_smoke.py`, and
  `layout_smoke.py` (both sizes). `title_smoke.py` covers:
  - the extras rows in order, with no empty kinds;
  - Play trailer, and Back to it;
  - an extra that plays to its end, landing on itself, with no timeline report, scrobble, or
    queue event;
  - two trailers with one name, where Back lands on the one that played;
  - the collection chooser, and a single collection opening directly;
  - a movie that ends after another button had focus, landing on its extras, and on Play when
    it has none;
  - Mark watched and Unwatched for a show as Ada, with Grace's count unchanged, and for a
    season;
  - Go to season and Go to show from an episode.

  `playback_smoke.py` also plays the show's trailer right after an episode with a subtitle file,
  and the trailer never loads that file.

Earlier runs found and fixed these problems before the passing run:

- The fixture server would not start under the lab's Python 3.10.
- A fixture route shadowed `films()`.
- `title_smoke.py` looked for button labels as content descriptions.
- `title_smoke.py` stopped pressing Back once the player's controls had hidden.

## Review

Codex (gpt-6.1-sol, high effort) reviewed the pull request eight times. Each finding was fixed:

1. `0bdef4d`:
   - `TitleNavigation` read the UI helper before the activity created it.
   - The watched panel kept old counts.
2. `ba76aa2`: an ended title restored a button used before Play instead of its extras.
3. `1b30c17`:
   - The new check compared the wrong label.
   - Back to Go to collection depended on when the record arrived.
4. `ef66da9`: two extras with the same name shared one focus memory.
5. `fe89b64`: approved. Its one test note, to repeat the no-report checks after the second
   trailer plays, was added.
6. `6690357`: the lint count and one word in this document.
7. `ee3fac7`, the first squash onto main: an extra kept the last title's track choices and
   subtitle files. Fixed by starting an extra with no track plan, with the `playback_smoke.py`
   check above.
8. `9df73c0`, on the current main: approved, with one note that this document described an
   older emulator run, now replaced by the run above. A review of the stacked library change
   found a test race in `title_smoke.py`, a tap on Go to collection while Play trailer joined
   the buttons; `7773a25` waits for Play trailer, and that review approved it.

Not verified: any physical Android TV, and a real media server's extras, collections, and
watched marks. The fixture server imitates Plex's documented responses.
