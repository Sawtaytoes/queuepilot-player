package dev.queuepilot.player;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Paging, sorting and jump-by-letter arithmetic for a library grid. The grid holds one
 * contiguous window of the section, always starting on a row boundary, so a page never leaves a
 * row half filled and a jump can start the window anywhere.
 */
final class LibraryBrowse {
  private LibraryBrowse() {}

  /** A section sort: the stored id, the label, and the media server's sort parameter. */
  record Sort(String id, String label, String param) {}

  static final List<Sort> SORTS =
      List.of(
          new Sort("title", "Title", "titleSort"),
          new Sort("added", "Recently added", "addedAt:desc"),
          new Sort("released", "Release date", "originallyAvailableAt:desc"),
          new Sort("rating", "Rating", "audienceRating:desc"),
          new Sort("watched", "Last watched", "lastViewedAt:desc"));

  static final Sort DEFAULT_SORT = SORTS.get(0);

  /** A stored sort id, or Title for one this version does not know. */
  static Sort sort(String id) {
    for (Sort sort : SORTS) if (sort.id().equals(id)) return sort;
    return DEFAULT_SORT;
  }

  static int indexOf(Sort sort) {
    return SORTS.indexOf(sort);
  }

  private static final Pattern SECTION = Pattern.compile("/library/sections/(\\d+)/all");

  /** The section key of a section's "all" listing, or empty for any other listing. */
  static String sectionKey(String path) {
    Matcher m = SECTION.matcher(path);
    return m.matches() ? m.group(1) : "";
  }

  /** Items requested per page: about 48, and always whole rows. */
  static int pageSize(int columns) {
    int c = Math.max(1, columns);
    return c * Math.max(1, Math.round(48f / c));
  }

  /** The first index of the row holding {@code index}. */
  static int rowStart(int index, int columns) {
    int c = Math.max(1, columns);
    return Math.max(0, index) / c * c;
  }

  /** One page of a listing; {@code sortParam} empty keeps the server's own order. */
  static String pagePath(String path, String sortParam, int start, int size) {
    StringBuilder b = new StringBuilder(path).append(path.contains("?") ? '&' : '?');
    if (!sortParam.isEmpty()) b.append("sort=").append(sortParam).append('&');
    return b.append("X-Plex-Container-Start=")
        .append(Math.max(0, start))
        .append("&X-Plex-Container-Size=")
        .append(size)
        .toString();
  }

  /** Load the next page while focus is within this many rows of the loaded end. */
  static final int AHEAD_ROWS = 3;

  static boolean needsLater(int focused, int loadedEnd, int columns, boolean isComplete) {
    return !isComplete && focused >= loadedEnd - AHEAD_ROWS * Math.max(1, columns);
  }

  static boolean needsEarlier(int focused, int loadedStart, int columns) {
    return loadedStart > 0 && focused < loadedStart + AHEAD_ROWS * Math.max(1, columns);
  }

  /** The page before a window that starts at {@code loadedStart}, clamped at the first item. */
  static int earlierStart(int loadedStart, int pageSize) {
    return Math.max(0, loadedStart - pageSize);
  }

  /** Where a restored grid starts: two rows above the remembered item, on a row boundary. */
  static int restoreStart(int index, int columns) {
    return Math.max(0, rowStart(index, columns) - 2 * Math.max(1, columns));
  }

  /** One entry of the section's first-character index: "#", "A", … with its title count. */
  record Letter(String key, String title, int size) {}

  /** The index of the first title under {@code key} in title order, or -1 when absent. */
  static int offsetOf(List<Letter> letters, String key) {
    int offset = 0;
    for (Letter letter : letters) {
      if (letter.key().equals(key)) return offset;
      offset += Math.max(0, letter.size());
    }
    return -1;
  }

  /** The letter whose titles include {@code index}, or null past the end. */
  static Letter letterAt(List<Letter> letters, int index) {
    int offset = 0;
    for (Letter letter : letters) {
      offset += Math.max(0, letter.size());
      if (index < offset) return letter;
    }
    return null;
  }

  /** "248 titles · Title", or "1 title" when there is one. */
  static String countLine(int total, Sort sort) {
    String count = total == 1 ? "1 title" : total + " titles";
    return count + " · Sorted by " + sort.label();
  }
}
