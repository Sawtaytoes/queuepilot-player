package dev.queuepilot.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DroppedFrameCounterTest {
  @Test
  public void samplesOfOneRendererAddTheirIncrease() {
    DroppedFrameCounter drops = new DroppedFrameCounter();
    Object counters = new Object();
    assertTrue(drops.sample(counters, 5));
    assertFalse(drops.sample(counters, 7));
    assertEquals(7, drops.total());
  }

  @Test
  public void aNewRendererCountsInFullEvenAboveTheOldCount() {
    DroppedFrameCounter drops = new DroppedFrameCounter();
    drops.sample(new Object(), 5);
    assertTrue(drops.sample(new Object(), 7));
    assertEquals(12, drops.total());
  }

  @Test
  public void dropsAfterTheLastSampleCountWhenTheRendererIsDisabled() {
    DroppedFrameCounter drops = new DroppedFrameCounter();
    Object old = new Object();
    drops.sample(old, 5);
    drops.disabled(old, 8); // 3 more after the last sample
    drops.sample(null, 0);
    drops.sample(new Object(), 7);
    assertEquals(15, drops.total());
  }

  @Test
  public void aRendererEnabledAndDisabledBetweenSamplesCounts() {
    DroppedFrameCounter drops = new DroppedFrameCounter();
    Object first = new Object();
    drops.sample(first, 5);
    drops.disabled(first, 5);
    drops.disabled(new Object(), 4); // never sampled
    drops.sample(new Object(), 2);
    assertEquals(11, drops.total());
  }

  @Test
  public void aDisabledRenderersFinalCountIsNotAddedTwice() {
    DroppedFrameCounter drops = new DroppedFrameCounter();
    Object old = new Object();
    drops.sample(old, 5);
    drops.disabled(old, 8);
    drops.sample(old, 8); // a sample before Media3 lets go of the old counters
    drops.disabled(old, 8);
    assertEquals(8, drops.total());
  }

  @Test
  public void finalCountsWhileSamplingIsStoppedCount() {
    DroppedFrameCounter drops = new DroppedFrameCounter();
    Object first = new Object(), second = new Object();
    drops.sample(first, 2);
    // Sampling stops (the readout is hidden); the renderer drops more and is replaced twice.
    drops.disabled(first, 6);
    drops.disabled(second, 3);
    drops.sample(new Object(), 1);
    assertEquals(10, drops.total());
  }
}
