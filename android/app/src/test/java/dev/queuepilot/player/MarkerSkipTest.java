package dev.queuepilot.player;

import static org.junit.Assert.*;

import java.util.List;
import org.junit.Test;

public class MarkerSkipTest {
  private static final MarkerSkip.Marker INTRO = new MarkerSkip.Marker("intro", 6000, 14000, false);
  private static final MarkerSkip.Marker MID_CREDITS =
      new MarkerSkip.Marker("credits", 18000, 21000, false);
  private static final MarkerSkip.Marker FINAL_CREDITS =
      new MarkerSkip.Marker("credits", 25000, 30000, true);
  private static final List<MarkerSkip.Marker> MARKERS =
      List.of(INTRO, MID_CREDITS, FINAL_CREDITS);

  @Test
  public void offersASkipOnlyInsideAMarker() {
    assertNull(MarkerSkip.active(MARKERS, 5999, 0, -1));
    assertEquals(INTRO, MarkerSkip.active(MARKERS, 6000, 0, -1));
    assertEquals(INTRO, MarkerSkip.active(MARKERS, 13999, 0, -1));
    assertNull(MarkerSkip.active(MARKERS, 14000, 0, -1));
    assertEquals(MID_CREDITS, MarkerSkip.active(MARKERS, 19000, 0, -1));
    assertEquals("Skip Intro", MarkerSkip.label(INTRO));
    assertEquals("Skip Credits", MarkerSkip.label(MID_CREDITS));
  }

  @Test
  public void ignoresOtherAndEmptyMarkers() {
    List<MarkerSkip.Marker> odd =
        List.of(
            new MarkerSkip.Marker("commercial", 0, 9000, false),
            new MarkerSkip.Marker("intro", 5000, 5000, false));
    assertNull(MarkerSkip.active(odd, 5000, 0, -1));
  }

  @Test
  public void introAndMidCreditsSeekToTheMarkerEnd() {
    assertEquals(14000, MarkerSkip.target(INTRO, 30000, 0, -1));
    assertFalse(MarkerSkip.completesItem(MID_CREDITS, 30000));
    assertEquals(21000, MarkerSkip.target(MID_CREDITS, 30000, 0, -1));
  }

  @Test
  public void finalCreditsCompleteTheItem() {
    assertTrue(MarkerSkip.completesItem(FINAL_CREDITS, 30000));
    assertEquals(30000, MarkerSkip.target(FINAL_CREDITS, 30000, 0, -1));
    var nearEnd = new MarkerSkip.Marker("credits", 25000, 28500, false);
    assertTrue(MarkerSkip.completesItem(nearEnd, 30000));
    assertEquals(30000, MarkerSkip.target(nearEnd, 30000, 0, -1));
    var notNearEnd = new MarkerSkip.Marker("credits", 25000, 27900, false);
    assertFalse(MarkerSkip.completesItem(notNearEnd, 30000));
    assertFalse(MarkerSkip.completesItem(INTRO, 14000));
  }

  @Test
  public void unknownDurationUsesTheMarkerEnd() {
    assertFalse(MarkerSkip.completesItem(MID_CREDITS, -1));
    assertEquals(21000, MarkerSkip.target(MID_CREDITS, -1, 0, -1));
    assertEquals(30000, MarkerSkip.target(FINAL_CREDITS, -1, 0, -1));
  }

  @Test
  public void authoredSectionsBoundTheSkip() {
    // A section ending inside the intro: the skip lands on the section end, never past it.
    assertEquals(10000, MarkerSkip.target(INTRO, 30000, 0, 10000));
    // Final credits inside a section finish the section, not the whole file.
    assertEquals(27000, MarkerSkip.target(FINAL_CREDITS, 30000, 0, 27000));
    // No skip is offered outside the section.
    assertNull(MarkerSkip.active(MARKERS, 7000, 8000, -1));
    assertNull(MarkerSkip.active(MARKERS, 19000, 0, 18500));
    assertEquals(INTRO, MarkerSkip.active(MARKERS, 9000, 8000, 20000));
  }
}
