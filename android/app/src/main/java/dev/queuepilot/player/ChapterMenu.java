package dev.queuepilot.player;

import android.app.Activity;
import android.graphics.Bitmap;
import android.widget.ImageView;
import android.widget.LinearLayout;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.LongConsumer;

/**
 * The player's chapter list, in a panel anchored to the control that opened it. A chapter with a
 * Plex chapter image shows it before its time and name; choosing a chapter seeks to its start.
 */
final class ChapterMenu {
  /** Loads a chapter image from the playing server off the main thread; returns null on failure. */
  interface Pictures {
    Bitmap load(String thumb) throws Exception;
  }

  private ChapterMenu() {}

  static void show(
      Activity activity,
      TvPanel.Builder builder,
      List<Chapters.Chapter> chapters,
      long positionMs,
      Executor network,
      Pictures pictures,
      LongConsumer seek) {
    TvUi ui = new TvUi(activity);
    String[] labels = new String[chapters.size()];
    for (int i = 0; i < labels.length; i++) labels[i] = Chapters.label(chapters.get(i));
    TvPanel[] shown = new TvPanel[1];
    TvPanel panel =
        builder
            .setTitle("Chapters")
            .setSelected(Chapters.current(chapters, positionMs))
            .setItemDecorator(
                (i, row) -> {
                  String thumb = chapters.get(i).thumb();
                  if (thumb.isEmpty()) return;
                  ImageView image = new ImageView(activity);
                  image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                  image.setBackground(ui.shape(TvUi.BG, 0, 8));
                  image.setClipToOutline(true);
                  LinearLayout.LayoutParams size =
                      new LinearLayout.LayoutParams(ui.dp(96), ui.dp(54));
                  size.rightMargin = ui.dp(12);
                  row.addView(image, 0, size);
                  String name = chapters.get(i).title();
                  network.execute(
                      () -> {
                        Bitmap bitmap;
                        try {
                          bitmap = pictures.load(thumb);
                        } catch (Exception unavailable) {
                          return;
                        }
                        if (bitmap == null) return;
                        activity.runOnUiThread(
                            () -> {
                              if (shown[0] == null || !shown[0].isShowing()) return;
                              image.setImageBitmap(bitmap);
                              // Described only once the picture is actually shown.
                              image.setContentDescription("Picture of " + name);
                            });
                      });
                })
            .setItems(labels, (d, i) -> seek.accept(chapters.get(i).startMs()))
            .setNegativeButton("Close", null)
            .create();
    shown[0] = panel;
    panel.show();
    panel.setPanelSize(ui.dp(480), -2);
  }
}
