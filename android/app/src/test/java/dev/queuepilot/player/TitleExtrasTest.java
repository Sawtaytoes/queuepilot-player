package dev.queuepilot.player;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;

public class TitleExtrasTest {
  private static PlexCatalog.Entry extra(String title, String extraType, String subtype)
      throws Exception {
    JSONObject facts = new JSONObject();
    if (extraType != null) facts.put("extraType", extraType);
    if (subtype != null) facts.put("subtype", subtype);
    return PlexCatalog.Entry.fromJson(
        new JSONObject()
            .put("key", title.replaceAll("[^a-z]", ""))
            .put("title", title)
            .put("type", "clip")
            .put("facts", facts));
  }

  private static List<String> headings(List<TitleExtras.Group> groups) {
    List<String> found = new ArrayList<>();
    for (TitleExtras.Group group : groups) found.add(group.kind().heading());
    return found;
  }

  @Test
  public void plexsNumberedKindsWinOverTheWord() {
    assertSame(TitleExtras.TRAILER, TitleExtras.kind("1", "featurette"));
    assertSame(TitleExtras.DELETED_SCENE, TitleExtras.kind("2", ""));
    assertSame(TitleExtras.INTERVIEW, TitleExtras.kind("3", null));
    assertSame(TitleExtras.BEHIND_THE_SCENES, TitleExtras.kind("5", ""));
    assertSame(TitleExtras.SCENE, TitleExtras.kind("6", ""));
    assertSame(TitleExtras.FEATURETTE, TitleExtras.kind("10", ""));
    assertSame(TitleExtras.SHORT, TitleExtras.kind("11", ""));
    // Music videos, concerts and "other" have no row of their own.
    for (String music : List.of("4", "7", "8", "9", "12"))
      assertSame(music, TitleExtras.OTHER, TitleExtras.kind(music, "trailer"));
  }

  @Test
  public void theWordIsReadWhenTheNumberIsMissingOrUnknown() {
    assertSame(TitleExtras.TRAILER, TitleExtras.kind("", "trailer"));
    assertSame(TitleExtras.BEHIND_THE_SCENES, TitleExtras.kind("", "behindTheScenes"));
    assertSame(TitleExtras.BEHIND_THE_SCENES, TitleExtras.kind("99", "behind-the-scenes"));
    assertSame(TitleExtras.DELETED_SCENE, TitleExtras.kind(null, "deleted"));
    assertSame(TitleExtras.DELETED_SCENE, TitleExtras.kind("", "deletedScene"));
    assertSame(TitleExtras.SCENE, TitleExtras.kind("", "sceneOrSample"));
    assertSame(TitleExtras.SHORT, TitleExtras.kind("", "Short"));
    assertSame(TitleExtras.OTHER, TitleExtras.kind("", "musicVideo"));
    assertSame(TitleExtras.OTHER, TitleExtras.kind("", ""));
    assertSame(TitleExtras.OTHER, TitleExtras.kind(null, null));
  }

  @Test
  public void extrasAreGroupedOneRowPerKindInAFixedOrder() throws Exception {
    List<TitleExtras.Group> groups =
        TitleExtras.group(
            List.of(
                extra("Gag reel", "12", "other"),
                extra("Building the city", "5", "behindTheScenes"),
                extra("Teaser", "1", "trailer"),
                extra("The alley cut", "2", "deleted"),
                extra("Official trailer", "1", "trailer"),
                extra("On glass", "10", "featurette"),
                extra("The director", "3", "interview")));
    assertEquals(
        List.of(
            "Trailers",
            "Behind the scenes",
            "Deleted scenes",
            "Featurettes",
            "Interviews",
            "Other"),
        headings(groups));
    // Within a row, Plex's own order is kept.
    assertEquals("Teaser", groups.get(0).items().get(0).title());
    assertEquals("Official trailer", groups.get(0).items().get(1).title());
  }

  @Test
  public void playTrailerStartsTheFirstTrailerAndIsAbsentWithoutOne() throws Exception {
    List<TitleExtras.Group> withTrailer =
        TitleExtras.group(
            List.of(extra("Interview", "3", ""), extra("Teaser", "1", ""), extra("Full", "1", "")));
    assertEquals("Teaser", TitleExtras.trailer(withTrailer).title());
    assertNull(TitleExtras.trailer(TitleExtras.group(List.of(extra("Interview", "3", "")))));
    assertNull(TitleExtras.trailer(TitleExtras.group(List.of())));
    assertTrue(TitleExtras.group(List.of()).isEmpty());
  }

  @Test
  public void aCardIsNamedByItsKindAndTitle() {
    assertEquals("Trailer, Teaser", TitleExtras.spoken(TitleExtras.TRAILER, "Teaser"));
    assertEquals("Deleted scene", TitleExtras.spoken(TitleExtras.DELETED_SCENE, " "));
    assertEquals("Extra, Gag reel", TitleExtras.spoken(TitleExtras.OTHER, "Gag reel"));
  }
}
