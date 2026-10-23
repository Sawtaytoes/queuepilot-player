package dev.queuepilot.player;

import static org.junit.Assert.*;

import org.junit.Test;

public class DisplaySizeTest {
  @Test
  public void defaultIsOneHundredPercent() {
    assertEquals(100, DisplaySize.DEFAULT_PERCENT);
    assertEquals(1f, DisplaySize.scale(DisplaySize.DEFAULT_PERCENT), 0f);
    assertEquals("100%", DisplaySize.label(100));
  }

  @Test
  public void unknownStoredValuesFallBackToTheDefault() {
    assertEquals(100, DisplaySize.valid(0));
    assertEquals(100, DisplaySize.valid(95));
    assertEquals(70, DisplaySize.valid(70));
    assertEquals(1f, DisplaySize.scale(500), 0f);
  }

  @Test
  public void smallerSizesGainColumns() {
    assertEquals(5, DisplaySize.columns(5, 100));
    assertEquals(7, DisplaySize.columns(5, 70));
    assertEquals(6, DisplaySize.columns(5, 80));
    assertEquals(4, DisplaySize.columns(5, 125));
    assertEquals(4, DisplaySize.columns(3, 70));
    assertEquals(2, DisplaySize.columns(3, 125));
    assertTrue(DisplaySize.columns(5, 70) > DisplaySize.columns(5, 100));
  }

  @Test
  public void neverFewerThanTwoColumns() {
    assertEquals(2, DisplaySize.columns(1, 125));
  }

  @Test
  public void itemWidthMatchesTheOriginalFiveColumnGrid() {
    // 0.5.1 used (mainWidth - 76) / 5: a 14 dp gap after each card and 6 dp of slack.
    assertEquals((1038 - 76) / 5, DisplaySize.itemWidth(1038, 5, 14));
    assertEquals((1038 - 48) / 3, DisplaySize.itemWidth(1038, 3, 14));
  }
}
