package dev.queuepilot.player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Recently Added row at the top of a library's first screen. A movie library lists its newest
 * movies. A show library lists its newest episodes grouped by show: one card per show, newest
 * show first, saying how many new episodes it has.
 */
final class RecentlyAdded {
  private RecentlyAdded() {}

  /** Cards in the row at most. */
  static final int LIMIT = 20;

  /** Episodes read to group: enough that a busy show does not crowd the others out. */
  static final int EPISODES_READ = 60;

  static String moviesPath(String section) {
    return "/library/sections/"
        + section
        + "/all?type=1&sort=addedAt:desc&X-Plex-Container-Start=0&X-Plex-Container-Size="
        + LIMIT;
  }

  static String episodesPath(String section) {
    return "/library/sections/"
        + section
        + "/all?type=4&sort=addedAt:desc&X-Plex-Container-Start=0&X-Plex-Container-Size="
        + EPISODES_READ;
  }

  /** One show's card: its newest episode, and how many of the listed episodes are its. */
  record Show(String showKey, String showTitle, String thumb, int count, PlexCatalog.Entry latest) {
    /** "3 new episodes" or "1 new episode". */
    String countLine() {
      return count == 1 ? "1 new episode" : count + " new episodes";
    }
  }

  /**
   * Groups the newest episodes by show, in the order each show's newest episode was added. An
   * episode without a show key is left out: it has no show to open.
   */
  static List<Show> byShow(List<PlexCatalog.Entry> newestFirst, int limit) {
    Map<String, List<PlexCatalog.Entry>> shows = new LinkedHashMap<>();
    for (PlexCatalog.Entry episode : newestFirst) {
      if (episode.showKey().isEmpty()) continue;
      shows.computeIfAbsent(episode.showKey(), k -> new ArrayList<>()).add(episode);
    }
    List<Show> cards = new ArrayList<>();
    for (Map.Entry<String, List<PlexCatalog.Entry>> show : shows.entrySet()) {
      if (cards.size() >= limit) break;
      PlexCatalog.Entry latest = show.getValue().get(0);
      cards.add(
          new Show(
              show.getKey(),
              latest.showTitle().isEmpty() ? latest.title() : latest.showTitle(),
              latest.showThumb().isEmpty() ? latest.thumb() : latest.showThumb(),
              show.getValue().size(),
              latest));
    }
    return cards;
  }
}
