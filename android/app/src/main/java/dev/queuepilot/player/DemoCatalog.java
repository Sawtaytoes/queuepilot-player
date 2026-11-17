package dev.queuepilot.player;

import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import java.util.Set;

/**
 * The fictional demo catalog, for reviewing the native layout without an account: its hero,
 * its drawn posters, and its one season.
 */
@androidx.media3.common.util.UnstableApi
final class DemoCatalog {
  private final MainActivity host;

  DemoCatalog(MainActivity host) {
    this.host = host;
  }

  void demoHero() {
    StoryOrder.Item next = host.demo.next(host.completed());
    FrameLayout hero = new FrameLayout(host);
    hero.setBackground(host.ui.shape(TvTheme.SURFACE_RAISED, 0, 14));
    hero.setClipToOutline(true);
    FrameLayout.LayoutParams art = new FrameLayout.LayoutParams(host.dp(host.mainWidth / 2), -1, Gravity.END);
    hero.addView(host.ui.demoArt(0), art);
    LinearLayout labels = host.ui.column();
    labels.setPadding(host.dp(30), host.dp(26), host.dp(30), host.dp(24));
    labels.addView(host.ui.text("CONTINUE IN STORY ORDER · DEMO", 12, TvUi.ACCENT));
    host.ui.gap(labels, 12);
    labels.addView(host.ui.title("Orbit Nine", 34));
    host.ui.gap(labels, 12);
    labels.addView(
        host.ui.text(
            next == null ? "Demo sequence complete" : "Season 1 · " + next.label(),
            16,
            TvUi.MUTED));
    host.ui.gap(labels, 20);
    LinearLayout actions = host.ui.row();
    labels.addView(actions);
    host.ui.action(actions, "View season", () -> host.navigate(new Screen("demo")), true);
    hero.addView(labels, new FrameLayout.LayoutParams(host.dp(host.mainWidth * 2 / 3), -2));
    host.content.addView(hero, new LinearLayout.LayoutParams(-1, host.dp(235)));
  }

  void demoPosters() {
    String[] titles = {
      "Orbit Nine", "The Last Beacon", "Glass City", "Wild Current", "After the Rain"
    };
    LinearLayout row = host.horizontal();
    for (int i = 0; i < titles.length; i++) {
      final int index = i;
      LinearLayout poster = host.ui.column();
      poster.setPadding(host.dp(3), host.dp(3), host.dp(3), host.dp(9));
      poster.addView(host.ui.demoArt(i), new LinearLayout.LayoutParams(-1, host.dp(240)));
      host.ui.gap(poster, 10);
      poster.addView(host.ui.title(titles[i], 16));
      host.ui.focusable(
          poster,
          () -> {
            if (index == 0) host.navigate(new Screen("demo"));
            else
              host.message(
                  titles[index],
                  "This is fictional demo artwork. Sign in with Plex to browse and play your"
                      + " library.");
          },
          TvUi.BG,
          0);
      LinearLayout.LayoutParams lp =
          new LinearLayout.LayoutParams(host.dp(host.itemWidth(host.columns(5))), -2);
      lp.rightMargin = host.dp(14);
      row.addView(poster, lp);
    }
  }

  void demoSeason() {
    host.shell("Series");
    host.heading("Fictional demo · Season 1", "Orbit Nine");
    host.status.setText("Specials stay in viewing order. Episode numbers stay the same.");
    LinearLayout row = host.horizontal();
    Set<String> watched = host.completed();
    StoryOrder.Item next = host.demo.next(watched);
    for (StoryOrder.Item item : host.demo.items())
      row.addView(
          host.ui.card(
              watched.contains(item.positionKey())
                  ? "Watched"
                  : next != null && item.positionKey().equals(next.positionKey())
                      ? "Up next"
                      : "Season 1",
              item.label(),
              item.title(),
              "View demo episode",
              () -> {
                TvPanel d =
                    host.panel()
                        .setTitle("Orbit Nine · " + item.label() + " · " + item.title())
                        .setMessage(
                            "This fictional sequence demonstrates ordering and profile progress. It"
                                + " doesn't play library media.")
                        .setPositiveButton(
                            "Mark watched",
                            (dialog, which) -> {
                              Set<String> done = host.completed();
                              done.add(item.positionKey());
                              host.prefs().edit().putStringSet("done:" + host.activeId, done).apply();
                              host.render(new Screen("demo"));
                            })
                        .setNegativeButton("Back", null)
                        .create();
                d.show();
                d.setPanelSize(host.dp(540), -2);
              },
              (host.mainWidth - 48) / 3));
    host.focusFirst(row.getChildAt(0));
    host.ui.gap(host.content, 24);
    host.content.addView(
        host.ui.button(
            "Reset demo progress",
            () -> {
              host.prefs().edit().remove("done:" + host.activeId).apply();
              host.render(new Screen("demo"));
            },
            false));
  }
}
