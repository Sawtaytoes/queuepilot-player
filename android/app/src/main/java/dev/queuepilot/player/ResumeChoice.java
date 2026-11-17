package dev.queuepilot.player;

import java.util.Locale;

/**
 * The question before a title with a saved position plays: "Resume from 12:34" or "Start from the
 * beginning". Resuming honors Rewind on resume, so a viewer who replays a little after a pause also
 * replays a little after a stop. A queue item's beginning is its authored section start.
 */
final class ResumeChoice {
  private ResumeChoice() {}

  /**
   * True when there is a saved position worth asking about: after the start, and before the end
   * when the length is known.
   */
  static boolean shouldAsk(long savedMs, long floorMs, long durationMs) {
    long floor = Math.max(0, floorMs);
    if (savedMs < floor + 1000) return false;
    return durationMs <= 0 || savedMs < durationMs;
  }

  /** Where playback starts for the viewer's answer. */
  static long start(boolean isResume, long savedMs, long floorMs, int rewindSeconds) {
    long floor = Math.max(0, floorMs);
    if (!isResume) return floor;
    return Math.max(floor, savedMs - Math.max(0, rewindSeconds) * 1000L);
  }

  /**
   * Where playback starts when nothing is asked: a saved point too close to the start to ask
   * about, or one at or past the end, which starts from the beginning.
   */
  static long unasked(long savedMs, long floorMs, long durationMs) {
    long floor = Math.max(0, floorMs);
    boolean isInside = savedMs > floor && (durationMs <= 0 || savedMs < durationMs);
    return isInside ? savedMs : floor;
  }

  static String resumeLabel(long savedMs) {
    return "Resume from " + time(savedMs);
  }

  static final String BEGINNING_LABEL = "Start from the beginning";

  /** "4:05", "12:34" or "1:02:03". */
  static String time(long ms) {
    long total = Math.max(0, ms) / 1000;
    long hours = total / 3600, minutes = total / 60 % 60, seconds = total % 60;
    return hours > 0
        ? String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
        : String.format(Locale.ROOT, "%d:%02d", minutes, seconds);
  }
}
