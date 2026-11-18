package dev.queuepilot.player;

import androidx.media3.common.Player;

/**
 * Rebuffers for {@link PlaybackStats}: the player waiting for data after it was ready and playing,
 * other than after a seek, since a seek's wait is the viewer's choice, not the connection's. A
 * pause or a seek ends one. Main thread, from Media3's events.
 */
final class RebufferCounter {
  private int playbackState = Player.STATE_IDLE;
  private boolean playWhenReady, isSeeking;
  private int count;
  private long totalMs, startedAt = -1;

  void playbackStateChanged(int state, long nowMs) {
    if (state == Player.STATE_BUFFERING
        && playbackState == Player.STATE_READY
        && playWhenReady
        && !isSeeking) {
      count++;
      startedAt = nowMs;
    } else if (state != Player.STATE_BUFFERING) end(nowMs);
    if (state == Player.STATE_READY) isSeeking = false;
    playbackState = state;
  }

  void playWhenReadyChanged(boolean playWhenReady, long nowMs) {
    this.playWhenReady = playWhenReady;
    if (!playWhenReady) end(nowMs);
  }

  void seeked(long nowMs) {
    isSeeking = true;
    end(nowMs);
  }

  private void end(long nowMs) {
    if (startedAt < 0) return;
    totalMs += Math.max(0, nowMs - startedAt);
    startedAt = -1;
  }

  boolean isRebuffering() {
    return startedAt >= 0;
  }

  int count() {
    return count;
  }

  /** Time spent rebuffering, including a rebuffer still going on at {@code nowMs}. */
  long ms(long nowMs) {
    return totalMs + (startedAt >= 0 ? Math.max(0, nowMs - startedAt) : 0);
  }
}
