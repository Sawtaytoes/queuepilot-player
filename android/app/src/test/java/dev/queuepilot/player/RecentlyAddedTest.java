package dev.queuepilot.player;

import static org.junit.Assert.*;

import java.util.List;
import org.json.JSONObject;
import org.junit.Test;

public class RecentlyAddedTest {
  private static PlexCatalog.Entry episode(String key, String show, String showTitle)
      throws Exception {
    return PlexCatalog.Entry.fromJson(
        new JSONObject()
            .put("key", key)
            .put("type", "episode")
            .put("title", "Episode " + key)
            .put("showKey", show)
            .put("showTitle", showTitle)
            .put("showThumb", show.isEmpty() ? "" : "/art/" + show));
  }

  @Test
  public void pathsAskForTheNewestMoviesOrEpisodes() {
    assertEquals(
        "/library/sections/2/all?type=1&sort=addedAt:desc&X-Plex-Container-Start=0&X-Plex-Container-Size=20",
        RecentlyAdded.moviesPath("2"));
    assertEquals(
        "/library/sections/1/all?type=4&sort=addedAt:desc&X-Plex-Container-Start=0&X-Plex-Container-Size=60",
        RecentlyAdded.episodesPath("1"));
  }

  @Test
  public void episodesAreGroupedOneCardPerShowNewestShowFirst() throws Exception {
    List<RecentlyAdded.Show> shows =
        RecentlyAdded.byShow(
            List.of(
                episode("11", "100", "Orbit Nine"),
                episode("21", "200", "Harbor Lights"),
                episode("12", "100", "Orbit Nine"),
                episode("99", "", ""),
                episode("13", "100", "Orbit Nine")),
            20);
    assertEquals(2, shows.size());
    assertEquals("Orbit Nine", shows.get(0).showTitle());
    assertEquals("3 new episodes", shows.get(0).countLine());
    assertEquals("11", shows.get(0).latest().key());
    assertEquals("/art/100", shows.get(0).thumb());
    assertEquals("1 new episode", shows.get(1).countLine());
  }

  @Test
  public void theRowStopsAtItsLimit() throws Exception {
    List<RecentlyAdded.Show> shows =
        RecentlyAdded.byShow(
            List.of(episode("1", "1", "A"), episode("2", "2", "B"), episode("3", "3", "C")), 2);
    assertEquals(2, shows.size());
  }
}
