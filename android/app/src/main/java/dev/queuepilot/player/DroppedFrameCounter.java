package dev.queuepilot.player;

import androidx.annotation.Nullable;

/**
 * Video frames dropped over a player's life, for {@link PlaybackStats}, from Media3's counters.
 *
 * <p>Media3 gives the video renderer a new counters object each time it is enabled, counting again
 * from zero, and keeps that object when it re-creates the decoder. Each object's count is added
 * once: by the samples while it is current, and by its final count when the renderer is disabled,
 * so frames dropped after the last sample, or by a renderer enabled and disabled between two
 * samples, still count. Objects are compared by identity only. Main thread.
 */
final class DroppedFrameCounter {
  @Nullable private Object counters;
  /** The part of {@link #counters}' count already added. */
  private long counted;

  private long total;

  /**
   * A sample of the current counters (null while the renderer is disabled) and their dropped
   * count. Returns whether the counters are new since the last call.
   */
  boolean sample(@Nullable Object current, long dropped) {
    boolean isNew = follow(current);
    add(dropped);
    return isNew;
  }

  /** The renderer was disabled; {@code dropped} is these counters' final count. */
  void disabled(Object finalCounters, long dropped) {
    follow(finalCounters);
    add(dropped);
  }

  long total() {
    return total;
  }

  private boolean follow(@Nullable Object current) {
    if (current == counters) return false;
    counters = current;
    counted = 0;
    return true;
  }

  private void add(long dropped) {
    if (dropped <= counted) return;
    total += dropped - counted;
    counted = dropped;
  }
}
