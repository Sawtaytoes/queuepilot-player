package dev.queuepilot.player;

import static org.junit.Assert.*;

import org.junit.Test;

public class DisplaySizeTest {
  @Test
  public void defaultIsAutoForA55InchScreenAt9Feet() {
    assertEquals(DisplaySize.AUTO, DisplaySize.DEFAULT_PERCENT);
    assertEquals(55f, DisplaySize.DEFAULT_SCREEN_INCHES, 0f);
    assertEquals(9f, DisplaySize.DEFAULT_DISTANCE_FEET, 0f);
    assertEquals(95, DisplaySize.percent(DisplaySize.AUTO, 55, 9));
    assertEquals("Auto · 95%", DisplaySize.label(DisplaySize.AUTO, 55, 9));
    assertEquals("100%", DisplaySize.label(100, 97, 6.5f));
  }

  @Test
  public void autoShrinksALargeScreenSeenFromCloseBy() {
    // 97 in at 6.5 ft spans about 57 degrees, against 22.6 for 55 in at 10 ft.
    assertEquals(56.9, DisplaySize.angle(97, 6.5), 0.05);
    assertEquals(22.6, DisplaySize.angle(55, 10), 0.05);
    assertEquals(65, DisplaySize.autoPercent(97, 6.5f));
    assertEquals("Auto · 65%", DisplaySize.label(DisplaySize.AUTO, 97, 6.5f));
    assertEquals(100, DisplaySize.autoPercent(55, 10));
    assertEquals(90, DisplaySize.autoPercent(65, 10));
    assertEquals(75, DisplaySize.autoPercent(77, 8));
    assertEquals(115, DisplaySize.autoPercent(43, 10));
  }

  @Test
  public void autoStaysWithinFiftyAndOneHundredTwentyFivePercent() {
    assertEquals(50, DisplaySize.autoPercent(150, 2));
    assertEquals(125, DisplaySize.autoPercent(20, 30));
  }

  @Test
  public void aScreenOrDistanceOutOfRangeReadsAsTheDefault() {
    assertEquals(55f, DisplaySize.screenInches(0), 0f);
    assertEquals(55f, DisplaySize.screenInches(500), 0f);
    assertEquals(9f, DisplaySize.distanceFeet(-1), 0f);
    assertEquals(95, DisplaySize.autoPercent(0, 0));
  }

  @Test
  public void manualChoicesRunFromFiftyToOneHundredTwentyFiveInFives() {
    assertEquals(16, DisplaySize.CHOICES.length);
    assertEquals(50, DisplaySize.CHOICES[0]);
    assertEquals(125, DisplaySize.CHOICES[DisplaySize.CHOICES.length - 1]);
    assertEquals(65, DisplaySize.percent(65, 55, 9));
    assertEquals(0.65f, DisplaySize.scale(65), 0f);
  }

  @Test
  public void unknownStoredValuesFallBackToAuto() {
    assertEquals(DisplaySize.AUTO, DisplaySize.valid(0));
    assertEquals(DisplaySize.AUTO, DisplaySize.valid(97));
    assertEquals(DisplaySize.AUTO, DisplaySize.valid(500));
    assertEquals(95, DisplaySize.valid(95));
    // The sizes 0.5.2 to 0.5.6 stored are still choices.
    for (int earlier : new int[] {70, 80, 90, 100, 110, 125})
      assertEquals(earlier, DisplaySize.valid(earlier));
  }

  @Test
  public void numbersDropAWholeNumbersDecimal() {
    assertEquals("97 in", DisplaySize.screenLabel(97));
    assertEquals("6.5 ft", DisplaySize.distanceLabel(6.5f));
    assertEquals("9 ft", DisplaySize.distanceLabel(9));
  }

  @Test
  public void smallerSizesGainColumns() {
    assertEquals(5, DisplaySize.columns(5, 100));
    assertEquals(7, DisplaySize.columns(5, 70));
    assertEquals(8, DisplaySize.columns(5, 65));
    assertEquals(10, DisplaySize.columns(5, 50));
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
