package dev.queuepilot.player;

import static org.junit.Assert.*;

import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class TitleLinksTest {
  private static JSONObject tag(String name, String id) throws Exception {
    return new JSONObject().put("tag", name).put("id", id);
  }

  private static JSONObject listed(String key, String type, String title) throws Exception {
    return new JSONObject().put("key", key).put("type", type).put("title", title);
  }

  @Test
  public void collectionsComeFromTheTagsOnceEachInPlexsOrder() throws Exception {
    JSONObject facts =
        new JSONObject()
            .put(
                "collections",
                new JSONArray()
                    .put(tag("Glass Saga", "71"))
                    .put(tag("", "72"))
                    .put(tag("City Stories", "73"))
                    .put(tag("Glass Saga", "71")));
    assertEquals(
        List.of(
            new TitleLinks.Collection("Glass Saga", "71"),
            new TitleLinks.Collection("City Stories", "73")),
        TitleLinks.collections(facts));
    assertTrue(TitleLinks.collections(new JSONObject()).isEmpty());
    assertTrue(TitleLinks.collections(null).isEmpty());
  }

  @Test
  public void aCollectionIsFoundByNameInTheSectionsList() throws Exception {
    JSONArray section =
        new JSONArray()
            .put(listed("900", "movie", "Glass Saga"))
            .put(listed("901", "collection", "glass saga"))
            .put(listed("902", "collection", "Glass Saga"))
            .put(listed("/odd", "collection", "City Stories"));
    // Exact name first, then a match ignoring case; only collections, only numeric keys.
    assertEquals("902", TitleLinks.collectionKey(section, "Glass Saga"));
    assertEquals("901", TitleLinks.collectionKey(section, "GLASS SAGA"));
    assertEquals("", TitleLinks.collectionKey(section, "City Stories"));
    assertEquals("", TitleLinks.collectionKey(null, "Glass Saga"));
  }

  @Test
  public void collectionPathsUseTheCollectionOrderAndFallBackToAFilter() {
    assertEquals("/library/collections/902/children", TitleLinks.collectionPath("902"));
    assertEquals("/library/sections/2/all?collection=71", TitleLinks.collectionFilterPath("2", "71"));
    assertEquals("", TitleLinks.collectionFilterPath("", "71"));
    assertEquals("", TitleLinks.collectionFilterPath("2", "7&x=1"));
  }

  @Test
  public void parentsOpenTheirChildren() {
    assertEquals("/library/metadata/101/children", TitleLinks.childrenPath("101"));
    assertEquals("", TitleLinks.childrenPath(""));
    assertEquals("", TitleLinks.childrenPath("../1"));
  }

  @Test
  public void watchedAndUnwatchedUseScrobbleAndUnscrobbleOnTheParentKey() {
    assertEquals(
        "/:/scrobble?identifier=com.plexapp.plugins.library&key=101",
        TitleLinks.watchedPath("101", true));
    assertEquals(
        "/:/unscrobble?identifier=com.plexapp.plugins.library&key=100",
        TitleLinks.watchedPath("100", false));
    assertThrows(IllegalArgumentException.class, () -> TitleLinks.watchedPath("1&key=2", true));
  }

  @Test
  public void theWatchedPanelSaysWhatChangesAndForWhom() throws Exception {
    assertEquals("Season 1", TitleLinks.watchedTitle("season", "Season 1", 1));
    assertEquals("Season 2", TitleLinks.watchedTitle("season", "", 2));
    assertEquals("this season", TitleLinks.watchedTitle("season", "", EpisodeLabel.UNKNOWN));
    assertEquals("Orbit Nine", TitleLinks.watchedTitle("show", "Orbit Nine", 0));
    assertEquals(
        "Mark all 12 episodes of Orbit Nine for Ada. 3 of 12 are watched now.",
        TitleLinks.watchedMessage(
            "Orbit Nine", new JSONObject().put("leafCount", "12").put("viewedLeafCount", "3"), "Ada"));
    assertEquals(
        "Mark the 1 episode of Season 1 for Grace. 1 of 1 is watched now.",
        TitleLinks.watchedMessage(
            "Season 1", new JSONObject().put("leafCount", 1).put("viewedLeafCount", 1), "Grace"));
    assertEquals(
        "Mark every episode of Season 1 for Ada.",
        TitleLinks.watchedMessage("Season 1", new JSONObject(), "Ada"));
  }
}
