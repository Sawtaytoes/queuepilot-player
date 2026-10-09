package dev.queuepilot.player;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class StoryOrderTest {
  @Test
  public void returningAfterEpisodeNineFindsTheSpecial() {
    Set<String> persisted = Set.of("orbit-9");
    StoryOrder restarted = StoryOrder.demo();
    assertEquals("demo:special-1", restarted.next(persisted).mediaKey());
    Set<String> completed = new HashSet<>(persisted);
    completed.add("orbit-ova");
    assertEquals("Episode 10", StoryOrder.demo().next(completed).label());
    assertEquals("demo:episode-10", StoryOrder.demo().next(completed).mediaKey());
  }

  @Test
  public void anotherProfileKeepsItsOwnPlace() {
    StoryOrder order = StoryOrder.demo();
    assertEquals("orbit-ova", order.next(Set.of("orbit-9")).positionKey());
    assertEquals("orbit-9", order.next(Set.of()).positionKey());
  }

  @Test
  public void repeatedMediaUsesDistinctViewingPositions() {
    StoryOrder order =
        new StoryOrder(
            List.of(
                new StoryOrder.Item("first", "plex:7", "Episode 7", "Recap", "episode"),
                new StoryOrder.Item("repeat", "plex:7", "Episode 7", "Recap", "episode")));
    assertEquals("repeat", order.next(Set.of("first")).positionKey());
    assertNull(order.next(Set.of("first", "repeat")));
  }

  @Test(expected = IllegalArgumentException.class)
  public void conflictingPositionKeysFail() {
    StoryOrder.Item item = new StoryOrder.Item("first", "plex:7", "Episode 7", "Recap", "episode");
    new StoryOrder(List.of(item, item));
  }
}
