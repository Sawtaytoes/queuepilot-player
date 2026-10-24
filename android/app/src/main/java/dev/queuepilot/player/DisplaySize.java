package dev.queuepilot.player;

import android.content.SharedPreferences;
import java.util.Locale;

/**
 * The viewer's Display size: one percentage that scales every dimension and text size. A smaller
 * size fits more on screen, so a grid gains columns instead of leaving empty space.
 *
 * <p>Auto, the default, computes the size from the screen's diagonal and the viewing distance.
 * A large screen seen from close by fills more of the view, so it needs a smaller interface:
 *
 * <pre>
 * width = diagonal x 16 / sqrt(16^2 + 9^2)          a 16:9 screen, in inches
 * angle = 2 x atan(width / 2 / (distance x 12))     the horizontal viewing angle
 * scale = sqrt(angle of 55 in at 10 ft / angle)     55 in at 10 ft, about 22.6 degrees, is 100%
 * </pre>
 *
 * rounded to 5% and kept within 50% to 125%. The square root halves the change: matching the
 * angle exactly would make a 97 in screen at 6.5 ft 40%, too small to read comfortably.
 */
final class DisplaySize {
  static final String PREFERENCE_KEY = "display-size",
      SCREEN_KEY = "display-screen-inches",
      DISTANCE_KEY = "display-distance-feet";

  /** The stored value for Auto. Every other stored value is a percentage. */
  static final int AUTO = 0;

  static final int DEFAULT_PERCENT = AUTO, MINIMUM = 50, MAXIMUM = 125, STEP = 5;
  static final float DEFAULT_SCREEN_INCHES = 55, DEFAULT_DISTANCE_FEET = 9;

  /** The screen and distance a viewer can enter; anything outside reads as the default. */
  static final float MINIMUM_SCREEN_INCHES = 20,
      MAXIMUM_SCREEN_INCHES = 150,
      MINIMUM_DISTANCE_FEET = 2,
      MAXIMUM_DISTANCE_FEET = 30;

  /** The manual choices: 50% to 125% in 5% steps. */
  static final int[] CHOICES = choices();

  private static final double REFERENCE_ANGLE = angle(55, 10);

  private DisplaySize() {}

  private static int[] choices() {
    int[] values = new int[(MAXIMUM - MINIMUM) / STEP + 1];
    for (int i = 0; i < values.length; i++) values[i] = MINIMUM + i * STEP;
    return values;
  }

  /** A stored value that is neither Auto nor one of the choices reads as Auto. */
  static int valid(int stored) {
    for (int choice : CHOICES) if (choice == stored) return stored;
    return AUTO;
  }

  static float screenInches(float stored) {
    return stored >= MINIMUM_SCREEN_INCHES && stored <= MAXIMUM_SCREEN_INCHES
        ? stored
        : DEFAULT_SCREEN_INCHES;
  }

  static float distanceFeet(float stored) {
    return stored >= MINIMUM_DISTANCE_FEET && stored <= MAXIMUM_DISTANCE_FEET
        ? stored
        : DEFAULT_DISTANCE_FEET;
  }

  /** The horizontal viewing angle of a 16:9 screen, in degrees. */
  static double angle(double diagonalInches, double distanceFeet) {
    double width = diagonalInches * 16 / Math.sqrt(16 * 16 + 9 * 9);
    return Math.toDegrees(2 * Math.atan(width / 2 / (distanceFeet * 12)));
  }

  /** Auto's percentage for a screen and a distance. */
  static int autoPercent(float diagonalInches, float distanceFeet) {
    double scale =
        Math.sqrt(
            REFERENCE_ANGLE / angle(screenInches(diagonalInches), distanceFeet(distanceFeet)));
    int percent = (int) Math.round(scale * 100 / STEP) * STEP;
    return Math.max(MINIMUM, Math.min(MAXIMUM, percent));
  }

  /** The percentage in effect: a manual choice, or Auto's computed value. */
  static int percent(int stored, float diagonalInches, float distanceFeet) {
    int choice = valid(stored);
    return choice == AUTO ? autoPercent(diagonalInches, distanceFeet) : choice;
  }

  static int percent(SharedPreferences prefs) {
    return percent(
        prefs.getInt(PREFERENCE_KEY, DEFAULT_PERCENT),
        prefs.getFloat(SCREEN_KEY, DEFAULT_SCREEN_INCHES),
        prefs.getFloat(DISTANCE_KEY, DEFAULT_DISTANCE_FEET));
  }

  static float scale(int percent) {
    return percent / 100f;
  }

  /** "100%", or "Auto · 65%" when Auto is chosen. */
  static String label(int stored, float diagonalInches, float distanceFeet) {
    int choice = valid(stored);
    return choice == AUTO
        ? "Auto · " + autoPercent(diagonalInches, distanceFeet) + "%"
        : choice + "%";
  }

  static String label(SharedPreferences prefs) {
    return label(
        prefs.getInt(PREFERENCE_KEY, DEFAULT_PERCENT),
        prefs.getFloat(SCREEN_KEY, DEFAULT_SCREEN_INCHES),
        prefs.getFloat(DISTANCE_KEY, DEFAULT_DISTANCE_FEET));
  }

  /** "97 in". */
  static String screenLabel(float diagonalInches) {
    return number(screenInches(diagonalInches)) + " in";
  }

  /** "6.5 ft". */
  static String distanceLabel(float distanceFeet) {
    return number(distanceFeet(distanceFeet)) + " ft";
  }

  /** A whole number without a decimal point, otherwise one decimal place. */
  static String number(float value) {
    return value == Math.rint(value)
        ? String.valueOf(Math.round(value))
        : String.format(Locale.US, "%.1f", value);
  }

  /**
   * Columns for a grid that shows {@code baseline} columns at 100%. The width a column needs
   * scales with the size, so the count scales inversely; at least two columns are kept.
   */
  static int columns(int baseline, int percent) {
    return Math.max(2, Math.round(baseline * 100f / Math.max(MINIMUM, percent)));
  }

  /** The width of one item when {@code columns} items share {@code width}, each with a gap. */
  static int itemWidth(int width, int columns, int gap) {
    return (width - 6 - gap * columns) / columns;
  }
}
