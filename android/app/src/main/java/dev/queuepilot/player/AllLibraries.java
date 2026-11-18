package dev.queuepilot.player;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * All Movies and Shows: every included movie and show library in one grid, each title tagged with
 * its library. The media server has no listing across libraries, so the titles are read per
 * library and merged here, in any of the five sorts; the jump-by-letter index is counted here too.
 */
final class AllLibraries {
  private AllLibraries() {}

  static final String TITLE = "All Movies and Shows";

  /** The merged grid's listing. It is not a server path, so it can never be sent to a server. */
  static final String PATH = "queuepilot:all";

  /** The search screen's "section" for the merged view. */
  static final String SEARCH_SECTION = "all";

  static boolean isPath(String path) {
    return path.equals(PATH) || path.startsWith(PATH + "?");
  }

  /** One movie or show library, with its chip color: an index into {@link TvTheme#CHIP_CONTENT}. */
  record Library(String key, String title, String type, int color) {}

  /**
   * Every movie and show library, in the server's key order. A library's color comes from its place
   * in that order, so it stays the same from visit to visit, and neighbors never share one.
   */
  static List<Library> libraries(JSONArray sections) {
    List<JSONObject> found = new ArrayList<>();
    for (int i = 0; sections != null && i < sections.length(); i++) {
      JSONObject section = sections.optJSONObject(i);
      if (section == null) continue;
      String type = section.optString("libraryType");
      if ((type.equals("movie") || type.equals("show")) && section.optString("key").matches("[0-9]+"))
        found.add(section);
    }
    found.sort(Comparator.comparingLong(s -> Long.parseLong(s.optString("key"))));
    List<Library> libraries = new ArrayList<>();
    for (int i = 0; i < found.size(); i++) {
      JSONObject s = found.get(i);
      libraries.add(
          new Library(
              s.optString("key"),
              s.optString("title"),
              s.optString("libraryType"),
              i % TvTheme.CHIP_CONTENT.length));
    }
    return libraries;
  }

  /** The text a title's place in title order is decided by: its sort title, without "The". */
  static String titleKey(JSONObject item) {
    JSONObject facts = item.optJSONObject("facts");
    String sort = facts == null ? "" : facts.optString("titleSort");
    String title = (sort.isBlank() ? item.optString("title") : sort).trim();
    String lower = title.toLowerCase(Locale.ROOT);
    for (String article : new String[] {"the ", "a ", "an "})
      if (lower.startsWith(article) && title.length() > article.length()) {
        title = title.substring(article.length()).trim();
        break;
      }
    return Normalizer.normalize(title, Normalizer.Form.NFD)
        .replaceAll("\\p{M}", "")
        .toLowerCase(Locale.ROOT);
  }

  /** "#" for a title that starts with anything but a letter A–Z, otherwise its letter. */
  static String letter(JSONObject item) {
    String key = titleKey(item);
    char c = key.isEmpty() ? '#' : Character.toUpperCase(key.charAt(0));
    return c >= 'A' && c <= 'Z' ? String.valueOf(c) : "#";
  }

  private static final Comparator<JSONObject> BY_TITLE =
      Comparator.comparing((JSONObject item) -> letter(item).equals("#") ? "" : letter(item))
          .thenComparing(AllLibraries::titleKey)
          .thenComparing(item -> item.optString("key"));

  private static String fact(JSONObject item, String name) {
    JSONObject facts = item.optJSONObject("facts");
    return facts == null ? "" : facts.optString(name);
  }

  private static double number(JSONObject item, String name) {
    try {
      return Double.parseDouble(fact(item, name));
    } catch (NumberFormatException e) {
      return Double.NEGATIVE_INFINITY;
    }
  }

  /** Newest, latest, or highest first; a title without the value goes last, then title order. */
  static Comparator<JSONObject> order(LibraryBrowse.Sort sort) {
    Comparator<JSONObject> descending =
        switch (sort.id()) {
          case "added" -> Comparator.comparingDouble(i -> -number(i, "addedAt"));
          case "released" ->
              Comparator.comparing(
                  (JSONObject i) -> fact(i, "originallyAvailableAt"), Comparator.reverseOrder());
          case "rating" -> Comparator.comparingDouble(i -> -number(i, "audienceRating"));
          case "watched" -> Comparator.comparingDouble(i -> -number(i, "lastViewedAt"));
          default -> null;
        };
    return descending == null ? BY_TITLE : descending.thenComparing(BY_TITLE);
  }

  /**
   * The libraries' titles in one list, sorted, each tagged in its facts with the library it came
   * from ({@code library}, {@code libraryTitle}, {@code libraryColor}).
   */
  static List<JSONObject> merge(
      List<Library> included, Map<String, JSONArray> bySection, LibraryBrowse.Sort sort)
      throws Exception {
    List<JSONObject> merged = new ArrayList<>();
    for (Library library : included) {
      JSONArray items = bySection.get(library.key());
      for (int i = 0; items != null && i < items.length(); i++) {
        JSONObject item = items.optJSONObject(i);
        if (item == null) continue;
        JSONObject facts = item.optJSONObject("facts");
        if (facts == null) item.put("facts", facts = new JSONObject());
        facts
            .put("library", library.key())
            .put("libraryTitle", library.title())
            .put("libraryColor", library.color());
        merged.add(item);
      }
    }
    merged.sort(order(sort));
    return merged;
  }

  /** The merged list's jump-by-letter index, in title order: "#" first, then A to Z. */
  static List<LibraryBrowse.Letter> letters(List<JSONObject> byTitle) {
    Map<String, Integer> counts = new LinkedHashMap<>();
    for (JSONObject item : byTitle) counts.merge(letter(item), 1, Integer::sum);
    List<LibraryBrowse.Letter> letters = new ArrayList<>();
    for (Map.Entry<String, Integer> c : counts.entrySet())
      letters.add(new LibraryBrowse.Letter(c.getKey(), c.getKey(), c.getValue()));
    return letters;
  }

  /** One page of the merged list. */
  static JSONArray page(List<JSONObject> merged, int start, int size) {
    JSONArray page = new JSONArray();
    for (int i = Math.max(0, start); i < Math.min(merged.size(), start + Math.max(0, size)); i++)
      page.put(merged.get(i));
    return page;
  }

  /** A query parameter of a merged-grid page path ({@link LibraryBrowse#pagePath}). */
  static String parameter(String path, String name) {
    int q = path.indexOf('?');
    if (q < 0) return "";
    for (String pair : path.substring(q + 1).split("&")) {
      int eq = pair.indexOf('=');
      if (eq > 0 && pair.substring(0, eq).equals(name)) return pair.substring(eq + 1);
    }
    return "";
  }

  /** The sort a merged-grid page path names, by the media server's sort parameter. */
  static LibraryBrowse.Sort sortOf(String path) {
    String param = parameter(path, "sort");
    for (LibraryBrowse.Sort sort : LibraryBrowse.SORTS) if (sort.param().equals(param)) return sort;
    return LibraryBrowse.DEFAULT_SORT;
  }

  static int intParameter(String path, String name, int fallback) {
    try {
      return Integer.parseInt(parameter(path, name));
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  /**
   * Search results of each included library in one list, once each, tagged with their library like
   * the grid's titles.
   */
  static JSONArray mergeSearches(List<Library> included, List<JSONArray> perLibrary)
      throws Exception {
    JSONArray found = new JSONArray();
    java.util.Set<String> seen = new java.util.HashSet<>();
    for (int l = 0; l < included.size() && l < perLibrary.size(); l++) {
      Library library = included.get(l);
      JSONArray items = perLibrary.get(l);
      for (int i = 0; items != null && i < items.length(); i++) {
        JSONObject item = items.optJSONObject(i);
        if (item == null || !seen.add(item.optString("hub") + "|" + item.optString("key"))) continue;
        JSONObject facts = item.optJSONObject("facts");
        if (facts == null) item.put("facts", facts = new JSONObject());
        facts
            .put("library", library.key())
            .put("libraryTitle", library.title())
            .put("libraryColor", library.color());
        found.put(item);
      }
    }
    return found;
  }
}
