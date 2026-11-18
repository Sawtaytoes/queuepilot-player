package dev.queuepilot.player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A title's extras ({@code /library/metadata/<key>/extras}), one row per kind instead of one long
 * line: Trailers, Behind the scenes, Deleted scenes, Featurettes, Interviews, Scenes, Shorts, and
 * Other. Plex names a kind twice, as a number ({@code extraType}) and a word ({@code subtype}); the
 * number wins when this version knows it.
 */
final class TitleExtras {
  private TitleExtras() {}

  /** One kind of extra: its row heading, and the word a single item is called by. */
  record Kind(String id, String heading, String singular) {}

  static final Kind TRAILER = new Kind("trailer", "Trailers", "Trailer");
  static final Kind BEHIND_THE_SCENES =
      new Kind("behindTheScenes", "Behind the scenes", "Behind the scenes");
  static final Kind DELETED_SCENE = new Kind("deletedScene", "Deleted scenes", "Deleted scene");
  static final Kind FEATURETTE = new Kind("featurette", "Featurettes", "Featurette");
  static final Kind INTERVIEW = new Kind("interview", "Interviews", "Interview");
  static final Kind SCENE = new Kind("scene", "Scenes", "Scene");
  static final Kind SHORT = new Kind("short", "Shorts", "Short");
  static final Kind OTHER = new Kind("other", "Other", "Extra");

  /** The rows, in the order the title screen shows them. */
  static final List<Kind> KINDS =
      List.of(
          TRAILER, BEHIND_THE_SCENES, DELETED_SCENE, FEATURETTE, INTERVIEW, SCENE, SHORT, OTHER);

  /**
   * Plex's numbered extra types: 1 trailer, 2 deleted scene, 3 interview, 4 music video, 5 behind
   * the scenes, 6 scene or sample, 7 live music video, 8 lyric music video, 9 concert, 10
   * featurette, 11 short, 12 other. The music kinds have no row of their own here.
   */
  static Kind kind(String extraType, String subtype) {
    switch (extraType == null ? "" : extraType.trim()) {
      case "1":
        return TRAILER;
      case "2":
        return DELETED_SCENE;
      case "3":
        return INTERVIEW;
      case "5":
        return BEHIND_THE_SCENES;
      case "6":
        return SCENE;
      case "10":
        return FEATURETTE;
      case "11":
        return SHORT;
      case "4", "7", "8", "9", "12":
        return OTHER;
      default:
        break;
    }
    String word = subtype == null ? "" : subtype.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
    return switch (word) {
      case "trailer", "trailers", "teaser" -> TRAILER;
      case "behindthescenes", "bts" -> BEHIND_THE_SCENES;
      case "deleted", "deletedscene", "deletedscenes" -> DELETED_SCENE;
      case "featurette", "featurettes" -> FEATURETTE;
      case "interview", "interviews" -> INTERVIEW;
      case "scene", "scenes", "sceneorsample", "sample" -> SCENE;
      case "short", "shorts" -> SHORT;
      default -> OTHER;
    };
  }

  static Kind kind(PlexCatalog.Entry entry) {
    return kind(entry.facts().optString("extraType"), entry.facts().optString("subtype"));
  }

  /** One row of extras of a single kind, in the order Plex listed them. */
  record Group(Kind kind, List<PlexCatalog.Entry> items) {}

  /** The extras in rows by kind, in {@link #KINDS} order; an empty kind has no row. */
  static List<Group> group(List<PlexCatalog.Entry> extras) {
    Map<Kind, List<PlexCatalog.Entry>> byKind = new LinkedHashMap<>();
    for (Kind kind : KINDS) byKind.put(kind, new ArrayList<>());
    for (PlexCatalog.Entry extra : extras) byKind.get(kind(extra)).add(extra);
    List<Group> groups = new ArrayList<>();
    for (Map.Entry<Kind, List<PlexCatalog.Entry>> row : byKind.entrySet())
      if (!row.getValue().isEmpty()) groups.add(new Group(row.getKey(), List.copyOf(row.getValue())));
    return groups;
  }

  /** The extra that Play trailer starts: the first trailer, or null when there is none. */
  static PlexCatalog.Entry trailer(List<Group> groups) {
    for (Group group : groups)
      if (group.kind() == TRAILER && !group.items().isEmpty()) return group.items().get(0);
    return null;
  }

  /** "Trailer, Official trailer": what a card is called, by kind and title. */
  static String spoken(Kind kind, String title) {
    return title == null || title.isBlank() ? kind.singular() : kind.singular() + ", " + title;
  }
}
