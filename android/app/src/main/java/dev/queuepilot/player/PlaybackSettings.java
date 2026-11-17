package dev.queuepilot.player;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.provider.Settings;
import java.util.function.Supplier;

/**
 * The playback options: skip amounts, refresh-rate matching, and the system subtitle style. The
 * same panel opens from Settings and from the player's options menu, so they are one press away
 * while watching.
 */
final class PlaybackSettings {
  private final Activity activity;
  private final SharedPreferences prefs;
  private final Supplier<TvPanel.Builder> panels;
  private final Runnable onFrameRateChanged;
  private final Runnable onChanged;

  PlaybackSettings(
      Activity activity,
      SharedPreferences prefs,
      Supplier<TvPanel.Builder> panels,
      Runnable onFrameRateChanged,
      Runnable onChanged) {
    this.activity = activity;
    this.prefs = prefs;
    this.panels = panels;
    this.onFrameRateChanged = onFrameRateChanged;
    this.onChanged = onChanged;
  }

  static int forwardShort(SharedPreferences prefs) {
    return SkipIntervals.valid(
        prefs.getInt(SkipIntervals.FORWARD_SHORT_KEY, SkipIntervals.DEFAULT_FORWARD_SHORT));
  }

  static int forwardLong(SharedPreferences prefs) {
    return SkipIntervals.valid(
        prefs.getInt(SkipIntervals.FORWARD_LONG_KEY, SkipIntervals.DEFAULT_FORWARD_LONG));
  }

  static int back(SharedPreferences prefs) {
    return SkipIntervals.valid(prefs.getInt(SkipIntervals.BACK_KEY, SkipIntervals.DEFAULT_BACK));
  }

  static boolean isFrameRateMatched(SharedPreferences prefs) {
    return prefs.getBoolean(FrameRateMatch.PREFERENCE_KEY, FrameRateMatch.DEFAULT_ON);
  }

  void show() {
    String[] rows = {
      "Skip forward, under 1 hour · " + SkipIntervals.label(forwardShort(prefs)),
      "Skip forward, 1 hour or longer · " + SkipIntervals.label(forwardLong(prefs)),
      "Skip back · " + SkipIntervals.label(back(prefs)),
      "Match frame rate · " + (isFrameRateMatched(prefs) ? "On" : "Off"),
      "Subtitle size and background",
      "When a file can't play directly · "
          + ServerConversion.label(prefs.getString(ServerConversion.PREFERENCE_KEY, null)),
    };
    TvPanel panel =
        panels
            .get()
            .setTitle("Playback")
            .setMessage("Skip amounts apply to the next video you start.")
            .setItemIcons(
                new int[] {
                  R.drawable.lucide_fast_forward,
                  R.drawable.lucide_fast_forward,
                  R.drawable.lucide_rewind,
                  R.drawable.lucide_monitor_play,
                  R.drawable.lucide_captions,
                  R.drawable.lucide_server_cog
                })
            .setItems(
                rows,
                (d, i) -> {
                  switch (i) {
                    case 0 ->
                        skip(
                            "Skip forward, under 1 hour",
                            SkipIntervals.FORWARD_SHORT_KEY,
                            forwardShort(prefs));
                    case 1 ->
                        skip(
                            "Skip forward, 1 hour or longer",
                            SkipIntervals.FORWARD_LONG_KEY,
                            forwardLong(prefs));
                    case 2 -> skip("Skip back", SkipIntervals.BACK_KEY, back(prefs));
                    case 3 -> frameRate();
                    case 4 -> captions();
                    default -> unplayable();
                  }
                })
            .setNegativeButton("Close", null)
            .create();
    panel.show();
    panel.setPanelSize(new TvUi(activity).dp(440), -2);
  }

  private void skip(String title, String key, int current) {
    TvPanel panel =
        panels
            .get()
            .setTitle(title)
            .setMessage(
                key.equals(SkipIntervals.BACK_KEY)
                    ? "How far Back and the remote's rewind key go."
                    : key.equals(SkipIntervals.FORWARD_SHORT_KEY)
                        ? "For a video shorter than an hour, such as most episodes."
                        : "For a video an hour or longer, such as most movies.")
            .setSelected(SkipIntervals.choiceIndex(current))
            .setItems(
                SkipIntervals.choiceLabels(),
                (d, i) -> {
                  prefs.edit().putInt(key, SkipIntervals.CHOICES[i]).apply();
                  onChanged.run();
                  show();
                })
            .setNegativeButton("Back", (d, i) -> show())
            .create();
    panel.show();
    panel.setPanelSize(new TvUi(activity).dp(360), -2);
  }

  private void frameRate() {
    boolean isOn = isFrameRateMatched(prefs);
    TvPanel panel =
        panels
            .get()
            .setTitle("Match frame rate")
            .setMessage(
                "Switch the TV's refresh rate to the video's frame rate, such as 24 frames a"
                    + " second for most movies, so motion stays smooth. The screen may go dark"
                    + " for a moment when it switches.")
            .setSelected(isOn ? 0 : 1)
            .setItems(
                new String[] {"On", "Off"},
                (d, i) -> {
                  prefs.edit().putBoolean(FrameRateMatch.PREFERENCE_KEY, i == 0).apply();
                  onFrameRateChanged.run();
                  onChanged.run();
                  show();
                })
            .setNegativeButton("Back", (d, i) -> show())
            .create();
    panel.show();
    panel.setPanelSize(new TvUi(activity).dp(400), -2);
  }

  private void unplayable() {
    TvPanel panel =
        panels
            .get()
            .setTitle("When a file can't play directly")
            .setMessage(
                "Some files use a format this TV can't play. The server can convert them while"
                    + " you watch, or playback can stop so you can fix the file first.")
            .setSelected(
                ServerConversion.index(prefs.getString(ServerConversion.PREFERENCE_KEY, null)))
            .setItems(
                ServerConversion.LABELS,
                (d, i) -> {
                  prefs.edit().putString(ServerConversion.PREFERENCE_KEY, ServerConversion.MODES[i]).apply();
                  onChanged.run();
                  show();
                })
            .setNegativeButton("Back", (d, i) -> show())
            .create();
    panel.show();
    panel.setPanelSize(new TvUi(activity).dp(420), -2);
  }

  /**
   * Text subtitles (SRT, WebVTT and similar) follow Android's caption style: size, font, color and
   * background. ASS subtitles keep their authored styles. Opens the system settings when the
   * device has them.
   */
  private void captions() {
    Intent intent = new Intent(Settings.ACTION_CAPTIONING_SETTINGS);
    boolean isAvailable = intent.resolveActivity(activity.getPackageManager()) != null;
    TvPanel panel =
        panels
            .get()
            .setTitle("Subtitle size and background")
            .setMessage(
                "Text subtitles use Android's caption style: text size, font, color, and"
                    + " background. Styled ASS subtitles keep the look their file gives them."
                    + (isAvailable ? "" : " This device has no caption settings screen."))
            .setPositiveButton(
                isAvailable ? "Open caption settings" : "Close",
                (d, i) -> {
                  if (!isAvailable) return;
                  try {
                    activity.startActivity(intent);
                  } catch (android.content.ActivityNotFoundException ignored) {
                    // Resolved a moment ago; nothing else to do.
                  }
                })
            .setNegativeButton("Back", (d, i) -> show())
            .create();
    panel.show();
    panel.setPanelSize(new TvUi(activity).dp(420), -2);
  }
}
