package dev.queuepilot.player;

/** One rewind after an actual pause; seeking while paused changes the chosen resume point. */
final class RewindOnResume {
  private boolean paused;

  void pause() {
    paused = true;
  }

  long resume(long position, long floor, int seconds) {
    if (!paused) return position;
    paused = false;
    return Math.max(floor, position - Math.max(0, seconds) * 1000L);
  }

  void reset() {
    paused = false;
  }
}
