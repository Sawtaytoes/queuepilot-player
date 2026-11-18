package dev.queuepilot.player;

import android.graphics.Bitmap;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Cast and crew: the title screen's row of people, the full cast panel, and each person's
 * photo, with an episode's cast filled in from its show.
 */
@androidx.media3.common.util.UnstableApi
final class CastPanel {
  private final MainActivity host;

  CastPanel(MainActivity host) {
    this.host = host;
  }

  /** The title's actors as a row of faces, the way the Plex detail page shows them. */
  void fillCastRow(LinearLayout area, PlexCatalog.Entry entry) {
    area.removeAllViews();
    List<JSONObject> actors = DetailFacts.actors(entry.roles());
    if (actors.isEmpty()) return;
    host.ui.gap(area, 26);
    area.addView(host.ui.title("Cast", 20));
    host.ui.gap(area, 12);
    LinearLayout row = host.horizontalIn(area);
    String origin = host.server(), secret = host.token();
    for (JSONObject actor : actors.subList(0, Math.min(12, actors.size()))) {
      LinearLayout card = host.ui.column();
      card.setGravity(Gravity.CENTER_HORIZONTAL);
      card.setPadding(host.dp(6), host.dp(8), host.dp(6), host.dp(8));
      ImageView face = host.avatar(84);
      card.addView(face);
      loadCastPhoto(face, actor.optString("thumb"), actor.optString("name"), origin, secret);
      host.ui.gap(card, 8);
      TextView name = host.ui.title(actor.optString("name"), 14);
      name.setGravity(Gravity.CENTER);
      name.setMaxLines(2);
      name.setEllipsize(TextUtils.TruncateAt.END);
      card.addView(name);
      TextView role = host.ui.text(actor.optString("role"), 12, TvUi.MUTED);
      role.setGravity(Gravity.CENTER);
      role.setMaxLines(2);
      role.setEllipsize(TextUtils.TruncateAt.END);
      card.addView(role);
      host.ui.focusable(
          card, () -> host.message(actor.optString("name"), actor.optString("role")), TvUi.BG);
      card.setContentDescription(actor.optString("name") + ". " + actor.optString("role"));
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(host.dp(124), -2);
      lp.rightMargin = host.dp(10);
      row.addView(card, lp);
    }
  }

  void showCast(PlexCatalog.Entry entry) {
    if (entry == null
        || TitleCast.inheritedKeys(entry.type(), entry.seasonKey(), entry.showKey()).isEmpty()) {
      castDialog(entry);
      return;
    }
    if (host.isCastLoading) return;
    host.isCastLoading = true;
    String origin = host.playingOrigin, secret = host.playingToken;
    int request = host.generation;
    host.network.execute(
        () -> {
          PlexCatalog.Entry complete = withInheritedCast(entry, origin, secret);
          host.runOnUiThread(
              () -> {
                host.isCastLoading = false;
                if (request != host.generation) return;
                if (host.playingEntry == entry) host.playingEntry = complete;
                castDialog(complete);
              });
        });
  }

  /**
   * Adds the season's and show's credits after the episode's own. Uses only the server
   * connection that is playing the episode; a missing parent leaves the episode's credits.
   */
  PlexCatalog.Entry withInheritedCast(
      PlexCatalog.Entry entry, String origin, String secret) {
    List<JSONObject> credits = new ArrayList<>();
    collectCredits(credits, entry.roles());
    for (String key : TitleCast.inheritedKeys(entry.type(), entry.seasonKey(), entry.showKey())) {
      try {
        JSONArray parents =
            PlexCatalog.list(
                PlexCatalog.get(
                    PlexCatalog.url(origin, "/library/metadata/" + key), secret, 16 * 1024 * 1024));
        if (parents.length() > 0)
          collectCredits(credits, parents.getJSONObject(0).optJSONArray("roles"));
      } catch (Exception ignored) {
      }
    }
    return entry.withCast(
        new JSONArray(
            TitleCast.distinct(
                credits,
                credit -> TitleCast.identity(credit.optString("kind"), credit.optString("name")))));
  }

  private static void collectCredits(List<JSONObject> credits, JSONArray roles) {
    if (roles == null) return;
    for (int i = 0; i < roles.length(); i++) {
      JSONObject credit = roles.optJSONObject(i);
      if (credit != null) credits.add(credit);
    }
  }

  private void loadCastPhoto(ImageView face, String thumb, String name) {
    loadCastPhoto(face, thumb, name, host.playingOrigin, host.playingToken);
  }

  private void loadCastPhoto(
      ImageView face, String thumb, String name, String origin, String secret) {
    boolean isExternal = TitleCast.isExternalPhoto(thumb);
    if (!isExternal && !(thumb.startsWith("/") && !thumb.startsWith("//"))) return;
    int request = host.generation;
    host.network.execute(
        () -> {
          Bitmap image = null;
          try {
            // The server resizes its own paths and proxies external portraits.
            image = host.artwork(origin, secret, thumb, 360);
          } catch (Exception ignored) {
          }
          if (image == null && isExternal) {
            try {
              // Credential-free fallback straight to the portrait host.
              image = MainActivity.portrait(AvatarImage.get(thumb));
            } catch (Exception ignored) {
            }
          }
          if (image == null) return;
          Bitmap loaded = image;
          host.runOnUiThread(
              () -> {
                if (request != host.generation) return;
                face.setImageBitmap(loaded);
                face.setContentDescription("Photo of " + name);
              });
        });
  }

  private void castDialog(PlexCatalog.Entry entry) {
    if (entry == null || entry.roles().length() == 0) {
      host.message("Title cast", "No cast information is available for this title.");
      return;
    }
    ScrollView scroll = new ScrollView(host);
    LinearLayout list = host.ui.column();
    list.setPadding(host.dp(20), host.dp(12), host.dp(20), host.dp(20));
    scroll.addView(list);
    LinearLayout row = null;
    for (int i = 0; i < entry.roles().length(); i++) {
      if (i % 4 == 0) {
        row = host.ui.row();
        list.addView(row);
        host.ui.gap(list, 20);
      }
      JSONObject actor = entry.roles().optJSONObject(i);
      if (actor == null) continue;
      LinearLayout card = host.ui.column();
      ImageView face = host.avatar(100);
      card.addView(face);
      loadCastPhoto(face, actor.optString("thumb"), actor.optString("name"));
      host.ui.gap(card, 10);
      card.addView(host.ui.title(actor.optString("name"), 15));
      host.ui.gap(card, 5);
      card.addView(host.ui.text(actor.optString("role"), 12, TvUi.MUTED));
      host.ui.focusable(
          card, () -> host.message(actor.optString("name"), actor.optString("role")), TvUi.PANEL);
      card.setContentDescription(actor.optString("name") + ". " + actor.optString("role"));
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(host.dp(128), -2);
      lp.setMargins(0, 0, host.dp(15), 0);
      row.addView(card, lp);
    }
    TvPanel dialog =
        host.panel()
            .setTitle(
                "Cast · "
                    + entry.primary()
                    + (entry.showTitle().isEmpty() ? "" : " · " + entry.secondary()))
            .setView(scroll)
            .setPositiveButton("Close", null)
            .create();
    dialog.show();
    dialog.setPanelSize(host.dp(Math.min(680, 84 + 143 * Math.min(4, entry.roles().length()))), -2);
  }
}
