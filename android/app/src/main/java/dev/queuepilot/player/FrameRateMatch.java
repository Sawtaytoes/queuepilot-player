package dev.queuepilot.player;

import java.util.List;

/**
 * Chooses the display mode that matches a video's frame rate, for {@code
 * WindowManager.LayoutParams.preferredDisplayModeId}.
 *
 * <p>Android's {@code Surface.setFrameRate} switches the refresh rate only when the switch is
 * seamless, unless the app passes {@code CHANGE_FRAME_RATE_ALWAYS}, which needs Android 12 (API
 * 31). A TV's HDMI mode switch is never seamless, and the NVIDIA Shield runs Android 11 (API 30).
 * Android's guidance names {@code preferredDisplayModeId} for an app that wants the switch even
 * when it is a heavy one, as on a TV. So the player picks a mode itself: the same resolution as the
 * current mode, with a refresh rate equal to the frame rate or a whole multiple of it.
 */
final class FrameRateMatch {
  static final String PREFERENCE_KEY = "match-frame-rate";
  static final boolean DEFAULT_ON = true;

  /** One {@code Display.Mode}. */
  record Mode(int id, int width, int height, float refreshRate) {}

  private FrameRateMatch() {}

  /**
   * The mode to request, or 0 (no preference) when the frame rate is unknown or no mode of the
   * current resolution matches it. An exact rate wins over a multiple, a lower multiple over a
   * higher. When the current mode already shows every frame for the same time, it is kept, so a
   * 29.97 fps video on a 59.94 Hz display does not blank the screen for a switch it does not need.
   */
  static int choose(List<Mode> modes, Mode current, double frameRate) {
    if (current == null || !(frameRate >= 10 && frameRate <= 120)) return 0;
    if (multiple(current.refreshRate(), frameRate) > 0) return current.id();
    Mode best = null;
    int bestMultiple = Integer.MAX_VALUE;
    for (Mode mode : modes) {
      if (mode.width() != current.width() || mode.height() != current.height()) continue;
      int multiple = multiple(mode.refreshRate(), frameRate);
      if (multiple <= 0) continue;
      if (multiple < bestMultiple) {
        best = mode;
        bestMultiple = multiple;
      }
    }
    return best == null ? 0 : best.id();
  }

  /**
   * The whole number of frames shown per refresh cycle, when {@code refreshRate} is a whole
   * multiple of {@code frameRate} within 0.1%; otherwise 0. 23.976 fps matches 23.976 Hz and
   * 47.952 Hz but not 24 Hz, since a 0.1% gap repeats a frame every 42 seconds.
   */
  static int multiple(double refreshRate, double frameRate) {
    if (refreshRate <= 0 || frameRate <= 0) return 0;
    long n = Math.round(refreshRate / frameRate);
    if (n < 1 || n > 8) return 0;
    double error = Math.abs(refreshRate - n * frameRate) / refreshRate;
    return error <= 0.0005 ? (int) n : 0;
  }
}
