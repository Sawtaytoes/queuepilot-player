package dev.queuepilot.player;

import java.util.List;

/**
 * Plex intro and credits markers offer a manual skip; nothing is skipped automatically. Seeks
 * stay inside an authored queue section, and skipping the final credits completes the item.
 */
final class MarkerSkip {
  /** Credits ending this close to the end of the item finish it rather than seek within it. */
  static final long FINAL_CREDITS_SLACK_MS = 2000;

  /** A skip button hides after this long unless the playback controls are showing. */
  static final long VISIBLE_MS = 8000;

  record Marker(String type, long start, long end, boolean isFinal) {
    boolean isIntro() {
      return "intro".equals(type);
    }

    boolean isCredits() {
      return "credits".equals(type);
    }
  }

  private MarkerSkip() {}

  /**
   * The intro or credits marker containing the position, if a skip would move forward inside the
   * playable window. The window is the authored section, or the whole item when sectionEnd < 0.
   */
  static Marker active(List<Marker> markers, long position, long sectionFloor, long sectionEnd) {
    for (Marker marker : markers) {
      if (!(marker.isIntro() || marker.isCredits()) || marker.end() <= marker.start()) continue;
      if (position < marker.start() || position >= marker.end()) continue;
      if (position < sectionFloor || (sectionEnd >= 0 && position >= sectionEnd)) continue;
      return marker;
    }
    return null;
  }

  static String label(Marker marker) {
    return marker.isIntro() ? "Skip Intro" : "Skip Credits";
  }

  /** Final credits finish the item: Plex marks it watched and a queue moves to the next item. */
  static boolean completesItem(Marker marker, long duration) {
    return marker.isCredits()
        && (marker.isFinal()
            || (duration > 0 && duration - marker.end() <= FINAL_CREDITS_SLACK_MS));
  }

  /**
   * Where the skip lands. Completing credits land on the end of the window, which the player
   * already treats as a finished item or a finished section. Never leaves the section.
   */
  static long target(Marker marker, long duration, long sectionFloor, long sectionEnd) {
    long windowEnd = sectionEnd >= 0 ? sectionEnd : duration;
    long landing = completesItem(marker, duration) && windowEnd > 0 ? windowEnd : marker.end();
    if (windowEnd > 0) landing = Math.min(landing, windowEnd);
    return Math.max(sectionFloor, landing);
  }
}
