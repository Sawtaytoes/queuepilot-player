package dev.queuepilot.player;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Queue titles repeat ("Movies" for several audiences), so a queue is told apart by who it is for
 * and which Plex profile it plays as. Every queue is listed: the ones that play as the active
 * profile first, then the ones that switch profile, each in the server's order.
 */
final class QueueList {
  private QueueList() {}

  /** The web app's text form of a queue's people: "Ada, Grace", "+n" after three. */
  static String audience(List<String> names) {
    if (names.isEmpty()) return "Anybody";
    if (names.size() <= 3) return String.join(", ", names);
    return String.join(", ", names.subList(0, 3)) + " +" + (names.size() - 3);
  }

  record Groups<T>(List<T> playsAsActive, List<T> switchesProfile) {}

  /** A queue with no required profile plays as whoever is watching. */
  static boolean playsAs(String requiredProfile, String activeProfile) {
    if (requiredProfile == null || requiredProfile.isBlank()) return true;
    String active = activeProfile == null ? "" : activeProfile.trim();
    return requiredProfile.trim().equalsIgnoreCase(active);
  }

  static <T> Groups<T> group(
      List<T> queues, Function<T, String> requiredProfile, String activeProfile) {
    List<T> own = new ArrayList<>(), other = new ArrayList<>();
    for (T queue : queues)
      (playsAs(requiredProfile.apply(queue), activeProfile) ? own : other).add(queue);
    return new Groups<>(own, other);
  }

  /** Shown only while a people filter is active, so a filter never hides queues silently. */
  static String filterSummary(int shown, int total) {
    return "Showing " + shown + " of " + total + (total == 1 ? " queue" : " queues");
  }
}
