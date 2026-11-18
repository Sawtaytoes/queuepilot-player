package dev.queuepilot.player;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

/** Only one stats object samples at a time, and a released player's stats stop for good. */
@androidx.media3.common.util.UnstableApi
public class PlaybackStatsSamplingTest {
  private final PlaybackStats first = new PlaybackStats(), next = new PlaybackStats();

  @After
  public void releaseBoth() {
    first.release();
    next.release();
  }

  @Test
  public void theNextItemsStatsStopThePreviousItemsSampler() {
    assertTrue(first.claimSampling());
    assertTrue(next.claimSampling());
    assertFalse("The previous item's sampler stops as the next one starts", first.isSampling());
    assertTrue(next.isSampling());
    // A late stop from the old readout leaves the new sampler alone.
    first.stop();
    assertTrue(next.isSampling());
  }

  @Test
  public void aReleasedPlayersStatsStopAndNeverStartAgain() {
    assertTrue(first.claimSampling());
    first.onPlayerReleased(null); // a queue moving on releases the old player first
    assertFalse(first.isSampling());
    assertFalse("A stale readout cannot restart it", first.claimSampling());
    assertTrue(next.claimSampling());
    assertTrue(next.isSampling());
  }
}
