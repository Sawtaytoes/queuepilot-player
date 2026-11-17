package dev.queuepilot.player;

import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * History: every queue's watching, by queue, then by sitting (plays less than 75 minutes
 * apart), then what was watched in it, over 7, 30, 90, or 365 days.
 */
@androidx.media3.common.util.UnstableApi
final class HistoryScreen {
  private final MainActivity host;

  HistoryScreen(MainActivity host) {
    this.host = host;
  }

  // Watch history: queue, then sitting, then what was watched in it (design 2B).

  void history() {
    host.shell("History");
    host.heading("Every queue · Watch history", "History");
    host.status.setText("Loading your history…");
    LinearLayout actions = host.ui.row();
    host.content.addView(actions);
    if (host.fullQueueScreen.needsQueueConnection(actions, "your watch history")) return;
    TextView period =
        host.ui.button(
            QueueDetail.days(host.historyDays), R.drawable.lucide_calendar, this::historyPeriod, false);
    period.setContentDescription("Period, " + QueueDetail.days(host.historyDays));
    actions.addView(period);
    host.ui.gap(host.content, 16);
    TextView scope =
        host.ui.text(
            "Plays of every queue on this TV, and plays in other apps when a queue tracks them."
                + " Newest first.",
            14,
            TvUi.MUTED);
    host.ui.withIcon(scope, R.drawable.lucide_info);
    host.content.addView(scope);
    LinearLayout area = host.ui.column();
    host.content.addView(area);
    String origin = host.prefs().getString("queue-origin", "");
    int request = host.generation, days = host.historyDays;
    host.network.execute(
        () -> {
          try {
            JSONObject catalog = null;
            try {
              catalog = NativeQueueApi.request(origin, "/api/native/queues?people=", null, "", "");
            } catch (Exception ignored) {
              // The history still reads without the catalog; only the start actions need it.
            }
            JSONObject result =
                NativeQueueApi.request(
                    origin,
                    "/api/native/history?days=" + days,
                    null,
                    "X-Plex-Profile-Token",
                    host.profileProof());
            JSONObject listed = catalog;
            host.runOnUiThread(
                () -> {
                  if (request != host.generation) return;
                  JSONArray queues = listed == null ? null : listed.optJSONArray("queues");
                  for (int i = 0; queues != null && i < queues.length(); i++) {
                    JSONObject queue = queues.optJSONObject(i);
                    if (queue != null) host.knownQueues.put(queue.optString("id"), queue);
                  }
                  drawHistory(result, area, period, days);
                  int hidden = result.optInt("hiddenLockedQueues");
                  if (hidden > 0) {
                    TextView locked =
                        host.ui.button(
                            hidden == 1
                                ? "1 locked queue is not shown · Enter PIN"
                                : hidden + " locked queues are not shown · Enter PIN",
                            R.drawable.lucide_lock,
                            () -> host.askTvPin(() -> host.render(host.current)),
                            false);
                    locked.setTag("history-locked");
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                    lp.leftMargin = host.dp(12);
                    actions.addView(locked, lp);
                  }
                });
          } catch (Exception error) {
            host.runOnUiThread(
                () -> {
                  if (request != host.generation) return;
                  host.status.setText("Your history couldn't load. " + error.getMessage());
                  host.ui.action(
                      actions, "Try again", R.drawable.lucide_rotate_cw, () -> host.render(host.current), true);
                  host.focusFirst(period);
                });
          }
        });
  }

  private void historyPeriod() {
    int[] choices = {7, 30, 90, 365};
    String[] labels = new String[choices.length];
    int selected = -1;
    for (int i = 0; i < choices.length; i++) {
      labels[i] = QueueDetail.days(choices[i]);
      if (choices[i] == host.historyDays) selected = i;
    }
    TvPanel dialog =
        host.panel()
            .setTitle("Show history for")
            .setSelected(selected)
            .setItems(
                labels,
                (d, i) -> {
                  host.historyDays = choices[i];
                  host.render(host.current);
                })
            .setNegativeButton("Cancel", null)
            .create();
    dialog.show();
    dialog.setPanelSize(host.dp(330), -2);
  }

  private void drawHistory(JSONObject result, LinearLayout area, View period, int days) {
    JSONArray queues = result.optJSONArray("queues");
    int queueCount = queues == null ? 0 : queues.length(), sittings = 0;
    for (int q = 0; q < queueCount; q++) {
      JSONArray list = queues.optJSONObject(q).optJSONArray("sittings");
      sittings += list == null ? 0 : list.length();
    }
    host.status.setText(
        queueCount == 0
            ? "Nothing watched in the " + QueueDetail.days(days).toLowerCase(Locale.US) + "."
            : QueueDetail.count(sittings, "sitting")
                + " in "
                + QueueDetail.count(queueCount, "queue")
                + " · "
                + QueueDetail.days(days));
    long now = System.currentTimeMillis();
    java.time.ZoneId zone = java.time.ZoneId.systemDefault();
    int columns = host.columns(3), width = host.itemWidth(columns);
    View first = null;
    for (int q = 0; q < queueCount; q++) {
      JSONObject listed = queues.optJSONObject(q);
      String setId = listed.optString("setId"), title = listed.optString("title");
      JSONObject queue = host.fullQueueScreen.queueFor(setId, title);
      host.ui.gap(area, 26);
      LinearLayout heading = host.ui.row();
      heading.setGravity(Gravity.CENTER_VERTICAL);
      LinearLayout names = host.ui.column();
      TextView name = host.ui.title(title, 23);
      name.setTag("history-queue");
      names.addView(name);
      host.ui.gap(names, 6);
      JSONArray list = listed.optJSONArray("sittings");
      int count = list == null ? 0 : list.length();
      names.addView(
          host.ui.text(
              QueueDetail.count(count, "sitting")
                  + " · last watched "
                  + QueueDetail.lastWatched(listed.optLong("lastWatchedAt"), now, zone),
              14,
              TvUi.MUTED));
      heading.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
      heading.addView(host.queueCards.fullQueueButton(queue));
      area.addView(heading);
      host.ui.gap(area, 14);
      LinearLayout row = null;
      for (int s = 0; s < count; s++) {
        if (s % columns == 0) {
          row = host.ui.row();
          area.addView(row);
          host.ui.gap(area, 14);
        }
        View card = sittingCard(listed, list.optJSONObject(s), queue, width, now, zone);
        row.addView(card);
        if (first == null) first = card;
      }
    }
    if (queueCount == 0)
      area.addView(
          host.ui.text(
              "Queue plays will appear here, grouped by queue and by sitting.", 17, TvUi.MUTED));
    host.focusFirst(first == null ? period : first);
  }

  private View sittingCard(
      JSONObject listed,
      JSONObject sitting,
      JSONObject queue,
      int width,
      long now,
      java.time.ZoneId zone) {
    LinearLayout card = host.ui.column();
    card.setPadding(host.dp(20), host.dp(18), host.dp(20), host.dp(18));
    JSONArray items = sitting.optJSONArray("items");
    int count = items == null ? 0 : items.length();
    LinearLayout pictures = host.ui.row();
    Set<String> pictured = new HashSet<>();
    for (int i = 0; i < count && pictured.size() < 3; i++) {
      JSONObject item = items.optJSONObject(i);
      String ratingKey = QueueDetail.text(item, "ratingKey"), server = QueueDetail.text(item, "plexServer");
      boolean isHome = server.isEmpty() || (host.profile != null && server.equals(host.profile.optString("serverId")));
      if (!isHome || !ratingKey.matches("\\d+") || !pictured.add(ratingKey)) continue;
      ImageView image = new ImageView(host);
      image.setScaleType(ImageView.ScaleType.CENTER_CROP);
      image.setBackgroundColor(TvTheme.SURFACE_SUNKEN);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(host.dp(112), host.dp(63));
      lp.rightMargin = host.dp(6);
      pictures.addView(image, lp);
      host.loadArt(image, "/library/metadata/" + ratingKey + "/thumb", 240);
    }
    if (pictures.getChildCount() > 0) {
      card.addView(pictures);
      host.ui.gap(card, 14);
    }
    boolean isApproximate = QueueDetail.isApproximate(sitting);
    String span =
        QueueDetail.sittingSpan(
            sitting.optLong("startedAt"), sitting.optLong("endedAt"), isApproximate, now, zone);
    TextView eyebrow = host.ui.text(span.toUpperCase(Locale.US), 12, TvUi.ACCENT);
    eyebrow.setTag("sitting-span");
    card.addView(eyebrow);
    host.ui.gap(card, 10);
    TextView name = host.ui.title(listed.optString("title"), 21);
    name.setMaxLines(2);
    name.setEllipsize(TextUtils.TruncateAt.END);
    card.addView(name);
    host.ui.gap(card, 10);
    List<String> spoken = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      JSONObject item = items.optJSONObject(i);
      String source = QueueDetail.source(item), label = QueueDetail.historyItem(item);
      TextView line = host.ui.text(label + (source.isEmpty() ? "" : " · " + source), 15, TvUi.WHITE);
      host.ui.withIcon(
          line,
          item.optString("state").equals("finished")
              ? R.drawable.lucide_circle_check
              : R.drawable.lucide_clock);
      line.setMaxLines(2);
      line.setEllipsize(TextUtils.TruncateAt.END);
      line.setTag("sitting-item");
      card.addView(line);
      host.ui.gap(card, 6);
      spoken.add(
          (item.optString("state").equals("finished") ? "Finished, " : "Partly watched, ")
              + label);
    }
    host.ui.gap(card, 6);
    String footer =
        QueueDetail.sittingFooter(sitting) + (isApproximate ? " · times are approximate" : "");
    card.addView(host.ui.text(footer, 13, TvUi.MUTED));
    host.ui.focusable(card, () -> sittingPanel(listed, sitting, queue, span), TvUi.PANEL, 12);
    card.setContentDescription(
        listed.optString("title") + ". " + span + ". " + String.join(". ", spoken) + ". " + footer);
    card.setTag("sitting");
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(host.dp(width), -2);
    lp.rightMargin = host.dp(14);
    card.setLayoutParams(lp);
    return card;
  }

  /** Continue the queue, play one item from the sitting again, or open the full queue. */
  private void sittingPanel(JSONObject listed, JSONObject sitting, JSONObject queue, String span) {
    LinearLayout body = host.ui.column();
    TvPanel[] holder = new TvPanel[1];
    host.panelButton(
        body,
        "Continue the queue",
        R.drawable.lucide_play,
        true,
        () -> {
          holder[0].dismiss();
          host.startNativeQueue(queue);
        });
    JSONArray items = sitting.optJSONArray("items");
    if ("rules".equals(queue.optString("kind")))
      host.panelNote(
          body, "A Rules queue chooses its own order each time, so it always starts from the top.");
    else {
      Set<String> offered = new HashSet<>();
      for (int i = 0; items != null && i < items.length() && offered.size() < 6; i++) {
        JSONObject item = items.optJSONObject(i);
        String entryKey = QueueDetail.text(item, "entryKey");
        String key = QueueDetail.text(item, "plexServer") + ":" + QueueDetail.text(item, "ratingKey");
        if (entryKey.isEmpty() || !offered.add(key)) continue;
        String label = QueueDetail.secondary(item);
        if (label.isEmpty()) label = QueueDetail.primary(item);
        host.panelButton(
            body,
            "Play " + label,
            R.drawable.lucide_circle_play,
            false,
            () -> {
              holder[0].dismiss();
              host.itemPanel.startFrom(queue, entryKey, item, "only");
            });
      }
    }
    host.panelButton(
        body,
        "Open the full queue",
        R.drawable.lucide_list_ordered,
        false,
        () -> {
          holder[0].dismiss();
          host.fullQueueScreen.openFullQueue(queue);
        });
    host.panelButton(body, "Close", R.drawable.lucide_x, false, () -> holder[0].dismiss());
    holder[0] = host.panel().setTitle(listed.optString("title")).setMessage(span).setView(body).create();
    holder[0].show();
    holder[0].setPanelSize(host.dp(460), -2);
  }
}
