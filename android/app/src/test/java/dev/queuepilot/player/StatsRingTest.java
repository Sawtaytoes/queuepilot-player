package dev.queuepilot.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class StatsRingTest {
  @Test
  public void keepsTheLatestSamplesOldestFirst() {
    StatsRing ring = new StatsRing(3);
    for (int value = 1; value <= 5; value++) ring.add(value);
    assertEquals(3, ring.size());
    assertEquals(3f, ring.get(0), 0);
    assertEquals(4f, ring.get(1), 0);
    assertEquals(5f, ring.get(2), 0);
    assertEquals(5f, ring.latest(), 0);
    assertEquals(3f, ring.ago(2), 0);
    assertTrue(Float.isNaN(ring.ago(3)));
  }

  @Test
  public void unknownSamplesAreGapsThatTheMeanAndMaximumSkip() {
    StatsRing ring = new StatsRing(60);
    ring.add(2);
    ring.add(Float.NaN);
    ring.add(4);
    assertEquals(3f, ring.mean(60), 0);
    assertEquals(4f, ring.mean(1), 0);
    assertEquals(4f, ring.max(), 0);
    assertTrue(Float.isNaN(ring.get(1)));
  }

  @Test
  public void anEmptyRingHasNoValues() {
    StatsRing ring = new StatsRing(4);
    assertTrue(Float.isNaN(ring.latest()));
    assertTrue(Float.isNaN(ring.mean(4)));
    assertTrue(Float.isNaN(ring.max()));
    ring.add(1);
    ring.clear();
    assertEquals(0, ring.size());
  }

  @Test(expected = IndexOutOfBoundsException.class)
  public void readingPastTheLatestFails() {
    StatsRing ring = new StatsRing(4);
    ring.add(1);
    ring.get(1);
  }
}
