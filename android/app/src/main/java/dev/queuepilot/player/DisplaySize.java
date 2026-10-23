package dev.queuepilot.player;

/**
 * The viewer's Display size: one percentage that scales every dimension and text size. A smaller
 * size fits more on screen, so a grid gains columns instead of leaving empty space.
 */
final class DisplaySize {
  static final String PREFERENCE_KEY = "display-size";
  static final int DEFAULT_PERCENT = 100;
  static final int[] CHOICES = {70, 80, 90, 100, 110, 125};

  private DisplaySize() {}

  /** A stored value that is not one of the choices reads as the default. */
  static int valid(int percent) {
    for (int choice : CHOICES) if (choice == percent) return percent;
    return DEFAULT_PERCENT;
  }

  static float scale(int percent) {
    return valid(percent) / 100f;
  }

  static String label(int percent) {
    return valid(percent) + "%";
  }

  /**
   * Columns for a grid that shows {@code baseline} columns at 100%. The width a column needs
   * scales with the size, so the count scales inversely; at least two columns are kept.
   */
  static int columns(int baseline, int percent) {
    return Math.max(2, Math.round(baseline * 100f / valid(percent)));
  }

  /** The width of one item when {@code columns} items share {@code width}, each with a gap. */
  static int itemWidth(int width, int columns, int gap) {
    return (width - 6 - gap * columns) / columns;
  }
}
