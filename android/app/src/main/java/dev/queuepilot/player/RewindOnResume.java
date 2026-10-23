package dev.queuepilot.player;

/** One rewind after an actual pause; seeking while paused changes the chosen resume point. */
final class RewindOnResume {
  /** Off unless the viewer chooses an amount in Settings; a stored choice always wins. */
  static final int DEFAULT_SECONDS = 0;

  static final String PREFERENCE_KEY = "rewind-seconds";

  private boolean paused;

  static String label(int seconds) {
    if (seconds <= 0) return "Off";
    return seconds == 1 ? "1 second" : seconds + " seconds";
  }

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
