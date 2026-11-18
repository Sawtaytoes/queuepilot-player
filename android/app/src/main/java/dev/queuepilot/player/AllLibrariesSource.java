package dev.queuepilot.player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Reads the titles of every included library for All Movies and Shows and keeps them for a few
 * minutes, so paging, sorting and letter jumps do not read every library again. Each opening of the
 * screen is a session ({@link #open}); only a session's first read may replace a stale read, so its
 * pages, its letters, and the grids its letter jumps start all come from one read. Every call
 * takes {@code isWanted}, checked on taking the lock, after each response, and before the read is
 * kept or sorted, so a request for a screen the viewer has left stops early and never replaces or
 * adds to a newer screen's read. Call it off the main thread.
 */
final class AllLibrariesSource {
  /** How long a read stays fresh. */
  static final long FRESH_MS = 5 * 60_000;

  /** Titles read per request from one library. */
  static final int READ_SIZE = 500;

  /** One page of a library's titles, and the library's total size, or -1 when unknown. */
  record Titles(JSONArray items, int total) {}

  /** The server requests this source makes. Tests replace them, since they cannot parse XML. */
  interface Reads {
    JSONArray sections(String origin, String secret) throws Exception;

    Titles titles(String origin, String secret, String section, int start) throws Exception;
  }

  /** The media server's own responses. */
  static final Reads SERVER =
      new Reads() {
        @Override
        public JSONArray sections(String origin, String secret) throws Exception {
          return PlexCatalog.list(
              PlexCatalog.get(
                  PlexCatalog.url(origin, "/library/sections"), secret, 4 * 1024 * 1024));
        }

        @Override
        public Titles titles(String origin, String secret, String section, int start)
            throws Exception {
          byte[] xml =
              PlexCatalog.get(
                  PlexCatalog.url(
                      origin,
                      LibraryBrowse.pagePath(
                          "/library/sections/" + section + "/all", "", start, READ_SIZE)),
                  secret,
                  16 * 1024 * 1024);
          return new Titles(PlexCatalog.list(xml), PlexCatalog.totalSize(xml));
        }
      };

  private final java.util.function.LongSupplier clock;
  private final Reads reads;
  private String readFor = "";
  private long readAt;
  private List<AllLibraries.Library> libraries = List.of();
  private final Map<String, JSONArray> bySection = new HashMap<>();
  private final Map<String, List<JSONObject>> sorted = new HashMap<>();

  /** Guards only the session numbers, never a request, so {@link #open} never waits on one. */
  private final Object sessions = new Object();

  private long opening, refreshedFor = -1;

  AllLibrariesSource(java.util.function.LongSupplier clock) {
    this(clock, SERVER);
  }

  AllLibrariesSource(java.util.function.LongSupplier clock, Reads reads) {
    this.clock = clock;
    this.reads = reads;
  }

  /** The screen opens (or opens again on Back): a new session, whose first read may refresh. */
  long open() {
    synchronized (sessions) {
      return ++opening;
    }
  }

  /**
   * True once for the current session, on its first read: that read may replace a stale one. An
   * older session's late request never may.
   */
  boolean takeRefresh(long session) {
    synchronized (sessions) {
      if (session != opening || refreshedFor == session) return false;
      refreshedFor = session;
      return true;
    }
  }

  /**
   * Whether a read made for {@code readFor} at {@code readAt} still serves {@code owner} at {@code
   * now}. A stale read still serves a grid's later pages ({@code mayRefresh} false).
   */
  static boolean isUsable(
      String owner, String readFor, long readAt, long now, boolean mayRefresh) {
    return owner.equals(readFor) && (!mayRefresh || now - readAt < FRESH_MS);
  }

  /**
   * Stops a request whose screen the viewer has left. Checked on taking the lock, before the
   * read is used or replaced, and between requests.
   */
  static void stopUnlessWanted(BooleanSupplier isWanted) {
    if (!isWanted.getAsBoolean()) throw new CancellationException();
  }

  private List<AllLibraries.Library> included(
      String origin,
      String secret,
      String profile,
      Predicate<String> isIncluded,
      boolean mayRefresh,
      BooleanSupplier isWanted)
      throws Exception {
    readSections(origin, secret, profile, mayRefresh, isWanted);
    List<AllLibraries.Library> found = new ArrayList<>();
    for (AllLibraries.Library library : libraries)
      if (isIncluded.test(library.key())) found.add(library);
    return found;
  }

  /** One page of the merged grid, as {@code {"entries": [...], "total": n}}. */
  synchronized JSONObject page(
      String origin,
      String secret,
      String profile,
      String path,
      long session,
      Predicate<String> isIncluded,
      BooleanSupplier isWanted)
      throws Exception {
    stopUnlessWanted(isWanted);
    int start = AllLibraries.intParameter(path, "X-Plex-Container-Start", 0);
    List<JSONObject> merged =
        merged(
            origin,
            secret,
            profile,
            AllLibraries.sortOf(path),
            isIncluded,
            takeRefresh(session),
            isWanted);
    int size = AllLibraries.intParameter(path, "X-Plex-Container-Size", merged.size());
    return new JSONObject()
        .put("entries", AllLibraries.page(merged, start, size))
        .put("total", merged.size());
  }

  /** The jump-by-letter index of the merged grid in title order. */
  synchronized List<LibraryBrowse.Letter> letters(
      String origin,
      String secret,
      String profile,
      long session,
      Predicate<String> isIncluded,
      BooleanSupplier isWanted)
      throws Exception {
    stopUnlessWanted(isWanted);
    return AllLibraries.letters(
        merged(
            origin,
            secret,
            profile,
            LibraryBrowse.DEFAULT_SORT,
            isIncluded,
            takeRefresh(session),
            isWanted));
  }

  /** Search across the included libraries, one request per library, merged once each. */
  JSONArray search(
      String origin,
      String secret,
      String profile,
      String query,
      Predicate<String> isIncluded,
      BooleanSupplier isWanted)
      throws Exception {
    List<AllLibraries.Library> included;
    synchronized (this) {
      stopUnlessWanted(isWanted);
      included = included(origin, secret, profile, isIncluded, true, isWanted);
    }
    List<JSONArray> results = new ArrayList<>();
    for (AllLibraries.Library library : included) {
      stopUnlessWanted(isWanted);
      results.add(
          PlexCatalog.hubs(
              PlexCatalog.get(
                  PlexCatalog.url(
                      origin,
                      "/hubs/search?query="
                          + PlexAccount.encode(query)
                          + "&limit=30&sectionId="
                          + library.key()),
                  secret,
                  16 * 1024 * 1024)));
    }
    return AllLibraries.mergeSearches(included, results);
  }

  private List<JSONObject> merged(
      String origin,
      String secret,
      String profile,
      LibraryBrowse.Sort sort,
      Predicate<String> isIncluded,
      boolean mayRefresh,
      BooleanSupplier isWanted)
      throws Exception {
    List<AllLibraries.Library> included =
        included(origin, secret, profile, isIncluded, mayRefresh, isWanted);
    StringBuilder id = new StringBuilder(sort.id());
    for (AllLibraries.Library library : included) {
      id.append('|').append(library.key());
      if (bySection.containsKey(library.key())) continue;
      JSONArray titles = readAll(origin, secret, library.key(), isWanted);
      // A screen the viewer left during the last response never keeps or sorts what it read.
      stopUnlessWanted(isWanted);
      bySection.put(library.key(), titles);
    }
    List<JSONObject> found = sorted.get(id.toString());
    if (found == null) {
      stopUnlessWanted(isWanted);
      found = AllLibraries.merge(included, bySection, sort);
      stopUnlessWanted(isWanted);
      sorted.put(id.toString(), found);
    }
    return found;
  }

  /** The library list, read again when it belongs to another profile or server, or is stale. */
  private void readSections(
      String origin, String secret, String profile, boolean mayRefresh, BooleanSupplier isWanted)
      throws Exception {
    String owner = profile + "|" + origin;
    long now = clock.getAsLong();
    if (isUsable(owner, readFor, readAt, now, mayRefresh)) return;
    JSONArray sections = reads.sections(origin, secret);
    // A request for a screen the viewer has left never replaces the read a newer one uses.
    stopUnlessWanted(isWanted);
    libraries = AllLibraries.libraries(sections);
    bySection.clear();
    sorted.clear();
    readFor = owner;
    readAt = now;
  }

  /** Every title of one library, in pages of {@link #READ_SIZE}. */
  private JSONArray readAll(
      String origin, String secret, String section, BooleanSupplier isWanted) throws Exception {
    JSONArray all = new JSONArray();
    for (int start = 0; ; start += READ_SIZE) {
      stopUnlessWanted(isWanted);
      Titles read = reads.titles(origin, secret, section, start);
      stopUnlessWanted(isWanted);
      JSONArray page = read.items();
      // A server that ignores paging sends the first page again: the library is complete.
      if (start > 0 && LibraryBrowse.isRepeat(all.optJSONObject(0), page)) break;
      for (int i = 0; i < page.length(); i++) all.put(page.get(i));
      int total = read.total();
      // A server that ignores paging sends everything at once.
      if (page.length() < READ_SIZE || page.length() > READ_SIZE) break;
      if (total >= 0 && all.length() >= total) break;
    }
    return all;
  }
}
