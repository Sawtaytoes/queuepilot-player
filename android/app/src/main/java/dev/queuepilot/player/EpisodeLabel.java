package dev.queuepilot.player;

/**
 * An episode is named by its show first: "Orbit Nine" over "S1 · E9 · The signal". An episode
 * title alone does not say which show is playing. Movies, shows and seasons keep their own title.
 */
final class EpisodeLabel {
  /** Plex and QueuePilot use this when a season number is not supplied. */
  static final int UNKNOWN = -1;

  private EpisodeLabel() {}

  static boolean isShowNamed(String type, String show) {
    return "episode".equals(type) && show != null && !show.isBlank();
  }

  /** The large line: the show for an episode, otherwise the item's own title. */
  static String primary(String type, String title, String show) {
    return isShowNamed(type, show) ? show.trim() : title;
  }

  /**
   * The smaller line for an episode: "S1 · E21 · Title". Season 0 is Plex's specials season, and
   * reads "Special · Title". Missing numbers are left out rather than guessed.
   */
  static String secondary(String type, int season, int episode, String title) {
    if (!"episode".equals(type)) return "";
    StringBuilder line = new StringBuilder();
    if (season == 0) line.append("Special");
    else {
      if (season > 0) line.append('S').append(season);
      if (episode > 0) {
        if (line.length() > 0) line.append(" · ");
        line.append('E').append(episode);
      }
    }
    if (title != null && !title.isBlank()) {
      if (line.length() > 0) line.append(" · ");
      line.append(title.trim());
    }
    return line.toString();
  }

  /**
   * The one-line now-playing title other devices show (the media session, and through it a Cast
   * status a universal remote reads): "Orbit Nine · S1 E9 · The signal". Those devices print a
   * single title line and often drop the subtitle, so the show, the number and the episode title
   * all go in it. Anything that is not a show-named episode keeps its own title.
   */
  static String nowPlaying(String type, String title, String show, int season, int episode) {
    if (!isShowNamed(type, show)) return title;
    StringBuilder line = new StringBuilder(show.trim());
    String number = season == 0 ? "Special" : "";
    if (season > 0) number = "S" + season;
    if (season != 0 && episode > 0) number = (number.isEmpty() ? "" : number + " ") + "E" + episode;
    if (!number.isEmpty()) line.append(" · ").append(number);
    if (title != null && !title.isBlank()) line.append(" · ").append(title.trim());
    return line.toString();
  }

  /** One spoken description for accessibility: "Orbit Nine, S1 · E9 · The signal". */
  static String spoken(String type, String title, String show, int season, int episode) {
    String first = primary(type, title, show), second = secondary(type, season, episode, title);
    if (!isShowNamed(type, show) || second.isEmpty()) return first;
    return first + ", " + second;
  }
}
