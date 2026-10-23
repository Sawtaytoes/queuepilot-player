package dev.queuepilot.player;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * Plex often keeps an episode's cast on its show: an anime episode can carry no Role entries
 * while the show lists every actor. The Cast dialog shows the episode's own credits first, then
 * the season's and the show's, once each.
 */
final class TitleCast {
  private TitleCast() {}

  /** Metadata keys whose credits complete an episode's cast, nearest first. */
  static List<String> inheritedKeys(String type, String seasonKey, String showKey) {
    List<String> keys = new ArrayList<>();
    if (!"episode".equals(type)) return keys;
    for (String key : new String[] {seasonKey, showKey})
      if (key != null && key.matches("[0-9]+") && !keys.contains(key)) keys.add(key);
    return keys;
  }

  /** One credit per person and kind: a guest actor and the same actor on the show collapse. */
  static String identity(String kind, String name) {
    return (kind == null || kind.isEmpty() ? "Role" : kind)
        + '\n'
        + (name == null ? "" : name.trim().toLowerCase(Locale.ROOT));
  }

  static <T> List<T> distinct(List<T> ordered, Function<T, String> identity) {
    LinkedHashMap<String, T> kept = new LinkedHashMap<>();
    for (T item : ordered) kept.putIfAbsent(identity.apply(item), item);
    return new ArrayList<>(kept.values());
  }

  /** An external portrait that may be fetched without credentials, or through the server. */
  static boolean isExternalPhoto(String thumb) {
    try {
      URI uri = URI.create(thumb);
      return "https".equals(uri.getScheme())
          && uri.getHost() != null
          && uri.getUserInfo() == null
          && (uri.getPort() == -1 || uri.getPort() == 443);
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * The connected server's image transcoder resizes both its own library paths and external
   * portrait URLs, so a portrait CDN is reached by the server rather than by the TV.
   */
  static String transcodePath(String thumb, int width) {
    boolean isLibraryPath = thumb.startsWith("/") && !thumb.startsWith("//");
    if (!isLibraryPath && !isExternalPhoto(thumb))
      throw new IllegalArgumentException("Invalid artwork path");
    return "/photo/:/transcode?width="
        + width
        + "&height="
        + (width * 3 / 2)
        + "&minSize=1&upscale=0&url="
        + PlexAccount.encode(thumb);
  }
}
