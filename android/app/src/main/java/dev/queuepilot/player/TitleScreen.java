package dev.queuepilot.player;

import android.graphics.Bitmap;
import android.text.TextUtils;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;

/**
 * The title screen: a movie's, show's, season's, or episode's art, facts, summary, actions,
 * extras, and cast, with the full details and the parent show's header loaded off the main
 * thread.
 */
@androidx.media3.common.util.UnstableApi
final class TitleScreen {
  private final MainActivity host;

  TitleScreen(MainActivity host) {
    this.host = host;
  }

  void details(PlexCatalog.Entry entry) {
    host.shell("");
    // Smaller than a screen heading: a long title still fits two lines at 1080p, then ends in an
    // ellipsis instead of pushing Play below the fold.
    TextView title =
        host.heading(
            entry.type().equals("episode")
                ? entry.showTitle().isEmpty() ? "Episode " + entry.episode() : "Episode"
                : "Your library",
            entry.primary(),
            26);
    title.setTag("details-title");
    title.setMaxLines(2);
    title.setEllipsize(TextUtils.TruncateAt.END);
    if (!entry.showTitle().isEmpty() && entry.type().equals("episode")) {
      TextView line = host.ui.text(entry.secondary(), 17, TvUi.WHITE);
      line.setTag("details-episode");
      line.setMaxLines(2);
      line.setEllipsize(TextUtils.TruncateAt.END);
      host.content.addView(line);
      host.ui.gap(host.content, 14);
    }
    LinearLayout row = host.ui.row();
    ImageView image = new ImageView(host);
    image.setScaleType(ImageView.ScaleType.FIT_CENTER);
    row.addView(image, new LinearLayout.LayoutParams(host.dp(180), host.dp(270)));
    host.loadArt(image, entry.thumb(), 1440);
    LinearLayout info = host.ui.column();
    info.setPadding(host.dp(28), host.dp(4), 0, 0);
    row.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
    info.addView(host.ui.text("Watch with " + host.profileName(), 15, TvUi.MUTED));
    host.ui.gap(info, 10);
    // Year, rating, length and studio; genres; scores; watch state; director and writer.
    LinearLayout facts = host.ui.column();
    info.addView(facts);
    fillDetailFacts(facts, entry);
    host.ui.gap(info, 14);
    TextView more = null;
    if (!entry.summary().isBlank()) {
      TextView summary = host.ui.text(entry.summary().trim(), 15, TvUi.WHITE);
      summary.setTag("details-summary");
      summary.setMaxLines(4);
      summary.setEllipsize(TextUtils.TruncateAt.END);
      summary.setLineSpacing(0, 1.12f);
      info.addView(summary);
      host.ui.gap(info, 18);
      more =
          host.ui.button(
              "More",
              R.drawable.lucide_text,
              () ->
                  host.panel()
                      .setTitle(entry.primary())
                      .setMessage(entry.summary().trim())
                      .setPositiveButton("Close", null)
                      .create()
                      .show(),
              false);
      more.setContentDescription("More. Read the full summary");
      // Offered only when the summary is actually cut off.
      TextView moreButton = more;
      moreButton.setVisibility(View.GONE);
      summary.addOnLayoutChangeListener(
          (v, l, t, r, b, ol, ot, or, ob) -> {
            android.text.Layout layout = summary.getLayout();
            boolean isClamped =
                layout != null
                    && layout.getLineCount() > 0
                    && layout.getEllipsisCount(layout.getLineCount() - 1) > 0;
            int visibility = isClamped ? View.VISIBLE : View.GONE;
            if (moreButton.getVisibility() != visibility)
              moreButton.post(() -> moreButton.setVisibility(visibility));
          });
    }
    LinearLayout actions = host.ui.row();
    TextView play = host.ui.button("Play", R.drawable.lucide_play, () -> host.playEntry(entry), true);
    actions.addView(play);
    // An episode is added as its show: a queue holds movies and shows.
    boolean isEpisode = entry.type().equals("episode");
    String addKey = isEpisode ? entry.showKey() : entry.key();
    if (!addKey.isEmpty()) {
      String addTitle = isEpisode ? entry.showTitle() : entry.title();
      TextView add =
          host.ui.button(
              "Add to queue",
              R.drawable.lucide_list_plus,
              () -> host.addToQueue(addKey, addTitle, isEpisode),
              false);
      add.setContentDescription(
          isEpisode ? "Add the show " + addTitle + " to a queue" : "Add to queue");
      LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-2, -2);
      gap.leftMargin = host.dp(12);
      actions.addView(add, gap);
    }
    if (more != null) {
      LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-2, -2);
      gap.leftMargin = host.dp(12);
      actions.addView(more, gap);
    }
    LinearLayout.LayoutParams art = new LinearLayout.LayoutParams(-2, -2);
    art.leftMargin = host.dp(12);
    actions.addView(
        host.ui.button("Artwork quality", R.drawable.lucide_image, () -> compareArt(entry), false), art);
    // The actions scroll sideways rather than squeeze a label at a large Display size.
    HorizontalScrollView actionScroll = new HorizontalScrollView(host);
    actionScroll.setHorizontalScrollBarEnabled(false);
    actionScroll.setClipToPadding(false);
    actionScroll.addView(actions);
    info.addView(actionScroll);
    // Where this title leads: its show and season, and its collections once the record arrives.
    LinearLayout links = host.ui.row();
    if (isEpisode) host.titleNavigation.addParentLinks(links, entry);
    host.ui.gap(info, 12);
    info.addView(links);
    host.content.addView(row);
    LinearLayout castArea = host.ui.column();
    host.content.addView(castArea);
    host.castPanel.fillCastRow(castArea, entry);
    LinearLayout extrasArea = host.ui.column();
    host.content.addView(extrasArea);
    host.focusFirst(play);
    loadFullDetails(
        entry,
        facts,
        castArea,
        full -> {
          if (!isEpisode) host.titleNavigation.addCollectionLink(links, full);
          host.titleNavigation.restoreFocus(() -> play);
        });
    // Play trailer goes beside Play, which stays first.
    new TitleExtrasRow(host.screenHost).load(entry.key(), extrasArea, actions, 1, () -> play);
  }

  /**
   * A single title or an extra that played to its end goes back to the screen it started from. A
   * title lands on its extras; an extra lands on itself. A later design adds an up-next screen.
   */
  void returnToTitle() {
    if (host.player == null || host.nativePlan != null || host.current == null || !"player".equals(host.current.kind()))
      return;
    if (!host.isPlayingExtra) {
      host.extrasFocus = host.history.peek();
      // An ended title starts on Play, then its extras: not on a button used before Play.
      if (host.extrasFocus != null) host.focusMemory.remove(host.extrasFocus);
    }
    // "ended" was the title's last report; Back must not send a second one.
    host.playingEntry = null;
    host.back();
  }

  void fillDetailFacts(LinearLayout facts, PlexCatalog.Entry entry) {
    facts.removeAllViews();
    String[][] lines = {
      {"details-meta", DetailFacts.meta(entry.type(), entry.facts(), entry.duration())},
      {"details-genres", DetailFacts.genres(entry.facts())},
      {"details-ratings", DetailFacts.ratings(entry.facts())},
      {
        "details-progress",
        DetailFacts.progress(entry.type(), entry.resume(), entry.duration(), entry.facts())
      },
      {"details-credits", DetailFacts.credits(entry.roles())},
    };
    for (String[] line : lines) {
      if (line[1].isEmpty()) continue;
      int color = line[0].equals("details-meta") ? TvUi.WHITE : TvUi.MUTED;
      TextView text = host.ui.text(line[1], 15, color);
      text.setTag(line[0]);
      text.setMaxLines(2);
      text.setEllipsize(TextUtils.TruncateAt.END);
      facts.addView(text);
      host.ui.gap(facts, 5);
    }
  }

  /**
   * A listing carries only part of a title's metadata, so the full record is read once the
   * screen is showing; an episode's cast is completed from its season and show.
   */
  private void loadFullDetails(
      PlexCatalog.Entry entry,
      LinearLayout facts,
      LinearLayout castArea,
      java.util.function.Consumer<PlexCatalog.Entry> loaded) {
    if (host.profile == null || entry.key().isEmpty()) return;
    int request = host.generation;
    String origin = host.server(), secret = host.token();
    host.network.execute(
        () -> {
          try {
            JSONArray items =
                PlexCatalog.list(
                    PlexCatalog.get(
                        PlexCatalog.url(origin, "/library/metadata/" + entry.key()),
                        secret,
                        16 * 1024 * 1024));
            if (items.length() == 0) return;
            PlexCatalog.Entry listed = PlexCatalog.Entry.fromJson(items.getJSONObject(0));
            PlexCatalog.Entry full = host.castPanel.withInheritedCast(listed, origin, secret);
            host.runOnUiThread(
                () -> {
                  if (request != host.generation) return;
                  fillDetailFacts(facts, full);
                  host.castPanel.fillCastRow(castArea, full);
                  loaded.accept(full);
                });
          } catch (Exception ignored) {
            // The listing's facts stay; the screen is still usable without the full record.
          }
        });
  }

  void loadParentHeader(String key, LinearLayout header) {
    int request = host.generation;
    String origin = host.server(), secret = host.token();
    host.network.execute(
        () -> {
          try {
            JSONArray items =
                PlexCatalog.list(
                    PlexCatalog.get(
                        PlexCatalog.url(origin, "/library/metadata/" + key),
                        secret,
                        16 * 1024 * 1024));
            if (items.length() == 0) return;
            PlexCatalog.Entry listed = PlexCatalog.Entry.fromJson(items.getJSONObject(0));
            PlexCatalog.Entry parent = host.castPanel.withInheritedCast(listed, origin, secret);
            if (!Set.of("show", "season").contains(parent.type())) return;
            host.runOnUiThread(
                () -> {
                  if (request != host.generation) return;
                  LinearLayout facts = host.ui.column();
                  header.addView(facts);
                  fillDetailFacts(facts, parent);
                  if (!parent.summary().isBlank()) {
                    TextView summary = host.ui.text(parent.summary().trim(), 15, TvUi.WHITE);
                    summary.setTag("parent-summary");
                    summary.setMaxLines(3);
                    summary.setEllipsize(TextUtils.TruncateAt.END);
                    host.ui.gap(header, 6);
                    header.addView(summary);
                  }
                  host.ui.gap(header, 14);
                  LinearLayout row = host.ui.row();
                  if (parent.type().equals("show"))
                    host.titleNavigation.linkButton(
                        row,
                        "Add to queue",
                        R.drawable.lucide_list_plus,
                        () -> host.addToQueue(parent.key(), parent.title(), false));
                  host.titleNavigation.addWatchedButton(row, parent, facts);
                  if (parent.type().equals("show")) host.titleNavigation.addCollectionLink(row, parent);
                  header.addView(row);
                  MainActivity.Grid shown = host.grid;
                  host.titleNavigation.restoreFocus(
                      () -> shown == null ? null : shown.cards.get(shown.start));
                  LinearLayout cast = host.ui.column();
                  header.addView(cast);
                  host.castPanel.fillCastRow(cast, parent);
                  if (parent.type().equals("show")) {
                    LinearLayout extras = host.ui.column();
                    header.addView(extras);
                    // Play trailer leads the show's buttons.
                    new TitleExtrasRow(host.screenHost)
                        .load(
                            parent.key(),
                            extras,
                            row,
                            0,
                            () -> shown == null ? null : shown.cards.get(shown.start));
                  }
                  host.ui.gap(header, 22);
                });
          } catch (Exception ignored) {
            // The listing still works without its parent's header.
          }
        });
  }

  private void compareArt(PlexCatalog.Entry entry) {
    TvPanel dialog =
        host.panel()
            .setTitle("Artwork quality")
            .setMessage("Loading 240px and 1440px artwork…")
            .setPositiveButton("Close", null)
            .create();
    dialog.show();
    dialog.setPanelSize(host.dp(820), host.dp(550));
    int request = host.generation;
    String origin = host.server(), secret = host.token();
    host.network.execute(
        () -> {
          try {
            Bitmap low = host.artwork(origin, secret, entry.thumb(), 240),
                high = host.artwork(origin, secret, entry.thumb(), 1440);
            host.runOnUiThread(
                () -> {
                  if (request != host.generation || !dialog.isShowing()) return;
                  LinearLayout images = host.ui.row();
                  images.setPadding(host.dp(24), host.dp(16), host.dp(24), host.dp(16));
                  for (Bitmap bitmap : List.of(low, high)) {
                    LinearLayout col = host.ui.column();
                    col.addView(
                        host.ui.text(bitmap.getWidth() + " × " + bitmap.getHeight(), 16, TvUi.MUTED));
                    ImageView v = new ImageView(host);
                    v.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    v.setImageBitmap(bitmap);
                    col.addView(v, new LinearLayout.LayoutParams(-1, host.dp(330)));
                    images.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
                  }
                  dialog.setMessage("");
                  dialog.setView(images);
                });
          } catch (Exception e) {
            host.runOnUiThread(
                () -> {
                  if (dialog.isShowing()) dialog.setMessage("Artwork couldn't be loaded.");
                });
          }
        });
  }
}
