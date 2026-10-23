package dev.queuepilot.player;

import static org.junit.Assert.*;

import java.util.List;
import org.junit.Test;

public class LibraryBrowseTest {
  private static final List<LibraryBrowse.Letter> LETTERS =
      List.of(
          new LibraryBrowse.Letter("#", "#", 4),
          new LibraryBrowse.Letter("A", "A", 12),
          new LibraryBrowse.Letter("B", "B", 0),
          new LibraryBrowse.Letter("C", "C", 7));

  @Test
  public void sortsMapToTheServerParameters() {
    assertEquals("titleSort", LibraryBrowse.sort("title").param());
    assertEquals("addedAt:desc", LibraryBrowse.sort("added").param());
    assertEquals("originallyAvailableAt:desc", LibraryBrowse.sort("released").param());
    assertEquals("audienceRating:desc", LibraryBrowse.sort("rating").param());
    assertEquals("lastViewedAt:desc", LibraryBrowse.sort("watched").param());
    assertEquals("Recently added", LibraryBrowse.sort("added").label());
    // A stored id this version does not know falls back to Title.
    assertSame(LibraryBrowse.DEFAULT_SORT, LibraryBrowse.sort("unknown"));
    assertSame(LibraryBrowse.DEFAULT_SORT, LibraryBrowse.sort(""));
  }

  @Test
  public void onlyASectionListingHasASectionKey() {
    assertEquals("3", LibraryBrowse.sectionKey("/library/sections/3/all"));
    assertEquals("", LibraryBrowse.sectionKey("/library/sections"));
    assertEquals("", LibraryBrowse.sectionKey("/library/metadata/100/children"));
  }

  @Test
  public void pagesAreWholeRows() {
    assertEquals(50, LibraryBrowse.pageSize(5));
    assertEquals(48, LibraryBrowse.pageSize(4));
    assertEquals(48, LibraryBrowse.pageSize(6));
    assertEquals(49, LibraryBrowse.pageSize(7));
    for (int columns = 1; columns <= 12; columns++)
      assertEquals(0, LibraryBrowse.pageSize(columns) % columns);
    assertEquals(10, LibraryBrowse.rowStart(13, 5));
    assertEquals(0, LibraryBrowse.rowStart(4, 5));
  }

  @Test
  public void pagePathsCarryTheSortAndTheWindow() {
    assertEquals(
        "/library/sections/3/all?sort=addedAt:desc&X-Plex-Container-Start=50"
            + "&X-Plex-Container-Size=50",
        LibraryBrowse.pagePath("/library/sections/3/all", "addedAt:desc", 50, 50));
    assertEquals(
        "/library/metadata/100/children?X-Plex-Container-Start=0&X-Plex-Container-Size=48",
        LibraryBrowse.pagePath("/library/metadata/100/children", "", 0, 48));
  }

  @Test
  public void loadsAheadOfFocusInBothDirections() {
    // 50 loaded from 0, five columns: the last three rows ask for more.
    assertFalse(LibraryBrowse.needsLater(34, 50, 5, false));
    assertTrue(LibraryBrowse.needsLater(35, 50, 5, false));
    assertFalse(LibraryBrowse.needsLater(49, 50, 5, true));
    assertFalse(LibraryBrowse.needsEarlier(3, 0, 5));
    assertTrue(LibraryBrowse.needsEarlier(114, 100, 5));
    assertFalse(LibraryBrowse.needsEarlier(115, 100, 5));
    assertEquals(50, LibraryBrowse.earlierStart(100, 50));
    assertEquals(0, LibraryBrowse.earlierStart(30, 50));
    assertEquals(25, LibraryBrowse.restoreStart(37, 5));
    assertEquals(0, LibraryBrowse.restoreStart(7, 5));
  }

  @Test
  public void aLetterJumpCountsTheTitlesBeforeIt() {
    assertEquals(0, LibraryBrowse.offsetOf(LETTERS, "#"));
    assertEquals(4, LibraryBrowse.offsetOf(LETTERS, "A"));
    assertEquals(16, LibraryBrowse.offsetOf(LETTERS, "C"));
    assertEquals(-1, LibraryBrowse.offsetOf(LETTERS, "Z"));
    assertEquals("#", LibraryBrowse.letterAt(LETTERS, 3).key());
    assertEquals("A", LibraryBrowse.letterAt(LETTERS, 4).key());
    assertEquals("C", LibraryBrowse.letterAt(LETTERS, 16).key());
    assertNull(LibraryBrowse.letterAt(LETTERS, 23));
  }

  @Test
  public void theCountLineNamesTheSort() {
    assertEquals(
        "248 titles · Sorted by Title",
        LibraryBrowse.countLine(248, LibraryBrowse.DEFAULT_SORT));
    assertEquals(
        "1 title · Sorted by Rating", LibraryBrowse.countLine(1, LibraryBrowse.sort("rating")));
  }
}
