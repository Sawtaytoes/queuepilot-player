package dev.queuepilot.player;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Queue titles repeat ("Movies" for several audiences), so a queue is told apart by who it is for.
 * Every queue is listed in the server's order. Nobody switches profile to use one: the server
 * plays each queue as the profile it names, so "Plays as" is shown only as information.
 */
final class QueueList {
  private QueueList() {}

  /** The web app's text form of a queue's people: "Ada, Grace", "+n" after three. */
  static String audience(List<String> names) {
    if (names.isEmpty()) return "Anybody";
    if (names.size() <= 3) return String.join(", ", names);
    return String.join(", ", names.subList(0, 3)) + " +" + (names.size() - 3);
  }

  /** The small secondary line under a queue: "Plays as Ada", or empty when it is not known. */
  static String playsAs(String name) {
    return name == null || name.isBlank() ? "" : "Plays as " + name.trim();
  }

  /**
   * Where a title can be added: every Picks queue. A Rules queue chooses its own titles, so it is
   * only counted, for the panel to explain. A locked queue stays a target; adding to it asks for
   * the TV PIN, like opening it does.
   */
  record AddTargets<T>(List<T> addable, int rules) {}

  static <T> AddTargets<T> addTargets(List<T> queues, Function<T, String> kind) {
    List<T> addable = new ArrayList<>();
    int rules = 0;
    for (T queue : queues) {
      if ("rules".equals(kind.apply(queue))) rules++;
      else addable.add(queue);
    }
    return new AddTargets<>(addable, rules);
  }

  /** Shown only while a people filter is active, so a filter never hides queues silently. */
  static String filterSummary(int shown, int total) {
    return "Showing " + shown + " of " + total + (total == 1 ? " queue" : " queues");
  }
}
