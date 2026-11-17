package dev.queuepilot.player;

import android.app.Activity;
import java.util.function.LongConsumer;

/**
 * Asks "Resume from 12:34" or "Start from the beginning" before a title with a saved position
 * plays. Back closes the question and plays nothing.
 */
final class ResumePrompt {
  private ResumePrompt() {}

  static void ask(
      Activity activity,
      TvPanel.Builder builder,
      String title,
      long savedMs,
      long floorMs,
      long durationMs,
      int rewindSeconds,
      LongConsumer start) {
    if (!ResumeChoice.shouldAsk(savedMs, floorMs, durationMs)) {
      start.accept(ResumeChoice.unasked(savedMs, floorMs, durationMs));
      return;
    }
    TvPanel panel =
        builder
            .setTitle(title)
            .setSelected(0)
            .setItemIcons(new int[] {R.drawable.lucide_play, R.drawable.lucide_rotate_ccw})
            .setItems(
                new String[] {ResumeChoice.resumeLabel(savedMs), ResumeChoice.BEGINNING_LABEL},
                (d, i) -> start.accept(ResumeChoice.start(i == 0, savedMs, floorMs, rewindSeconds)))
            .setNegativeButton("Cancel", null)
            .create();
    panel.show();
    panel.setPanelSize(new TvUi(activity).dp(380), -2);
  }
}
