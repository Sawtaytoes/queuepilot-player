package dev.queuepilot.player;

import android.text.TextUtils;
import android.view.View;
import android.view.ViewParent;
import android.widget.ScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.json.JSONArray;

/** The Recently Added row above a library's grid, on the library's first screen only. */
final class RecentlyAddedRow {
  private final ScreenHost host;
  private final TvUi ui;

  RecentlyAddedRow(ScreenHost host) {
    this.host = host;
    this.ui = host.ui();
  }

  /** True when {@code view} is inside a Recently Added row. */
  static boolean holds(View view) {
    for (Object at = view; at instanceof View; at = ((View) at).getParent())
      if (TAG.equals(((View) at).getTag())) return true;
    return false;
  }

  private static final String TAG = "recently-added";

  /**
   * Reads and draws the row for a movie or show library; any other kind gets none. {@code
   * gridFirst} is the grid's first card, where the screen puts focus before the row arrives. On a
   * fresh visit ({@code isFresh}) the row then takes focus, so the screen starts at its top.
   */
  void load(
      String section,
      String libraryType,
      LinearLayout area,
      boolean isFresh,
      Supplier<View> gridFirst) {
    boolean isShows = "show".equals(libraryType);
    if (!isShows && !"movie".equals(libraryType)) return;
    int request = host.generation();
    String origin = host.server(), secret = host.token();
    String path = isShows ? RecentlyAdded.episodesPath(section) : RecentlyAdded.moviesPath(section);
    host.background(
        () -> {
          try {
            JSONArray items =
                PlexCatalog.list(
                    PlexCatalog.get(PlexCatalog.url(origin, path), secret, 8 * 1024 * 1024));
            List<PlexCatalog.Entry> entries = new ArrayList<>();
            for (int i = 0; i < items.length(); i++)
              entries.add(PlexCatalog.Entry.fromJson(items.getJSONObject(i)));
            host.activity()
                .runOnUiThread(
                    () -> {
                      if (request == host.generation()) draw(isShows, entries, area, isFresh, gridFirst);
                    });
          } catch (Exception ignored) {
            // The grid below still lists everything.
          }
        });
  }

  private void draw(
      boolean isShows,
      List<PlexCatalog.Entry> entries,
      LinearLayout area,
      boolean isFresh,
      Supplier<View> gridFirst) {
    area.removeAllViews();
    List<View> cards = new ArrayList<>();
    if (isShows)
      for (RecentlyAdded.Show show : RecentlyAdded.byShow(entries, RecentlyAdded.LIMIT))
        cards.add(showCard(show));
    else
      for (PlexCatalog.Entry movie : entries.subList(0, Math.min(RecentlyAdded.LIMIT, entries.size()))) {
        View card = host.poster(movie);
        card.setContentDescription("Recently added, " + movie.spoken());
        cards.add(card);
      }
    if (cards.isEmpty()) return;
    area.setTag(TAG);
    TextView heading = ui.title("Recently Added", 21);
    area.addView(heading);
    ui.gap(area, 12);
    LinearLayout row = host.horizontalIn(area);
    for (View card : cards) row.addView(card);
    ui.gap(area, 18);
    takeFocus(cards.get(0), area, isFresh, gridFirst);
  }

  /**
   * Starts the screen on the row, unless the viewer has already moved: a fresh visit lands on the
   * first card, and Back from a title opened here lands on its card again. The screen then shows
   * from its top, with the library's tools.
   */
  private void takeFocus(View first, View area, boolean isFresh, Supplier<View> gridFirst) {
    View focused = host.activity().getCurrentFocus();
    if (focused != null && focused != gridFirst.get()) return;
    View remembered = host.rememberedView();
    View target = remembered != null && holds(remembered) ? remembered : isFresh ? first : null;
    if (target == null) return;
    target.requestFocus();
    for (ViewParent at = area.getParent(); at != null; at = at.getParent())
      if (at instanceof ScrollView) {
        ScrollView scroll = (ScrollView) at;
        scroll.post(() -> scroll.scrollTo(0, 0));
        return;
      }
  }

  /** One show with new episodes: its poster, its name, and "3 new episodes". */
  private View showCard(RecentlyAdded.Show show) {
    LinearLayout card = ui.column();
    card.setPadding(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(10));
    int width = host.posterWidth();
    ImageView image = new ImageView(host.activity());
    image.setScaleType(ImageView.ScaleType.CENTER_CROP);
    image.setBackgroundColor(TvUi.PANEL);
    card.addView(image, new LinearLayout.LayoutParams(-1, ui.dp(width * 3 / 2)));
    host.loadArt(image, show.thumb(), 720);
    ui.gap(card, 10);
    TextView label = ui.title(show.showTitle(), 16);
    label.setMaxLines(2);
    label.setEllipsize(TextUtils.TruncateAt.END);
    card.addView(label);
    ui.gap(card, 5);
    card.addView(ui.text(show.countLine(), 12, TvUi.MUTED));
    ui.focusable(
        card,
        () -> host.openLibrary(TitleLinks.childrenPath(show.showKey()), show.showTitle()),
        TvUi.BG,
        0);
    card.setContentDescription("Recently added, " + show.showTitle() + ", " + show.countLine());
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ui.dp(width), -2);
    lp.rightMargin = ui.dp(14);
    card.setLayoutParams(lp);
    return card;
  }
}
