package dev.queuepilot.player;

import android.util.Log;
import android.view.Display;
import android.view.Window;
import android.view.WindowManager;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Tracks;
import java.util.ArrayList;
import java.util.List;

/**
 * Asks Android for the display mode that matches the playing video's frame rate, through the
 * window's {@code preferredDisplayModeId}; see {@link FrameRateMatch} for why. Clearing it returns
 * the TV to the mode it chooses itself.
 */
@androidx.media3.common.util.UnstableApi
final class DisplayModeSwitch {
  static final String LOG_TAG = "QueuePilotFrameRate";

  private DisplayModeSwitch() {}

  /**
   * Requests a mode for {@code plexFrameRate}, or for the selected video track's rate when Plex
   * reports none. With matching off, or no rate, the request is cleared.
   */
  static void apply(Window window, boolean isOn, double plexFrameRate, Tracks tracks) {
    double rate = plexFrameRate > 0 ? plexFrameRate : trackFrameRate(tracks);
    int wanted = 0;
    Display display = window.getDecorView().getDisplay();
    if (isOn && rate > 0 && display != null) {
      List<FrameRateMatch.Mode> modes = new ArrayList<>();
      for (Display.Mode mode : display.getSupportedModes()) modes.add(mode(mode));
      wanted = FrameRateMatch.choose(modes, mode(display.getMode()), rate);
    }
    set(window, wanted);
    Log.i(LOG_TAG, "frameRate=" + rate + " on=" + isOn + " preferredMode=" + wanted);
  }

  static void clear(Window window) {
    set(window, 0);
  }

  private static void set(Window window, int modeId) {
    WindowManager.LayoutParams attributes = window.getAttributes();
    if (attributes.preferredDisplayModeId == modeId) return;
    attributes.preferredDisplayModeId = modeId;
    window.setAttributes(attributes);
  }

  private static FrameRateMatch.Mode mode(Display.Mode mode) {
    return new FrameRateMatch.Mode(
        mode.getModeId(), mode.getPhysicalWidth(), mode.getPhysicalHeight(), mode.getRefreshRate());
  }

  private static double trackFrameRate(Tracks tracks) {
    if (tracks == null) return 0;
    for (Tracks.Group group : tracks.getGroups()) {
      if (group.getType() != C.TRACK_TYPE_VIDEO) continue;
      for (int i = 0; i < group.length; i++) {
        Format format = group.getTrackFormat(i);
        if (group.isTrackSelected(i) && format.frameRate > 0) return format.frameRate;
      }
    }
    return 0;
  }
}
