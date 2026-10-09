package dev.queuepilot.player;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** A viewing position has its own key; the provider's media identity is unchanged. */
public final class StoryOrder {
  public record Item(
      String positionKey, String mediaKey, String label, String title, String kind) {}

  private final List<Item> items;

  public StoryOrder(List<Item> items) {
    Set<String> keys = new HashSet<>();
    for (Item item : items) {
      if (item.positionKey() == null
          || item.positionKey().isBlank()
          || !keys.add(item.positionKey())) {
        throw new IllegalArgumentException("Every viewing position needs a unique key");
      }
      if (item.mediaKey() == null || item.mediaKey().isBlank())
        throw new IllegalArgumentException("Missing media identity");
    }
    this.items = List.copyOf(items);
  }

  public List<Item> items() {
    return items;
  }

  public Item next(Set<String> completedPositions) {
    for (Item item : items) if (!completedPositions.contains(item.positionKey())) return item;
    return null;
  }

  public static StoryOrder demo() {
    return new StoryOrder(
        List.of(
            new Item("orbit-9", "demo:episode-9", "Episode 9", "The signal", "episode"),
            new Item(
                "orbit-ova",
                "demo:special-1",
                "OVA · after Episode 9",
                "A day between stars",
                "special"),
            new Item("orbit-10", "demo:episode-10", "Episode 10", "Beyond the orbit", "episode")));
  }
}
