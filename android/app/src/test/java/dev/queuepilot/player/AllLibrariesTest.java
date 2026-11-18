package dev.queuepilot.player;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class AllLibrariesTest {
  private static JSONObject section(String key, String type, String title) throws Exception {
    return new JSONObject().put("key", key).put("libraryType", type).put("title", title);
  }

  private static JSONObject item(String key, String title, Object... facts) throws Exception {
    JSONObject f = new JSONObject();
    for (int i = 0; i < facts.length; i += 2) f.put((String) facts[i], facts[i + 1]);
    return new JSONObject().put("key", key).put("title", title).put("facts", f);
  }

  private static List<String> titles(List<JSONObject> items) {
    List<String> found = new ArrayList<>();
    for (JSONObject i : items) found.add(i.optString("title"));
    return found;
  }

  private static final LibraryBrowse.Sort TITLE = LibraryBrowse.sort("title");

  @Test
  public void librariesAreMoviesAndShowsInKeyOrderWithStableColors() throws Exception {
    JSONArray sections =
        new JSONArray()
            .put(section("11", "movie", "Archive"))
            .put(section("2", "movie", "Films"))
            .put(section("5", "artist", "Music"))
            .put(section("1", "show", "Series"));
    List<AllLibraries.Library> libraries = AllLibraries.libraries(sections);
    assertEquals(3, libraries.size());
    assertEquals("Series", libraries.get(0).title());
    assertEquals("Films", libraries.get(1).title());
    assertEquals("Archive", libraries.get(2).title());
    assertEquals(0, libraries.get(0).color());
    assertEquals(2, libraries.get(2).color());
    // The same listing gives the same colors every time.
    assertEquals(libraries, AllLibraries.libraries(sections));
  }

  @Test
  public void colorsRepeatOnlyAfterEveryHueIsUsed() throws Exception {
    JSONArray sections = new JSONArray();
    for (int i = 1; i <= TvTheme.CHIP_CONTENT.length + 1; i++)
      sections.put(section(String.valueOf(i), "movie", "L" + i));
    List<AllLibraries.Library> libraries = AllLibraries.libraries(sections);
    assertEquals(0, libraries.get(TvTheme.CHIP_CONTENT.length).color());
    assertEquals(TvTheme.CHIP_CONTENT.length - 1, libraries.get(TvTheme.CHIP_CONTENT.length - 1).color());
  }

  @Test
  public void titleOrderIgnoresArticlesAccentsAndCaseAndPutsDigitsFirst() throws Exception {
    List<AllLibraries.Library> both =
        List.of(new AllLibraries.Library("1", "Series", "show", 0), new AllLibraries.Library("2", "Films", "movie", 1));
    List<JSONObject> merged =
        AllLibraries.merge(
            both,
            Map.of(
                "1", new JSONArray().put(item("a", "The Quiet Orchard")).put(item("b", "Éclair")),
                "2",
                    new JSONArray()
                        .put(item("c", "glass city"))
                        .put(item("d", "3 Rivers"))
                        .put(item("e", "Zero Hour", "titleSort", "Aardvark"))),
            TITLE);
    assertEquals(
        List.of("3 Rivers", "Zero Hour", "Éclair", "glass city", "The Quiet Orchard"),
        titles(merged));
    assertEquals("Series", merged.get(4).getJSONObject("facts").getString("libraryTitle"));
    assertEquals(1, merged.get(0).getJSONObject("facts").getInt("libraryColor"));
    List<LibraryBrowse.Letter> letters = AllLibraries.letters(merged);
    assertEquals(
        List.of("#", "A", "E", "G", "Q"),
        letters.stream().map(LibraryBrowse.Letter::key).toList());
    // Each letter's offset lands on its first title.
    assertEquals(3, LibraryBrowse.offsetOf(letters, "G"));
  }

  @Test
  public void otherSortsPutNewestOrHighestFirstAndMissingValuesLast() throws Exception {
    List<AllLibraries.Library> one = List.of(new AllLibraries.Library("1", "Films", "movie", 0));
    JSONArray items =
        new JSONArray()
            .put(item("a", "Alpha", "addedAt", "100", "audienceRating", "7.5", "originallyAvailableAt", "2001-05-01"))
            .put(item("b", "Bravo", "addedAt", "300", "lastViewedAt", "50"))
            .put(item("c", "Charlie", "addedAt", "200", "audienceRating", "9.1", "originallyAvailableAt", "2020-01-01", "lastViewedAt", "90"));
    assertEquals(
        List.of("Bravo", "Charlie", "Alpha"),
        titles(AllLibraries.merge(one, Map.of("1", items), LibraryBrowse.sort("added"))));
    assertEquals(
        List.of("Charlie", "Alpha", "Bravo"),
        titles(AllLibraries.merge(one, Map.of("1", items), LibraryBrowse.sort("rating"))));
    assertEquals(
        List.of("Charlie", "Alpha", "Bravo"),
        titles(AllLibraries.merge(one, Map.of("1", items), LibraryBrowse.sort("released"))));
    assertEquals(
        List.of("Charlie", "Bravo", "Alpha"),
        titles(AllLibraries.merge(one, Map.of("1", items), LibraryBrowse.sort("watched"))));
  }

  @Test
  public void pagesAndPathParametersMatchTheGridsPaging() throws Exception {
    List<JSONObject> merged = new ArrayList<>();
    for (int i = 0; i < 7; i++) merged.add(item(String.valueOf(i), "T" + i));
    assertEquals(2, AllLibraries.page(merged, 5, 3).length());
    assertEquals("T5", AllLibraries.page(merged, 5, 3).getJSONObject(0).getString("title"));
    assertEquals(0, AllLibraries.page(merged, 9, 3).length());
    String path = LibraryBrowse.pagePath(AllLibraries.PATH, "addedAt:desc", 48, 24);
    assertTrue(AllLibraries.isPath(path));
    assertFalse(AllLibraries.isPath("/library/sections/1/all"));
    assertEquals(LibraryBrowse.sort("added"), AllLibraries.sortOf(path));
    assertEquals(LibraryBrowse.DEFAULT_SORT, AllLibraries.sortOf(AllLibraries.PATH));
    assertEquals(48, AllLibraries.intParameter(path, "X-Plex-Container-Start", 0));
    assertEquals(24, AllLibraries.intParameter(path, "X-Plex-Container-Size", 0));
    assertThrows(IllegalArgumentException.class, () -> PlexCatalog.url("http://10.0.0.2:32400", path));
  }

  @Test
  public void searchesAreMergedOnceEachAndTagged() throws Exception {
    List<AllLibraries.Library> both =
        List.of(new AllLibraries.Library("1", "Series", "show", 0), new AllLibraries.Library("2", "Films", "movie", 1));
    JSONArray series = new JSONArray().put(item("100", "Orbit Nine").put("hub", "show"));
    JSONArray films =
        new JSONArray()
            .put(item("302", "Glass City").put("hub", "movie"))
            .put(item("100", "Orbit Nine").put("hub", "show"));
    JSONArray found = AllLibraries.mergeSearches(both, List.of(series, films));
    assertEquals(2, found.length());
    assertEquals("Films", found.getJSONObject(1).getJSONObject("facts").getString("libraryTitle"));
  }
}
