package dev.queuepilot.player;

/**
 * One screen in the Back history. {@code position} is the library item a paged grid is
 * restored around, so Back from a title deep in a library returns to that title.
 */
record Screen(
    String kind, String path, String title, PlexCatalog.Entry entry, int position) {
  Screen(String kind, String path, String title, PlexCatalog.Entry entry) {
    this(kind, path, title, entry, 0);
  }

  Screen(String kind) {
    this(kind, "", "", null);
  }
}
