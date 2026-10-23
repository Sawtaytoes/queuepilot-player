package dev.queuepilot.player;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class DetailFactsTest {
  private static JSONObject facts(Object... pairs) throws Exception {
    JSONObject facts = new JSONObject();
    for (int i = 0; i < pairs.length; i += 2) facts.put((String) pairs[i], pairs[i + 1]);
    return facts;
  }

  private static JSONObject credit(String kind, String name) throws Exception {
    return new JSONObject().put("kind", kind).put("name", name);
  }

  @Test
  public void metaLineLeavesOutWhatPlexDidNotSend() throws Exception {
    assertEquals(
        "2024 · PG · 1 h 52 min · Lantern Pictures",
        DetailFacts.meta(
            "movie",
            facts("year", "2024", "contentRating", "PG", "studio", "Lantern Pictures"),
            112 * 60_000L));
    assertEquals("45 min", DetailFacts.meta("episode", facts(), 45 * 60_000L));
    assertEquals("", DetailFacts.meta("movie", facts(), 0));
    assertEquals(
        "2019 · 3 seasons · 1 episode",
        DetailFacts.meta("show", facts("year", "2019", "childCount", 3, "leafCount", 1), 0));
  }

  @Test
  public void runtimeRoundsToMinutes() {
    assertEquals("2 h", DetailFacts.runtime(120 * 60_000L));
    assertEquals("1 min", DetailFacts.runtime(40_000));
    assertEquals("", DetailFacts.runtime(10_000));
  }

  @Test
  public void genresAndScores() throws Exception {
    assertEquals(
        "Drama, Mystery",
        DetailFacts.genres(facts("genres", new JSONArray().put("Drama").put("Mystery"))));
    assertEquals("", DetailFacts.genres(facts()));
    assertEquals(
        "Critics 8.1 · Audience 7.6",
        DetailFacts.ratings(facts("rating", "8.1", "audienceRating", "7.6")));
    assertEquals("Audience 7.0", DetailFacts.ratings(facts("audienceRating", "7", "rating", "x")));
  }

  @Test
  public void progressSaysWhatIsLeftOrWatched() throws Exception {
    assertEquals(
        "38 min left", DetailFacts.progress("movie", 74 * 60_000L, 112 * 60_000L, facts()));
    assertEquals("Watched", DetailFacts.progress("movie", 0, 1, facts("viewCount", "2")));
    assertEquals("Unwatched", DetailFacts.progress("episode", 0, 1, facts()));
    assertEquals(
        "3 of 12 episodes watched",
        DetailFacts.progress("show", 0, 0, facts("leafCount", 12, "viewedLeafCount", 3)));
    assertEquals(
        "Watched",
        DetailFacts.progress("season", 0, 0, facts("leafCount", 4, "viewedLeafCount", 4)));
  }

  @Test
  public void creditsNameDirectorsAndWritersOnce() throws Exception {
    JSONArray roles =
        new JSONArray()
            .put(credit("Role", "Mara Quill"))
            .put(credit("Director", "Ines Cole"))
            .put(credit("Writer", "Theo Marsh"))
            .put(credit("Writer", "Ada Byrne"))
            .put(credit("Writer", "Rui Sol"))
            .put(credit("Director", "Ines Cole"));
    assertEquals(
        "Directed by Ines Cole · Written by Theo Marsh, Ada Byrne, and Rui Sol",
        DetailFacts.credits(roles));
    assertEquals("", DetailFacts.credits(new JSONArray()));
    assertEquals(1, DetailFacts.actors(roles).size());
  }
}
