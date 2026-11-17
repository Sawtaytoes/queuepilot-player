package dev.queuepilot.player;

import static org.junit.Assert.*;

import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** Skip intervals, the resume question, chapters, and frame-rate matching. */
public class PlaybackRulesTest {
  @Test
  public void forwardSkipFollowsTheItemsLength() {
    long fiftyNineMinutes = 59 * 60 * 1000L, hour = 60 * 60 * 1000L;
    assertEquals(10000, SkipIntervals.forwardMs(fiftyNineMinutes, 10, 30));
    assertEquals(30000, SkipIntervals.forwardMs(hour, 10, 30));
    assertEquals(30000, SkipIntervals.forwardMs(2 * hour, 10, 30));
    // An unknown length uses the shorter skip.
    assertEquals(10000, SkipIntervals.forwardMs(-1, 10, 30));
    assertEquals(10000, SkipIntervals.backMs(SkipIntervals.DEFAULT_BACK));
  }

  @Test
  public void storedSkipAmountsAreKeptToTheOfferedChoices() {
    assertEquals(15, SkipIntervals.valid(15));
    assertEquals(5, SkipIntervals.valid(0));
    assertEquals(60, SkipIntervals.valid(600));
    assertEquals(1, SkipIntervals.choiceIndex(10));
    assertEquals("30 seconds", SkipIntervals.label(30));
    assertEquals(SkipIntervals.CHOICES.length, SkipIntervals.choiceLabels().length);
  }

  @Test
  public void asksOnlyAboutAMeaningfulSavedPosition() {
    assertTrue(ResumeChoice.shouldAsk(754000, 0, 1440000));
    assertFalse(ResumeChoice.shouldAsk(0, 0, 1440000));
    assertFalse(ResumeChoice.shouldAsk(500, 0, 1440000));
    assertFalse(ResumeChoice.shouldAsk(1440000, 0, 1440000));
    assertTrue(ResumeChoice.shouldAsk(754000, 0, -1));
    // A queue section starting at 41:10 is the item's beginning.
    assertFalse(ResumeChoice.shouldAsk(2470000, 2470000, 3600000));
    assertTrue(ResumeChoice.shouldAsk(2600000, 2470000, 3600000));
  }

  @Test
  public void resumeHonorsRewindAndTheSectionStart() {
    assertEquals(754000, ResumeChoice.start(true, 754000, 0, 0));
    assertEquals(744000, ResumeChoice.start(true, 754000, 0, 10));
    assertEquals(0, ResumeChoice.start(false, 754000, 0, 10));
    assertEquals(2470000, ResumeChoice.start(false, 2600000, 2470000, 3));
    assertEquals(2470000, ResumeChoice.start(true, 2475000, 2470000, 10));
    assertEquals(500, ResumeChoice.unasked(500, 0, 1440000));
    assertEquals(0, ResumeChoice.unasked(1440000, 0, 1440000));
    assertEquals(2470000, ResumeChoice.unasked(0, 2470000, 3600000));
  }

  @Test
  public void namesTheResumeTime() {
    assertEquals("Resume from 12:34", ResumeChoice.resumeLabel(754000));
    assertEquals("Resume from 0:04", ResumeChoice.resumeLabel(4000));
    assertEquals("Resume from 1:02:03", ResumeChoice.resumeLabel(3723000));
  }

  @Test
  public void readsChaptersInTimeOrder() throws Exception {
    JSONArray array =
        new JSONArray()
            .put(new JSONObject().put("title", "Ending").put("index", 3).put("start", 20000).put("end", 30000).put("thumb", ""))
            .put(new JSONObject().put("title", "").put("index", 1).put("start", 0).put("end", 10000).put("thumb", "/library/media/7/chapterImages/1"))
            .put(new JSONObject().put("title", "Middle").put("index", 2).put("start", 10000).put("end", 20000))
            .put(new JSONObject().put("title", "Broken").put("start", -1));
    List<Chapters.Chapter> chapters = Chapters.fromJson(array);
    assertEquals(3, chapters.size());
    assertEquals("Chapter 1", chapters.get(0).title());
    assertEquals("/library/media/7/chapterImages/1", chapters.get(0).thumb());
    assertEquals("0:10 · Middle", Chapters.label(chapters.get(1)));
    assertEquals(-1, Chapters.current(List.of(chapters.get(1)), 5000));
    assertEquals(1, Chapters.current(chapters, 15000));
    assertEquals(2, Chapters.current(chapters, 20000));
    assertEquals(0, Chapters.fromJson(null).size());
  }

  @Test
  public void chapterSeeksStayInsideAQueueSection() {
    assertEquals(10000, Chapters.seekTarget(10000, 0, -1));
    assertEquals(2470000, Chapters.seekTarget(0, 2470000, 3510000));
    assertEquals(3510000, Chapters.seekTarget(3600000, 2470000, 3510000));
  }

  private static FrameRateMatch.Mode mode(int id, float rate) {
    return new FrameRateMatch.Mode(id, 3840, 2160, rate);
  }

  @Test
  public void matchesTheFrameRateExactlyOrByAWholeMultiple() {
    List<FrameRateMatch.Mode> modes =
        List.of(
            mode(1, 60f),
            mode(2, 59.94f),
            mode(3, 23.976f),
            mode(4, 24f),
            mode(5, 50f),
            new FrameRateMatch.Mode(6, 1920, 1080, 23.976f));
    FrameRateMatch.Mode current = modes.get(0);
    assertEquals(3, FrameRateMatch.choose(modes, current, 23.976));
    assertEquals(4, FrameRateMatch.choose(modes, current, 24));
    assertEquals(5, FrameRateMatch.choose(modes, current, 25));
    // 60 Hz already shows 30 fps evenly: no switch.
    assertEquals(1, FrameRateMatch.choose(modes, current, 30));
    assertEquals(2, FrameRateMatch.choose(modes, current, 29.97));
    // No 4K mode for 23.976 at 47.95 Hz, and 24 Hz is 0.1% off.
    assertEquals(0, FrameRateMatch.choose(List.of(mode(1, 60f), mode(4, 24f)), current, 23.976));
    assertEquals(0, FrameRateMatch.choose(modes, current, 0));
    assertEquals(0, FrameRateMatch.choose(modes, null, 24));
  }

  @Test
  public void prefersTheLowestMultiple() {
    assertEquals(
        3, FrameRateMatch.choose(List.of(mode(1, 60f), mode(2, 47.952f), mode(3, 23.976f)), mode(1, 60f), 23.976));
    assertEquals(2, FrameRateMatch.multiple(47.952, 23.976));
    assertEquals(0, FrameRateMatch.multiple(24, 23.976));
    assertEquals(0, FrameRateMatch.multiple(60, 23.976));
  }
}
