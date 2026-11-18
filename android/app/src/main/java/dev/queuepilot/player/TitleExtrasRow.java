package dev.queuepilot.player;

import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.json.JSONArray;

/** A title's extras on its title screen or show header: one row per kind, and Play trailer. */
final class TitleExtrasRow {
  private final ScreenHost host;
  private final TvUi ui;

  TitleExtrasRow(ScreenHost host) {
    this.host = host;
    this.ui = host.ui();
  }

  /**
   * Reads the extras of {@code key} after the screen shows. Play trailer is inserted into {@code
   * actions} at {@code trailerAt}. {@code defaultFocus} is where the screen first put focus: the
   * extras restore focus only while it is still there.
   */
  void load(
      String key,
      LinearLayout area,
      LinearLayout actions,
      int trailerAt,
      Supplier<View> defaultFocus) {
    if (!key.matches("[0-9]+") || host.server().isEmpty()) return;
    int request = host.generation();
    String origin = host.server(), secret = host.token();
    host.background(
        () -> {
          try {
            JSONArray items =
                PlexCatalog.list(
                    PlexCatalog.get(
                        PlexCatalog.url(origin, "/library/metadata/" + key + "/extras"),
                        secret,
                        8 * 1024 * 1024));
            List<PlexCatalog.Entry> extras = new ArrayList<>();
            for (int i = 0; i < items.length(); i++)
              extras.add(PlexCatalog.Entry.fromJson(items.getJSONObject(i)));
            List<TitleExtras.Group> groups = TitleExtras.group(extras);
            host.activity()
                .runOnUiThread(
                    () -> {
                      if (request == host.generation())
                        draw(groups, area, actions, trailerAt, defaultFocus);
                    });
          } catch (Exception ignored) {
            // A title without extras, or an unreachable server, shows no extras.
          }
        });
  }

  private void draw(
      List<TitleExtras.Group> groups,
      LinearLayout area,
      LinearLayout actions,
      int trailerAt,
      Supplier<View> defaultFocus) {
    area.removeAllViews();
    // Read before anything is added, so a screen without extras still uses up its turn.
    boolean isEnded = host.takeExtrasFocus();
    if (groups.isEmpty()) return;
    PlexCatalog.Entry trailer = TitleExtras.trailer(groups);
    if (trailer != null && actions != null) addPlayTrailer(trailer, actions, trailerAt);
    area.setTag("extras");
    View first = null;
    for (TitleExtras.Group group : groups) {
      ui.gap(area, 24);
      TextView heading = ui.title(group.kind().heading(), 20);
      heading.setTag("extras-heading");
      area.addView(heading);
      ui.gap(area, 12);
      LinearLayout row = host.horizontalIn(area);
      for (PlexCatalog.Entry extra : group.items()) {
        View card = card(group.kind(), extra);
        row.addView(card);
        if (first == null) first = card;
      }
    }
    // Back from an extra returns to it; a title that just ended lands on its extras. Either way,
    // only while focus is still where the screen first put it.
    View focused = host.activity().getCurrentFocus();
    boolean isUnmoved = focused == null || focused == defaultFocus.get();
    if (!isUnmoved) return;
    if (isEnded) {
      first.requestFocus();
      return;
    }
    View remembered = host.rememberedView();
    if (remembered != null && remembered != focused) remembered.requestFocus();
  }

  private void addPlayTrailer(PlexCatalog.Entry trailer, LinearLayout actions, int trailerAt) {
    TextView[] button = new TextView[1];
    button[0] =
        ui.button(
            "Play trailer",
            R.drawable.lucide_clapperboard,
            () -> host.playExtra(trailer, button[0]),
            false);
    int at = Math.min(trailerAt, actions.getChildCount());
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
    if (at > 0) lp.leftMargin = ui.dp(12);
    actions.addView(button[0], at, lp);
    // The button it now precedes keeps its spacing.
    if (at == 0 && actions.getChildCount() > 1) {
      View next = actions.getChildAt(1);
      LinearLayout.LayoutParams spaced = (LinearLayout.LayoutParams) next.getLayoutParams();
      spaced.leftMargin = ui.dp(12);
      next.setLayoutParams(spaced);
    }
  }

  /** A landscape card for one extra: its still, its title, and its length. */
  private View card(TitleExtras.Kind kind, PlexCatalog.Entry extra) {
    LinearLayout card = ui.column();
    card.setPadding(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(8));
    ImageView image = new ImageView(host.activity());
    image.setScaleType(ImageView.ScaleType.CENTER_CROP);
    image.setBackgroundColor(TvUi.PANEL);
    card.addView(image, new LinearLayout.LayoutParams(-1, ui.dp(122)));
    host.loadArt(image, extra.thumb(), 720);
    ui.gap(card, 8);
    TextView label = ui.title(extra.title().isBlank() ? kind.singular() : extra.title(), 15);
    label.setMaxLines(2);
    label.setEllipsize(TextUtils.TruncateAt.END);
    card.addView(label);
    String length = DetailFacts.runtime(extra.duration());
    if (!length.isEmpty()) {
      ui.gap(card, 4);
      card.addView(ui.text(length, 12, TvUi.MUTED));
    }
    ui.focusable(card, () -> host.playExtra(extra, card), TvUi.BG, 0);
    card.setContentDescription(TitleExtras.spoken(kind, extra.title()));
    // Two extras may share a spoken name; focus memory tells them apart by key.
    card.setTag(new ScreenHost.FocusKey("extra:" + extra.key()));
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ui.dp(218), -2);
    lp.rightMargin = ui.dp(12);
    card.setLayoutParams(lp);
    return card;
  }
}
