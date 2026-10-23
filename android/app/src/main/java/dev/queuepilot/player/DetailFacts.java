package dev.queuepilot.player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The lines a title screen shows under its name, built from Plex metadata the way the Plex
 * client shows them: year, rating, length and studio; genres; scores; how much is watched; and
 * who directed and wrote it. A value Plex did not send is left out rather than guessed.
 */
final class DetailFacts {
  private DetailFacts() {}

  /** "2024 · PG-13 · 1 h 52 min · Lantern Pictures", or seasons and episodes for a show. */
  static String meta(String type, JSONObject facts, long durationMs) {
    List<String> parts = new ArrayList<>();
    add(parts, facts.optString("year"));
    add(parts, facts.optString("contentRating"));
    if (type.equals("show")) {
      add(parts, count(facts.optInt("childCount"), "season"));
      add(parts, count(facts.optInt("leafCount"), "episode"));
    } else if (type.equals("season")) add(parts, count(facts.optInt("leafCount"), "episode"));
    else add(parts, runtime(durationMs));
    add(parts, facts.optString("studio"));
    return String.join(" · ", parts);
  }

  static String genres(JSONObject facts) {
    JSONArray list = facts.optJSONArray("genres");
    if (list == null) return "";
    List<String> names = new ArrayList<>();
    for (int i = 0; i < list.length() && names.size() < 4; i++) add(names, list.optString(i));
    return String.join(", ", names);
  }

  /** Plex's critic and audience scores, both out of ten. */
  static String ratings(JSONObject facts) {
    List<String> parts = new ArrayList<>();
    String critics = score(facts.optString("rating"));
    String audience = score(facts.optString("audienceRating"));
    if (!critics.isEmpty()) parts.add("Critics " + critics);
    if (!audience.isEmpty()) parts.add("Audience " + audience);
    return String.join(" · ", parts);
  }

  /** "38 min left", "Watched" or "Unwatched"; for a show or season, episodes watched. */
  static String progress(String type, long resumeMs, long durationMs, JSONObject facts) {
    if (type.equals("show") || type.equals("season")) {
      int total = facts.optInt("leafCount"), watched = facts.optInt("viewedLeafCount");
      if (total <= 0) return "";
      if (watched >= total) return "Watched";
      if (watched == 0) return "Unwatched";
      return watched + " of " + total + " episodes watched";
    }
    if (resumeMs > 0 && durationMs > resumeMs) return runtime(durationMs - resumeMs) + " left";
    if (facts.optInt("viewCount") > 0) return "Watched";
    return "Unwatched";
  }

  /** "Directed by Lena Hart · Written by Theo Marsh", from the title's credits. */
  static String credits(JSONArray roles) {
    List<String> directors = new ArrayList<>(), writers = new ArrayList<>();
    for (int i = 0; roles != null && i < roles.length(); i++) {
      JSONObject credit = roles.optJSONObject(i);
      if (credit == null) continue;
      String kind = credit.optString("kind"), name = credit.optString("name");
      if (kind.equals("Director") && !directors.contains(name)) add(directors, name);
      if (kind.equals("Writer") && !writers.contains(name)) add(writers, name);
    }
    List<String> parts = new ArrayList<>();
    if (!directors.isEmpty()) parts.add("Directed by " + names(directors));
    if (!writers.isEmpty()) parts.add("Written by " + names(writers));
    return String.join(" · ", parts);
  }

  /** Only actors, in billing order, for the cast row. */
  static List<JSONObject> actors(JSONArray roles) {
    List<JSONObject> actors = new ArrayList<>();
    for (int i = 0; roles != null && i < roles.length(); i++) {
      JSONObject credit = roles.optJSONObject(i);
      if (credit != null && credit.optString("kind", "Role").equals("Role")) actors.add(credit);
    }
    return actors;
  }

  static String runtime(long milliseconds) {
    long minutes = Math.round(milliseconds / 60000.0);
    if (minutes <= 0) return "";
    if (minutes < 60) return minutes + " min";
    return minutes / 60 + " h" + (minutes % 60 == 0 ? "" : " " + minutes % 60 + " min");
  }

  private static String names(List<String> names) {
    if (names.size() <= 2) return String.join(" and ", names);
    return String.join(", ", names.subList(0, names.size() - 1))
        + ", and "
        + names.get(names.size() - 1);
  }

  private static String score(String value) {
    try {
      double score = Double.parseDouble(value);
      return score > 0 ? String.format(Locale.ROOT, "%.1f", score) : "";
    } catch (NumberFormatException e) {
      return "";
    }
  }

  private static String count(int value, String noun) {
    return value <= 0 ? "" : value + " " + noun + (value == 1 ? "" : "s");
  }

  private static void add(List<String> parts, String value) {
    if (value != null && !value.isBlank()) parts.add(value.trim());
  }
}
