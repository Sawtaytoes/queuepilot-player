package dev.queuepilot.player;

/**
 * How far the player's skip buttons and the remote's rewind and fast-forward keys move. Forward
 * depends on the item's length, not its kind: under an hour it skips the shorter amount, an hour or
 * longer the longer one. Back is one amount for everything. All three are chosen in Settings.
 */
final class SkipIntervals {
  static final String FORWARD_SHORT_KEY = "skip-forward-short-seconds";
  static final String FORWARD_LONG_KEY = "skip-forward-long-seconds";
  static final String BACK_KEY = "skip-back-seconds";

  static final int DEFAULT_FORWARD_SHORT = 10, DEFAULT_FORWARD_LONG = 30, DEFAULT_BACK = 10;

  /** An item this long or longer skips forward by the longer amount. */
  static final long LONG_ITEM_MS = 60 * 60 * 1000L;

  /** The amounts offered in Settings, in seconds. */
  static final int[] CHOICES = {5, 10, 15, 20, 30, 45, 60};

  private SkipIntervals() {}

  /**
   * The forward skip for an item of {@code durationMs}. An unknown length (0 or less) uses the
   * shorter amount, the safer of the two.
   */
  static long forwardMs(long durationMs, int shortSeconds, int longSeconds) {
    int seconds = durationMs >= LONG_ITEM_MS ? longSeconds : shortSeconds;
    return valid(seconds) * 1000L;
  }

  static long backMs(int seconds) {
    return valid(seconds) * 1000L;
  }

  /** A stored amount outside the offered choices falls back to the nearest one. */
  static int valid(int seconds) {
    int best = CHOICES[0];
    for (int choice : CHOICES)
      if (Math.abs(choice - seconds) < Math.abs(best - seconds)) best = choice;
    return best;
  }

  static String label(int seconds) {
    return valid(seconds) + " seconds";
  }

  static int choiceIndex(int seconds) {
    int value = valid(seconds);
    for (int i = 0; i < CHOICES.length; i++) if (CHOICES[i] == value) return i;
    return -1;
  }

  static String[] choiceLabels() {
    String[] labels = new String[CHOICES.length];
    for (int i = 0; i < CHOICES.length; i++) labels[i] = label(CHOICES[i]);
    return labels;
  }
}
