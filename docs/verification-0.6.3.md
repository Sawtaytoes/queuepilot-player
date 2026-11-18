# Library views: Recently Added, Folders, and All Movies and Shows

Version 0.6.3-preview, version code 18. Verified 2026-11-18.

The application ID is unchanged, so this updates the installed preview in place. Checks use
invented fixture data only.

## What changed

1. **Recently Added.** A movie or show library's own first screen starts with a Recently Added
   row. A movie library shows its 20 newest movies. A show library reads its 60 newest episodes
   and shows one card per show, newest first, with the count ("3 new episodes"). The row is not
   shown inside a show, a season, a folder, a collection, or search (`RecentlyAdded.java`,
   `RecentlyAddedRow.java`).
2. **View as Folders.** A library's options panel has **View as · Titles / Folders**, kept per
   library and profile. Folders reads `/library/sections/<key>/folder`, with nested folders, and
   Back walks up one folder. Folders keeps the server's order, so it has no sort and no letter
   bar (`LibraryOptions.java`, `LibraryOptionsPanel.java`).
3. **All Movies and Shows.** A merged grid of every included movie and show library. Each title
   has a chip with its library's name in a color kept per library. A library's options panel has
   **In All Movies and Shows · Included / Not included**. A library viewed as Folders is left out
   until it is included by hand. No library name is written in the app. All five sorts, the A–Z
   letter bar, the count line, infinite scroll, and Search work in the merged grid. The merged
   grid has no offline saved copy, and its title order can differ from the server's for articles
   in other languages (`AllLibraries.java`, `AllLibrariesSource.java`, `LibraryChip.java`).

4. **Focus after leaving through the rail.** A screen left through the rail no longer remembers
   the rail as its place. Before, reopening a library left that way put focus back on the rail,
   where it stayed: the Recently Added row could not take it, and the remote's next press went
   to the rail. The rail's own Back and selection behavior is unchanged.

`MainActivity.java` keeps thin call sites. Search for All Movies and Shows is in `SearchScreen.java`,
which main split out of `MainActivity.java` while this change was in review.

This change was moved onto the title-screen change (pull request 23, 0.6.2-preview) after main
moved, then rebased onto main after that change merged with the release build (pull request 27),
so it is 0.6.3-preview. It was last rebased onto main after pull requests 33 (a steadier Display
size check), 36 (the title screen and cast moved out of `MainActivity.java`), 37 (Settings moved
out), 25 (playback statistics), and 38 (the player moved out). The only conflicts were in
`MainActivity.java`'s fields, where both sides were kept; none of this change's code was in the
moved title, Settings, or player code. `MainActivity.java` is 1,689 lines.

## Gates

- **`tools/build.sh`**: 224 unit tests with 0 failures, including `LibraryPagingTest` (a grid
  keeps its read, only a session's first read refreshes, opening never waits on a request, a
  request for a screen that was left stops before it touches the read or keeps what it read, and
  a page that repeats the first item ends the listing), `AllLibrariesTest`, `RecentlyAddedTest`,
  and `LibraryOptionsTest`. ktlint passed. Lint: 0 errors, 120 warnings, and 1 hint, the same
  counts as main. The debug APK built. `tools/theme_check.py` and `tools/size_check.py` passed.
- **CI**: all required checks passed on the pull request.
- **Emulator**: the full `tools/tv-checks.sh` suite passed every check on `f6b929c`, the code of
  the final head, in the shared QueuePilot Test environment on 2026-11-18, about 5:18 to 6:03 AM
  CST. It had also passed on `3f477e2`, before the last two rebases, about 3:37 to 4:22 AM CST.
  These checks passed:
  `theme_check.py`, `smoke.py`, `native_smoke.py`, `panels_smoke.py`, `markers_smoke.py`,
  `labels_smoke.py`, `display_smoke.py` and `rail_smoke.py` (each at native size and 320 dpi),
  `details_smoke.py`, `queue_history_smoke.py`, `household_smoke.py`, `cold_start_smoke.py`,
  `restyle_smoke.py`, `library_smoke.py`, `title_smoke.py`, `library_views_smoke.py`,
  `playback_smoke.py`, `conversion_smoke.py`, and `layout_smoke.py` (both sizes).

  Earlier, a full run on `693f1eb` with the checks of `26ae9d6` passed every check
  except `details_smoke.py`, whose screen dump timed out in `adb`; a second run passed it. In that
  second run `native_smoke.py` measured a 2.2-second rewind on resume where 3 seconds was
  expected. That check passed in both full runs, and this change does not touch playback.

  Earlier runs on this change found and fixed these problems:
  - `display_smoke.py` expected a grid poster to have first focus; the Recently Added row has it.
  - Taps in `title_smoke.py` raced the Recently Added row and Play trailer as they arrived.
  - The library tools could be above the screen after a letter jump, and the D-pad walk back to
    them could land on Search beside Sort.
  - The Recently added check expected a film first; the fixture's archive titles are newer.
  - Leaving a library through the rail left focus on the rail when it was opened again (item 4).

  Runs on this change also hit the lab's known Display size panel lost tap (`display_smoke.py`
  at 320 dpi, then both `rail_smoke.py` runs, and once `restyle_smoke.py`), which this change
  does not touch.

## Review

Codex (gpt-6.1-sol, high effort) reviewed every head. Each finding was fixed before the next
review:

1. The first reviews of the feature found that the Recently Added row moved the screen away from
   its tools (`de78648`), that the merged grid read every library again for each page and kept
   reads a left screen had started, and that a server ignoring paging could repeat a library
   (`6a8140c`), and that the merged read could refresh between a grid's pages and its letters
   (`f6234b7`).
2. Opening All Movies and Shows could wait for an older request (`05bfced`); a request for a
   screen that was left could still use the read (`929eb43`); and a screen left during the last
   response still kept and sorted what it read (`9eeacc1`, with a unit test).
3. After the move onto main: approved. Reviews of the emulator checks then found a tap racing
   Play trailer's arrival in `title_smoke.py`, a D-pad walk that could pass without starting on a
   card, a Recently added check that expected a film first although archive titles are newer,
   and a fixture listing without added times; each was fixed.
4. `268d343`: leaving a library through the rail made the rail that library's remembered place,
   so reopening it left focus on the rail (item 4 above). Fixed in `693f1eb`; `26ae9d6` adds a
   remote-driven check that fails on the old code. Approved.
5. `3f477e2` and `26e3011`, after rebases onto main: no findings. Approved.

Not verified: any physical Android TV, and a real media server's folder listings and
library counts. The fixture server imitates Plex's documented responses.
