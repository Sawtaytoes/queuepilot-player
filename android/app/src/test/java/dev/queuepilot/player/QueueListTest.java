package dev.queuepilot.player;

import static org.junit.Assert.*;

import java.util.List;
import org.junit.Test;

public class QueueListTest {
  record Queue(String id, String requiredProfile) {}

  @Test
  public void audienceMatchesTheWebText() {
    assertEquals("Anybody", QueueList.audience(List.of()));
    assertEquals("Ada", QueueList.audience(List.of("Ada")));
    assertEquals("Ada, Grace", QueueList.audience(List.of("Ada", "Grace")));
    assertEquals(
        "Ada, Grace, Linus +2",
        QueueList.audience(List.of("Ada", "Grace", "Linus", "Morgan", "Nadia")));
  }

  @Test
  public void activeProfileQueuesComeFirstInServerOrder() {
    var queues =
        List.of(
            new Queue("movies-kids", "Younger Kids"),
            new Queue("movies-ada", "Ada"),
            new Queue("anime", ""),
            new Queue("movies-demo", "Demo"),
            new Queue("shows-ada", "ada"));
    var groups = QueueList.group(queues, Queue::requiredProfile, "Ada");
    assertEquals(
        List.of("movies-ada", "anime", "shows-ada"),
        groups.playsAsActive().stream().map(Queue::id).toList());
    assertEquals(
        List.of("movies-kids", "movies-demo"),
        groups.switchesProfile().stream().map(Queue::id).toList());
  }

  @Test
  public void everyQueueIsKept() {
    var queues = List.of(new Queue("a", "Ada"), new Queue("b", "Grace"), new Queue("c", null));
    var groups = QueueList.group(queues, Queue::requiredProfile, "Grace");
    assertEquals(3, groups.playsAsActive().size() + groups.switchesProfile().size());
  }

  @Test
  public void filterSummaryCountsWhatIsHidden() {
    assertEquals("Showing 2 of 24 queues", QueueList.filterSummary(2, 24));
    assertEquals("Showing 1 of 1 queue", QueueList.filterSummary(1, 1));
  }
}
