package dev.queuepilot.player;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;
import java.util.function.Supplier;
import org.json.JSONArray;

/**
 * Where a title screen leads: an episode's show and season, a movie's or show's collections, and
 * marking a whole show or season watched or unwatched for the chosen profile.
 */
final class TitleNavigation {
  private final ScreenHost host;

  /** Made before the activity has its UI, so the UI is asked for each time it is used. */
  TitleNavigation(ScreenHost host) {
    this.host = host;
  }

  /** A button in a row of links, spaced from the one before it. */
  TextView linkButton(LinearLayout row, String label, int icon, Runnable action) {
    TextView button = host.ui().button(label, icon, action, false);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
    if (row.getChildCount() > 0) lp.leftMargin = host.ui().dp(12);
    row.addView(button, lp);
    return button;
  }

  /** Go to show and Go to season, for an episode. */
  void addParentLinks(LinearLayout links, PlexCatalog.Entry episode) {
    linkButton(links, "Go to show", R.drawable.lucide_tv, () -> goToParent(episode, true));
    linkButton(
        links, "Go to season", R.drawable.lucide_list_video, () -> goToParent(episode, false));
  }

  /**
   * Opens an episode's show or season. A listing may leave out the parent's key; the episode's
   * own record is read for it then.
   */
  private void goToParent(PlexCatalog.Entry episode, boolean isShow) {
    String key = isShow ? episode.showKey() : episode.seasonKey();
    if (!key.isEmpty()) {
      openParent(episode, key, isShow);
      return;
    }
    int request = host.generation();
    String origin = host.server(), secret = host.token();
    host.background(
        () -> {
          String found = "";
          try {
            JSONArray items =
                PlexCatalog.list(
                    PlexCatalog.get(
                        PlexCatalog.url(origin, "/library/metadata/" + episode.key()),
                        secret,
                        4 * 1024 * 1024));
            PlexCatalog.Entry full = PlexCatalog.Entry.fromJson(items.getJSONObject(0));
            found = isShow ? full.showKey() : full.seasonKey();
          } catch (Exception unavailable) {
            // Reported below.
          }
          String parent = found;
          host.activity()
              .runOnUiThread(
                  () -> {
                    if (request != host.generation()) return;
                    if (parent.isEmpty())
                      host.message(
                          isShow ? "Go to show" : "Go to season",
                          "Your server couldn't be reached, so this can't be opened now.");
                    else openParent(episode, parent, isShow);
                  });
        });
  }

  private void openParent(PlexCatalog.Entry episode, String key, boolean isShow) {
    String path = TitleLinks.childrenPath(key);
    if (path.isEmpty()) return;
    String title =
        isShow
            ? episode.showTitle().isEmpty() ? "Show" : episode.showTitle()
            : TitleLinks.watchedTitle("season", episode.seasonTitle(), episode.season());
    host.openLibrary(path, title);
  }

  /**
   * After controls arrive late (the title's record, a show's header), the remembered one takes
   * focus back: Back from a collection lands on Go to collection. Only while focus is still where
   * the screen first put it ({@code defaultFocus}), so the viewer's own move is never undone.
   */
  void restoreFocus(Supplier<View> defaultFocus) {
    View focused = host.activity().getCurrentFocus();
    if (focused != null && focused != defaultFocus.get()) return;
    View remembered = host.rememberedView();
    if (remembered != null && remembered != focused) remembered.requestFocus();
  }

  /** Go to collection: one collection opens at once, several open a chooser. */
  void addCollectionLink(LinearLayout links, PlexCatalog.Entry full) {
    List<TitleLinks.Collection> collections = TitleLinks.collections(full.facts());
    if (collections.isEmpty()) return;
    String section = full.facts().optString("librarySectionID");
    TextView button =
        linkButton(
            links,
            "Go to collection",
            R.drawable.lucide_layers,
            () -> {
              if (collections.size() == 1) {
                openCollection(section, collections.get(0));
                return;
              }
              String[] names = new String[collections.size()];
              for (int i = 0; i < names.length; i++) names[i] = collections.get(i).title();
              TvPanel chooser =
                  host.panel()
                      .setTitle("Go to collection")
                      .setMessage(full.primary() + " is in " + names.length + " collections.")
                      .setItems(names, (d, i) -> openCollection(section, collections.get(i)))
                      .setNegativeButton("Cancel", null)
                      .create();
              chooser.show();
              chooser.setPanelSize(host.ui().dp(420), -2);
            });
    button.setContentDescription(
        collections.size() == 1
            ? "Go to collection, " + collections.get(0).title()
            : "Go to collection, choose from " + collections.size());
  }

  /**
   * Opens a collection in its own order. The tag names the collection, so the section's list of
   * collections gives its ratingKey; without it, the section filtered to the collection is shown.
   */
  private void openCollection(String section, TitleLinks.Collection collection) {
    int request = host.generation();
    String origin = host.server(), secret = host.token();
    host.background(
        () -> {
          String path = "";
          try {
            if (section.matches("[0-9]+")) {
              JSONArray listed =
                  PlexCatalog.list(
                      PlexCatalog.get(
                          PlexCatalog.url(origin, "/library/sections/" + section + "/collections"),
                          secret,
                          8 * 1024 * 1024));
              String key = TitleLinks.collectionKey(listed, collection.title());
              if (!key.isEmpty()) path = TitleLinks.collectionPath(key);
            }
          } catch (Exception unavailable) {
            // The filtered section below still lists the members.
          }
          if (path.isEmpty()) path = TitleLinks.collectionFilterPath(section, collection.id());
          String target = path;
          host.activity()
              .runOnUiThread(
                  () -> {
                    if (request != host.generation()) return;
                    if (target.isEmpty())
                      host.message(collection.title(), "This collection couldn't be opened.");
                    else host.openLibrary(target, collection.title());
                  });
        });
  }

  /** Mark watched on a show's or season's header. */
  void addWatchedButton(LinearLayout row, PlexCatalog.Entry parent, LinearLayout facts) {
    // The panel reads the latest record, so its counts follow a mark made on this screen.
    PlexCatalog.Entry[] latest = {parent};
    linkButton(
        row, "Mark watched", R.drawable.lucide_circle_check, () -> watchedPanel(latest, facts));
  }

  /**
   * The panel says how many episodes change and for whom, and nothing changes until Watched or
   * Unwatched is chosen.
   */
  private void watchedPanel(PlexCatalog.Entry[] latest, LinearLayout facts) {
    PlexCatalog.Entry parent = latest[0];
    String what = TitleLinks.watchedTitle(parent.type(), parent.title(), parent.episode());
    TvPanel confirm =
        host.panel()
            .setTitle("Mark " + what)
            .setMessage(TitleLinks.watchedMessage(what, parent.facts(), host.profileName()))
            .setPositiveButton("Watched", (d, i) -> markAll(latest, what, true, facts))
            .setNeutralButton("Unwatched", (d, i) -> markAll(latest, what, false, facts))
            .setNegativeButton("Cancel", null)
            .create();
    confirm.show();
    confirm.setPanelSize(host.ui().dp(440), -2);
  }

  /** Marks every episode of a show or season for the chosen profile, then shows the new count. */
  private void markAll(
      PlexCatalog.Entry[] latest, String what, boolean isWatched, LinearLayout facts) {
    PlexCatalog.Entry parent = latest[0];
    int request = host.generation();
    String origin = host.server(), secret = host.token(), name = host.profileName();
    host.status((isWatched ? "Marking " : "Unmarking ") + what + "…");
    host.background(
        () -> {
          try {
            PlexCatalog.get(
                PlexCatalog.url(origin, TitleLinks.watchedPath(parent.key(), isWatched)),
                secret,
                1024 * 1024);
          } catch (Exception e) {
            host.activity()
                .runOnUiThread(
                    () -> {
                      if (request != host.generation()) return;
                      host.status("");
                      host.message(
                          "Mark " + what, "Your server couldn't be reached. Nothing was changed.");
                    });
            return;
          }
          PlexCatalog.Entry refreshed = null;
          try {
            JSONArray items =
                PlexCatalog.list(
                    PlexCatalog.get(
                        PlexCatalog.url(origin, "/library/metadata/" + parent.key()),
                        secret,
                        4 * 1024 * 1024));
            if (items.length() > 0) refreshed = PlexCatalog.Entry.fromJson(items.getJSONObject(0));
          } catch (Exception ignored) {
            // The change was made; the old count stays until the screen is opened again.
          }
          PlexCatalog.Entry shown = refreshed;
          host.activity()
              .runOnUiThread(
                  () -> {
                    if (request != host.generation()) return;
                    host.status(
                        what
                            + " is marked "
                            + (isWatched ? "watched" : "unwatched")
                            + " for "
                            + name
                            + ".");
                    if (shown != null) {
                      latest[0] = shown;
                      host.fillDetailFacts(facts, shown);
                    }
                  });
        });
  }
}
