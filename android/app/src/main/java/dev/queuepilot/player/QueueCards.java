package dev.queuepilot.player;

import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Queue cards: each queue with its audience as faces and text, whom it plays as, its lock, and
 * its Full queue action, in the server's order.
 */
@androidx.media3.common.util.UnstableApi
final class QueueCards {
  private final MainActivity host;

  QueueCards(MainActivity host) {
    this.host = host;
  }

  /**
   * Draws the filter notice and every queue. Home uses scrolling rows; Watching uses a
   * grid. Returns the first queue card, or null when no queue is shown.
   */
  View renderQueueGroups(
      LinearLayout area, JSONObject all, JSONObject shown, String origin, boolean isRow) {
    JSONArray allQueues = all.optJSONArray("queues"), shownQueues = shown.optJSONArray("queues");
    int total = allQueues == null ? 0 : allQueues.length();
    int count = shownQueues == null ? 0 : shownQueues.length();
    for (int i = 0; i < total; i++) {
      JSONObject queue = allQueues.optJSONObject(i);
      if (queue != null) host.knownQueues.put(queue.optString("id"), queue);
    }
    if (shown != all) {
      LinearLayout notice = host.ui.row();
      notice.setGravity(Gravity.CENTER_VERTICAL);
      TextView summary = host.ui.text(QueueList.filterSummary(count, total), 15, TvUi.WHITE);
      summary.setTag("queue-filter-summary");
      summary.setPadding(0, 0, host.dp(14), 0);
      notice.addView(summary);
      notice.addView(
          host.ui.button(
              "Clear",
              () -> {
                host.prefs().edit().remove("queue-people").apply();
                host.render(host.current);
              },
              false));
      area.addView(notice);
      host.ui.gap(area, 14);
    }
    if (all.optBoolean("cached")) {
      area.addView(host.ui.text("Saved queues · reconnect to start playback", 14, TvUi.MUTED));
      host.ui.gap(area, 10);
    }
    List<JSONObject> list = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      JSONObject queue = shownQueues.optJSONObject(i);
      if (queue != null) list.add(queue);
    }
    // EVERY QUEUE, IN THE SERVER'S ORDER, AS ONE GROUP. Nobody switches profile to use a queue:
    // the server plays each as the profile it names, which each card states as information.
    View first = null;
    int columns = host.columns(3), width = host.itemWidth(columns);
    LinearLayout row = null;
    for (int i = 0; i < list.size(); i++) {
      if (row == null || (!isRow && i % columns == 0)) {
        if (isRow) {
          HorizontalScrollView scroll = new HorizontalScrollView(host);
          scroll.setHorizontalScrollBarEnabled(false);
          scroll.setClipToPadding(false);
          row = host.ui.row();
          row.setPadding(host.dp(3), host.dp(3), host.dp(3), host.dp(3));
          scroll.addView(row);
          area.addView(scroll);
        } else {
          row = host.ui.row();
          area.addView(row);
        }
        host.ui.gap(area, 18);
      }
      LinearLayout card = queueCard(list.get(i), width, origin);
      row.addView(withFullQueue(card, list.get(i)));
      if (first == null) {
        first = card;
        host.ui.primaryCard(card);
      }
    }
    if (count == 0)
      area.addView(
          host.ui.text(
              total == 0
                  ? "No queues yet. Create one in QueuePilot."
                  : "No queues match these people. Choose Clear to show all " + total + ".",
              17,
              TvUi.MUTED));
    return first;
  }

  /** A queue card with its "Full queue" action beneath it, as one column in a row or grid. */
  private View withFullQueue(LinearLayout card, JSONObject queue) {
    LinearLayout column = host.ui.column();
    column.setLayoutParams(card.getLayoutParams());
    column.addView(card, new LinearLayout.LayoutParams(-1, -2));
    TextView full = fullQueueButton(queue);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
    lp.topMargin = host.dp(8);
    column.addView(full, lp);
    return column;
  }

  TextView fullQueueButton(JSONObject queue) {
    TextView full =
        host.ui.button("Full queue", R.drawable.lucide_list_ordered, () -> host.fullQueueScreen.openFullQueue(queue), false);
    // Titles repeat, so the spoken name carries the audience as the card does.
    full.setContentDescription(
        "Full queue. "
            + queue.optString("title", "Movies & Shows")
            + (queue.has("people") ? ". " + queueAudience(queue) : ""));
    return full;
  }

  private LinearLayout queueCard(JSONObject queue, int width, String origin) {
    String kind = queue.optString("kind").equals("rules") ? "Rules" : "Picks";
    boolean isLocked = queue.optBoolean("isLocked");
    LinearLayout card =
        host.ui.card(
            isLocked ? kind + " · Locked" : kind,
            isLocked
                ? R.drawable.lucide_lock
                : kind.equals("Rules") ? R.drawable.lucide_list_filter : R.drawable.lucide_sparkles,
            queue.optString("title", "Movies & Shows"),
            queueAudience(queue),
            "Play queue",
            () -> host.startNativeQueue(queue),
            width);
    if (isLocked) card.setTag("queue-locked");
    // Whom the queue plays as is information only: it never asks anybody to switch profile.
    String playsAs = QueueList.playsAs(queue.optString("playsAs"));
    if (!playsAs.isEmpty()) {
      TextView line = host.ui.text(playsAs, 13, TvUi.MUTED);
      line.setTag("queue-plays-as");
      line.setPadding(0, 0, 0, host.dp(10));
      View action = card.findViewWithTag("card-action");
      card.addView(line, card.indexOfChild(action));
    }
    LinearLayout faces = host.ui.row();
    JSONArray people = queue.optJSONArray("people");
    if (people != null)
      for (int j = 0; j < Math.min(4, people.length()); j++) {
        ImageView face = host.avatar(36);
        faces.addView(face);
        host.loadQueueAvatar(face, people.optString(j), origin);
      }
    card.addView(faces, 1);
    // Titles repeat, so the spoken name carries the audience and the profile too.
    card.setContentDescription(
        queue.optString("title", "Movies & Shows")
            + ". "
            + queueAudience(queue)
            + ". "
            + kind
            + (isLocked ? ". Locked" : "")
            + (playsAs.isEmpty() ? "" : ". " + playsAs)
            + ". Play queue");
    return card;
  }

  /** Who the queue is for, in the web app's text form: "Ada, Grace". */
  String queueAudience(JSONObject queue) {
    // The server's own audience line, the same text the web app prints, when it sends one.
    String audience = queue.optString("audience");
    if (!audience.isEmpty()) return audience;
    List<String> names = new ArrayList<>();
    JSONArray list = queue.optJSONArray("people");
    if (list != null)
      for (int i = 0; i < list.length(); i++) {
        String id = list.optString(i), name = id;
        for (int j = 0; host.queuePeople != null && j < host.queuePeople.length(); j++) {
          JSONObject person = host.queuePeople.optJSONObject(j);
          if (person != null && id.equals(person.optString("id")))
            name = person.optString("displayName", id);
        }
        names.add(name);
      }
    return QueueList.audience(names);
  }
}
