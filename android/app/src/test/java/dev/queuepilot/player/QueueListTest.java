package dev.queuepilot.player;

import static org.junit.Assert.*;

import java.util.List;
import org.junit.Test;

public class QueueListTest {
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
  public void playsAsIsInformationOnly() {
    assertEquals("Plays as Grace", QueueList.playsAs("Grace"));
    assertEquals("", QueueList.playsAs(""));
    assertEquals("", QueueList.playsAs(null));
  }

  @Test
  public void filterSummaryCountsWhatIsHidden() {
    assertEquals("Showing 2 of 24 queues", QueueList.filterSummary(2, 24));
    assertEquals("Showing 1 of 1 queue", QueueList.filterSummary(1, 1));
  }

  @Test
  public void aTitleIsAddedToEveryPicksQueueWhoeverItPlaysAs() {
    record Listed(String id, String kind) {}
    var queues =
        List.of(
            new Listed("ada-watch", "picks"),
            new Listed("movies-grace", "picks"),
            new Listed("anyone", "picks"),
            new Listed("movies-ada", "rules"));
    var targets = QueueList.addTargets(queues, Listed::kind);
    assertEquals(
        List.of("ada-watch", "movies-grace", "anyone"),
        targets.addable().stream().map(Listed::id).toList());
    assertEquals(1, targets.rules());
  }

  @Test
  public void savedQueuesAreRetriedSoonThenLessOftenThenOnlyOnANetworkOrResume() {
    assertEquals(2000, QueueList.liveRetryDelayMs(0));
    assertEquals(4000, QueueList.liveRetryDelayMs(1));
    assertEquals(8000, QueueList.liveRetryDelayMs(2));
    assertEquals(15000, QueueList.liveRetryDelayMs(3));
    assertEquals(30000, QueueList.liveRetryDelayMs(4));
    assertEquals(-1, QueueList.liveRetryDelayMs(5));
    assertEquals(-1, QueueList.liveRetryDelayMs(-1));
    // The timed retries cover the emulator's measured boot gap (Wi-Fi 23 s after the app opened).
    long total = 0;
    for (int i = 0; QueueList.liveRetryDelayMs(i) >= 0; i++) total += QueueList.liveRetryDelayMs(i);
    assertTrue(total > 23000);
  }
}
