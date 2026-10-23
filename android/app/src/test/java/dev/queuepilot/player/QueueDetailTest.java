package dev.queuepilot.player;

import static org.junit.Assert.*;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class QueueDetailTest {
  private static final ZoneId ZONE = ZoneId.of("America/Chicago");

  private static JSONObject item(String state, long duration, long offset) throws Exception {
    return new JSONObject()
        .put("type", "episode")
        .put("title", "The signal")
        .put("show", "Orbit Nine")
        .put("season", 1)
        .put("episode", 9)
        .put("state", state)
        .put("durationMs", duration)
        .put("offsetMs", offset);
  }

  private static long at(int day, int hour, int minute) {
    return ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, ZONE).toInstant().toEpochMilli();
  }

  @Test
  public void summaryCountsTheWholeQueueAndTheTimeLeft() throws Exception {
    JSONObject full =
        new JSONObject()
            .put("lineup", new JSONObject().put("count", 2).put("endPosition", 2))
            .put(
                "entries",
                new JSONArray()
                    .put(
                        new JSONObject()
                            .put(
                                "items",
                                new JSONArray()
                                    .put(item("watched", 1_440_000, 0))
                                    .put(item("in_progress", 1_440_000, 840_000))))
                    .put(
                        new JSONObject()
                            .put("items", new JSONArray().put(item("unwatched", 2_760_000, 0)))));
    QueueDetail.Summary summary = QueueDetail.summarize(full);
    assertEquals(3, summary.items());
    assertEquals(1, summary.watched());
    assertEquals(1, summary.inProgress());
    assertEquals(2, summary.lineup());
    assertEquals(600_000 + 2_760_000, summary.remainingMs());
    assertEquals("3 items · 1 watched · about 56 min left", QueueDetail.summaryLine(summary));
  }

  @Test
  public void durationsAndClocks() {
    assertEquals("1 min", QueueDetail.duration(10_000));
    assertEquals("24 min", QueueDetail.duration(1_440_000));
    assertEquals("1 h 52 min", QueueDetail.duration(6_720_000));
    assertEquals("2 h", QueueDetail.duration(7_200_000));
    assertEquals("41:10", QueueDetail.clock(2_470_000));
    assertEquals("1:02:05", QueueDetail.clock(3_725_000));
  }

  @Test
  public void itemStateNeedsNoColorToRead() throws Exception {
    assertEquals("Watched", QueueDetail.state(item("watched", 1_440_000, 0)));
    assertEquals("Next · 10 min left", QueueDetail.state(item("in_progress", 1_440_000, 840_000).put("isNext", true)));
    assertEquals("Next", QueueDetail.state(item("unwatched", 1_440_000, 0).put("isNext", true)));
    assertEquals("In progress · 10 min left", QueueDetail.state(item("in_progress", 1_440_000, 840_000)));
    assertEquals("Special", QueueDetail.state(item("unwatched", 1_080_000, 0).put("isSpecial", true)));
    assertEquals("Not watched", QueueDetail.state(item("unwatched", 1_080_000, 0)));
    JSONObject section =
        item("unwatched", 3_600_000, 0)
            .put("section", new JSONObject().put("startMs", 2_470_000).put("endMs", 3_510_000));
    assertEquals("Section 41:10 to 58:30", QueueDetail.state(section));
    assertEquals(-1f, QueueDetail.progress(item("unwatched", 1_440_000, 0)), 0f);
    assertEquals(.5f, QueueDetail.progress(item("in_progress", 1_000, 500)), .001f);
  }

  @Test
  public void episodeLinesNameTheShowAndMoviesTheirTitle() throws Exception {
    JSONObject episode = item("unwatched", 1_440_000, 0);
    assertEquals("Orbit Nine", QueueDetail.primary(episode));
    assertEquals("The signal", QueueDetail.rowTitle(episode));
    assertEquals("Orbit Nine · S1 · E9 · 24 min", QueueDetail.detail(episode));
    assertEquals(
        "Orbit Nine · Special · 18 min",
        QueueDetail.detail(item("unwatched", 1_080_000, 0).put("season", 0)));
    JSONObject movie =
        new JSONObject()
            .put("type", "movie")
            .put("title", "Glass City")
            .put("show", JSONObject.NULL)
            .put("season", JSONObject.NULL)
            .put("episode", JSONObject.NULL)
            .put("durationMs", 6_720_000);
    assertEquals("Glass City", QueueDetail.primary(movie));
    assertEquals("Glass City", QueueDetail.rowTitle(movie));
    assertEquals("1 h 52 min", QueueDetail.detail(movie));
    assertEquals("Untitled item", QueueDetail.primary(new JSONObject().put("title", JSONObject.NULL)));
  }

  @Test
  public void rulesQueuesCannotStartHereAndProviderItemsCannotBeMarked() throws Exception {
    assertNull(QueueDetail.startUnavailable(new JSONObject().put("kind", "picks")));
    assertNotNull(QueueDetail.startUnavailable(new JSONObject().put("kind", "rules")));
    assertNull(QueueDetail.markUnavailable(new JSONObject().put("canMarkWatched", true)));
    String reason = QueueDetail.markUnavailable(new JSONObject().put("canMarkWatched", false));
    assertNotNull(reason);
    assertFalse("Source-neutral wording", reason.contains("Plex"));
  }

  @Test
  public void entryNotes() throws Exception {
    assertEquals(
        "Entry 2 of 5 · Priority · Shuffled",
        QueueDetail.entryNote(
            new JSONObject().put("type", "show").put("lane", "priority").put("isShuffled", true),
            1,
            5));
    assertEquals(
        "Shuffled",
        QueueDetail.entryNote(new JSONObject().put("type", "lineup").put("isShuffled", true), 0, 1));
  }

  @Test
  public void historyDaysAndSittingSpans() {
    long now = at(23, 21, 0);
    assertEquals("Today", QueueDetail.day(at(23, 0, 5), now, ZONE));
    assertEquals("Yesterday", QueueDetail.day(at(22, 23, 59), now, ZONE));
    assertEquals("Tuesday, October 20", QueueDetail.day(at(20, 22, 10), now, ZONE));
    assertEquals("today", QueueDetail.lastWatched(at(23, 20, 0), now, ZONE));
    assertEquals("Tuesday, October 20", QueueDetail.lastWatched(at(20, 22, 10), now, ZONE));
    assertEquals(
        "Today · 7:42 PM to 8:44 PM",
        QueueDetail.sittingSpan(at(23, 19, 42), at(23, 20, 44), false, now, ZONE));
    assertEquals(
        "Yesterday · about 8:05 PM to 8:43 PM",
        QueueDetail.sittingSpan(at(22, 20, 5), at(22, 20, 43), true, now, ZONE));
  }

  @Test
  public void historyItemsAndFooters() throws Exception {
    JSONObject partial =
        new JSONObject()
            .put("type", "episode")
            .put("title", "The signal")
            .put("show", "Orbit Nine")
            .put("season", 1)
            .put("episode", 9)
            .put("state", "partial")
            .put("positionMs", 893_000)
            .put("durationMs", 1_440_000)
            .put("source", "native-tv");
    assertEquals("Orbit Nine · S1 · E9 · The signal · 62%", QueueDetail.historyItem(partial));
    assertEquals("", QueueDetail.source(partial));
    JSONObject finished =
        new JSONObject()
            .put("type", "movie")
            .put("title", "The Last Beacon")
            .put("state", "finished")
            .put("source", "queue-ledger");
    assertEquals("The Last Beacon", QueueDetail.historyItem(finished));
    assertEquals("Recorded by the queue", QueueDetail.source(finished));
    JSONObject sitting =
        new JSONObject()
            .put("startedAt", at(23, 19, 42))
            .put("endedAt", at(23, 20, 40))
            .put("items", new JSONArray().put(partial).put(finished.put("isTimeApproximate", true)));
    assertEquals("2 items · 58 min", QueueDetail.sittingFooter(sitting));
    assertTrue(QueueDetail.isApproximate(sitting));
  }
}
