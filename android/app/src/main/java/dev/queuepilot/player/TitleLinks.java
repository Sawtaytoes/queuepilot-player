package dev.queuepilot.player;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Where a title screen leads: an episode's show and season, a movie's or show's collections, and
 * the media server requests that mark a whole show or season watched or unwatched.
 */
final class TitleLinks {
  private TitleLinks() {}

  /** One of the collections a title belongs to: its name and the server's tag id. */
  record Collection(String title, String id) {}

  /**
   * The title's collections, from the {@code <Collection>} tags of its full record, once each and
   * in Plex's order. A tag with no name is left out: there is nothing to show for it.
   */
  static List<Collection> collections(JSONObject facts) {
    List<Collection> found = new ArrayList<>();
    JSONArray tags = facts == null ? null : facts.optJSONArray("collections");
    for (int i = 0; tags != null && i < tags.length(); i++) {
      JSONObject tag = tags.optJSONObject(i);
      if (tag == null) continue;
      String title = tag.optString("tag").trim();
      if (title.isEmpty()) continue;
      Collection collection = new Collection(title, tag.optString("id").trim());
      boolean isRepeat = false;
      for (Collection known : found) isRepeat |= known.title().equals(title);
      if (!isRepeat) found.add(collection);
    }
    return found;
  }

  /**
   * The ratingKey of the named collection in a section's collection list ({@code
   * /library/sections/<key>/collections}), or empty. The tag carries the collection's name, not
   * its ratingKey, so the name is matched: exactly first, then ignoring case.
   */
  static String collectionKey(JSONArray sectionCollections, String title) {
    String loose = "";
    for (int i = 0; sectionCollections != null && i < sectionCollections.length(); i++) {
      JSONObject item = sectionCollections.optJSONObject(i);
      if (item == null || !"collection".equals(item.optString("type"))) continue;
      String name = item.optString("title"), key = item.optString("key");
      if (key.isEmpty() || !key.matches("[0-9]+")) continue;
      if (name.equals(title)) return key;
      if (loose.isEmpty() && name.equalsIgnoreCase(title)) loose = key;
    }
    return loose;
  }

  /** A collection's members in the collection's own order. */
  static String collectionPath(String ratingKey) {
    return "/library/collections/" + ratingKey + "/children";
  }

  /**
   * The section filtered to one collection: used when the collection list does not name it. It
   * lists the same members, in the section's order rather than the collection's.
   */
  static String collectionFilterPath(String section, String tagId) {
    if (!section.matches("[0-9]+") || !tagId.matches("[0-9]+")) return "";
    return "/library/sections/" + section + "/all?collection=" + tagId;
  }

  /** A show's or season's list of children, as a library screen opens it. */
  static String childrenPath(String ratingKey) {
    return ratingKey.matches("[0-9]+") ? "/library/metadata/" + ratingKey + "/children" : "";
  }

  /**
   * Marks every episode of a show or season watched ({@code /:/scrobble}) or unwatched ({@code
   * /:/unscrobble}) for the profile whose token sends it.
   */
  static String watchedPath(String ratingKey, boolean isWatched) {
    if (!ratingKey.matches("[0-9]+")) throw new IllegalArgumentException("Invalid ratingKey");
    return (isWatched ? "/:/scrobble" : "/:/unscrobble")
        + "?identifier=com.plexapp.plugins.library&key="
        + ratingKey;
  }

  /** "Season 1" or "Orbit Nine", as the watched panel names what it changes. */
  static String watchedTitle(String type, String title, int season) {
    if (!"season".equals(type)) return title;
    if (!title.isBlank()) return title;
    return season == EpisodeLabel.UNKNOWN ? "this season" : "Season " + season;
  }

  /**
   * The watched panel's explanation: how many episodes it changes, for whom, and how many are
   * watched now. Counts Plex did not send are left out.
   */
  static String watchedMessage(String what, JSONObject facts, String profile) {
    int total = facts == null ? -1 : facts.optInt("leafCount", -1);
    int watched = facts == null ? -1 : facts.optInt("viewedLeafCount", -1);
    String episodes =
        total < 0 ? "every episode of " : total == 1 ? "the 1 episode of " : "all " + total + " episodes of ";
    StringBuilder text =
        new StringBuilder("Mark ").append(episodes).append(what).append(" for ").append(profile).append('.');
    if (total > 0 && watched >= 0)
      text.append(' ')
          .append(watched)
          .append(" of ")
          .append(total)
          .append(watched == 1 ? " is" : " are")
          .append(" watched now.");
    return text.toString();
  }
}
