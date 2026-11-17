package dev.queuepilot.player;

import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Locale;
import org.json.JSONObject;

/**
 * Home: the queue cards first, then the hero for the queue or title to resume, then the
 * continue row. Focus stays where it was when the cards redraw.
 */
@androidx.media3.common.util.UnstableApi
final class HomeScreen {
  private final MainActivity host;

  HomeScreen(MainActivity host) {
    this.host = host;
  }

  void drawHome() {
    host.shell("Home");
    host.heading(
        host.profile == null ? "DEMO · " + host.profileName() : "Welcome, " + host.profileName(),
        "What are we watching?");
    host.status.setText("Your people. Your queues. Your library.");
    LinearLayout cards = host.horizontal();
    int width = (host.mainWidth - 48) / 3;
    if (host.profile == null) {
      StoryOrder.Item next = host.demo.next(host.completed());
      cards.addView(
          host.ui.card(
              host.profileName() + " · Watching",
              "Ready when you are",
              next == null
                  ? "You have finished this demo sequence."
                  : "Next: Orbit Nine · " + next.label(),
              "View demo queue",
              () -> host.navigate(new Screen("demo")),
              width));
      cards.addView(
          host.ui.card(
              "Watching together",
              "Watch together",
              "Shared queues will use the QueuePilot engine's audience rules.",
              "Explore shared queues",
              () -> host.navigate(new Screen("queues")),
              width));
      cards.addView(
          host.ui.card(
              "Your library",
              "Make yourself at home",
              "Connect Plex with a code from your phone. Your library is discovered automatically.",
              "Sign in with Plex",
              () -> host.navigate(new Screen("signin")),
              width));
    } else {
      cards.addView(
          host.ui.card(
              "Your library",
              "Browse something new",
              host.profile.optString("serverName", "Your media server"),
              "Browse library",
              () -> host.navigate(new Screen("library", "/library/sections", "Your library", null)),
              width));
      homeQueueCards(cards, width);
    }
    if (host.profile == null || host.prefs().getString("queue-origin", "").isEmpty()) {
      host.ui.primaryCard(cards.getChildAt(0));
      host.focusFirst(cards.getChildAt(0));
    }
    host.section("Continue your story");
    if (host.profile == null) {
      host.demoCatalog.demoHero();
      host.section("Browse your library");
      host.demoCatalog.demoPosters();
    } else {
      JSONObject snapshot = host.queueLoader.queueSnapshot();
      if (snapshot != null) {
        host.content.addView(queueHero(snapshot));
        host.ui.gap(host.content, 16);
      }
      LinearLayout continuation = host.ui.column();
      host.content.addView(continuation);
      TextView loading = host.ui.text("Finding your next episode…", 16, TvUi.MUTED);
      continuation.addView(loading);
      host.fetch(
          "/library/onDeck",
          (entries, cached) -> {
            continuation.removeAllViews();
            if (entries.length() == 0)
              continuation.addView(
                  host.ui.text("Choose a title below to start watching.", 16, TvUi.MUTED));
            else
              continuation.addView(mediaHero(PlexCatalog.Entry.fromJson(entries.optJSONObject(0))));
          },
          () ->
              loading.setText(
                  "Continue watching is unavailable. You can still browse your libraries below."));
      host.section("Browse your library");
      LinearLayout libraries = host.horizontal();
      host.fetch(
          "/library/sections",
          (entries, cached) -> {
            for (int i = 0; i < entries.length(); i++) {
              PlexCatalog.Entry entry = PlexCatalog.Entry.fromJson(entries.optJSONObject(i));
              libraries.addView(
                  host.ui.card(
                      "Library",
                      entry.title(),
                      "Browse titles on " + host.profile.optString("serverName", "your server") + ".",
                      "Open library",
                      () -> host.navigate(new Screen("library", entry.children(), entry.title(), null)),
                      Math.min(250, (host.mainWidth - 48) / 3)));
            }
          },
          () ->
              libraries.addView(
                  host.ui.text("Server unavailable. Try again from Series or Movies.", 15, TvUi.MUTED)));
    }
  }

  /**
   * Queues come first on Home: every queue, grouped by the profile it plays as, each with its
   * audience as text. Without a QueuePilot connection a single card offers to connect one.
   */
  private void homeQueueCards(LinearLayout cards, int width) {
    String origin = host.prefs().getString("queue-origin", "");
    if (origin.isEmpty()) {
      cards.addView(
          host.ui.card(
              "QueuePilot",
              "Connect your queues",
              "Your QueuePilot watching queues will appear here.",
              "Connect QueuePilot",
              () -> host.navigate(new Screen("queues")),
              width),
          0);
      return;
    }
    int request = host.generation;
    // The rows are added where the placeholder sits, above Continue and the library rows.
    LinearLayout queueArea = host.ui.column();
    ViewGroup cardsParent = (ViewGroup) cards.getParent();
    ViewGroup outer = (ViewGroup) cardsParent.getParent();
    outer.addView(queueArea, outer.indexOfChild(cardsParent));
    TextView loading = host.ui.text("Finding your queues…", 15, TvUi.MUTED);
    queueArea.addView(loading);
    // A live answer that follows saved queues replaces them. Focus moves with it only when it
    // was on the replaced cards, on the card this method focused, or nowhere: a viewer who has
    // moved on to the library or the rail keeps their place.
    View[] placed = new View[1];
    host.queueLoader.loadLiveQueueLists(
        origin,
        (all, shown) -> {
          if (request != host.generation) return;
          // Decided before the old cards go: removing a focused card moves focus elsewhere.
          boolean isFocusOurs = isFocusOurs(placed[0], queueArea);
          queueArea.removeAllViews();
          View first = host.queueCards.renderQueueGroups(queueArea, all, shown, origin, true);
          if (first == null) first = cards.getChildAt(0);
          if (isFocusOurs) {
            placed[0] = first;
            host.focusFirst(first);
          }
        },
        error -> {
          if (request != host.generation) return;
          boolean isFocusOurs = isFocusOurs(placed[0], queueArea);
          if (loading.getParent() == null) {
            queueArea.removeAllViews();
            queueArea.addView(loading);
          }
          loading.setText("QueuePilot couldn't be reached. Your library is still here.");
          host.ui.primaryCard(cards.getChildAt(0));
          if (isFocusOurs) {
            placed[0] = cards.getChildAt(0);
            host.focusFirst(placed[0]);
          }
        });
  }

  /**
   * True on the first answer (placed is null), or when focus is nowhere, on the view this screen
   * placed it on, or inside {@code area}. A viewer who moved on keeps their place.
   */
  private boolean isFocusOurs(View placed, ViewGroup area) {
    if (placed == null) return true;
    View focused = host.getWindow().getDecorView().findFocus();
    return focused == null || focused == placed || isWithin(focused, area);
  }

  static boolean isWithin(View view, ViewGroup parent) {
    for (View v = view; v != null; v = v.getParent() instanceof View ? (View) v.getParent() : null)
      if (v == parent) return true;
    return false;
  }

  /**
   * Continues the profile's last queue. It names the queue and the item it was on. Resuming starts
   * a QueuePilot session, so it needs the QueuePilot server; the card says so rather than imply an
   * offline resume.
   */
  private View queueHero(JSONObject snapshot) {
    JSONObject queue = snapshot.optJSONObject("queue"), item = snapshot.optJSONObject("item");
    String type = item.optString("type", "episode"), title = item.optString("title");
    String show = item.optString("show");
    int season = item.isNull("season") ? EpisodeLabel.UNKNOWN : item.optInt("season", -1);
    int episode = item.isNull("episode") ? 0 : item.optInt("episode", 0);
    LinearLayout hero = host.ui.column();
    hero.setPadding(host.dp(24), host.dp(20), host.dp(24), host.dp(20));
    hero.setBackground(host.ui.shape(TvTheme.ACCENT_SURFACE, 0, 12));
    hero.addView(host.ui.text(queue.optString("title", "QueuePilot queue"), 13, TvUi.ACCENT));
    host.ui.gap(hero, 12);
    hero.addView(host.ui.title(EpisodeLabel.primary(type, title, show), 29));
    String line = EpisodeLabel.secondary(type, season, episode, title);
    if (!line.isEmpty()) {
      host.ui.gap(hero, 10);
      hero.addView(host.ui.text(line, 16, TvUi.WHITE));
    }
    host.ui.gap(hero, 10);
    hero.addView(
        host.ui.text(
            "Where this queue left off. Resuming asks your QueuePilot server for the queue.",
            14,
            TvUi.MUTED));
    host.ui.gap(hero, 14);
    TextView resume =
        host.ui.button("Resume queue", R.drawable.lucide_play, () -> host.startNativeQueue(queue), true);
    resume.setContentDescription(
        "Resume queue. "
            + queue.optString("title")
            + ". "
            + EpisodeLabel.spoken(type, title, show, season, episode));
    LinearLayout actions = host.ui.row();
    actions.addView(resume);
    TextView full = host.queueCards.fullQueueButton(queue);
    LinearLayout.LayoutParams fullGap = new LinearLayout.LayoutParams(-2, -2);
    fullGap.leftMargin = host.dp(12);
    actions.addView(full, fullGap);
    hero.addView(actions);
    return hero;
  }

  private View mediaHero(PlexCatalog.Entry entry) {
    LinearLayout hero = host.ui.row();
    hero.setPadding(host.dp(24), host.dp(20), host.dp(24), host.dp(20));
    hero.setBackground(host.ui.shape(TvTheme.SURFACE_RAISED, 0, 12));
    LinearLayout labels = host.ui.column();
    // The media server's own on-deck item: name that source, never imply it is a queue's next
    // item, and name the server rather than the provider.
    String source = host.profile == null ? "" : host.profile.optString("serverName");
    String eyebrow = source.isEmpty() ? "" : source.toUpperCase(Locale.ROOT) + " · ";
    labels.addView(host.ui.text(eyebrow + "CONTINUE WATCHING", 12, TvUi.ACCENT));
    host.ui.gap(labels, 12);
    labels.addView(host.ui.title(entry.primary(), 29));
    host.ui.gap(labels, 14);
    String line = MainActivity.episodeLine(entry, "");
    if (!line.isEmpty()) labels.addView(host.ui.text(line, 16, TvUi.MUTED));
    host.ui.gap(labels, 14);
    TextView view =
        host.ui.button(
            "View title",
            R.drawable.lucide_film,
            () -> host.navigate(new Screen("details", "", "", entry)),
            true);
    view.setContentDescription("View title. " + entry.spoken());
    labels.addView(view);
    hero.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
    ImageView image = new ImageView(host);
    image.setScaleType(ImageView.ScaleType.CENTER_CROP);
    boolean isShowPoster = !entry.posterThumb().equals(entry.thumb());
    hero.addView(image, new LinearLayout.LayoutParams(host.dp(isShowPoster ? 124 : 240), host.dp(185)));
    host.loadArt(image, entry.posterThumb(), 720);
    return hero;
  }
}
