package dev.queuepilot.player;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Full queue: the whole ordered queue from QueuePilot, as a list or an artwork grid, which the
 * server chooses from the content, with the end of tonight's lineup marked.
 */
@androidx.media3.common.util.UnstableApi
final class FullQueueScreen {
  private final MainActivity host;

  FullQueueScreen(MainActivity host) {
    this.host = host;
  }

  // Full queue: the whole ordered queue from QueuePilot, in the layout the server chose for its
  // content (a list for episodes, an artwork grid for movies), with tonight's lineup marked.

  void openFullQueue(JSONObject queue) {
    String id = queue.optString("id");
    host.knownQueues.put(id, queue);
    host.isWatchedShown = false;
    host.fullQueueFocus = "";
    host.navigate(new Screen("fullqueue", id, queue.optString("title", "Movies & Shows"), null));
  }

  /** A queue the catalog has not listed yet, known only by its id and title. */
  JSONObject queueFor(String setId, String title) {
    JSONObject known = host.knownQueues.get(setId);
    if (known != null) return known;
    JSONObject queue = new JSONObject();
    try {
      queue.put("id", setId).put("title", title);
    } catch (Exception ignored) {
    }
    return queue;
  }

  /**
   * Explains why a QueuePilot screen cannot load, with the one action that fixes it. Returns false
   * when the screen can load.
   */
  boolean needsQueueConnection(LinearLayout actions, String what) {
    if (host.profile != null && !host.prefs().getString("queue-origin", "").isEmpty()) return false;
    boolean isSignedOut = host.profile == null;
    host.status.setText(
        isSignedOut
            ? "Sign in to see " + what + "."
            : "Connect your QueuePilot server to see " + what + ".");
    Runnable fix = isSignedOut ? () -> host.navigate(new Screen("signin")) : host.settingsScreen::queueSetup;
    host.ui.action(
        actions,
        isSignedOut ? "Sign in" : "Connect QueuePilot",
        isSignedOut ? R.drawable.lucide_circle_user_round : R.drawable.lucide_list_video,
        fix,
        true);
    host.focusFirst(actions.getChildAt(0));
    return true;
  }

  void fullQueue(String setId, String title) {
    host.shell("Watching");
    JSONObject queue = queueFor(setId, title);
    String audience = queue.has("people") ? host.queueCards.queueAudience(queue) : "";
    host.heading(audience.isEmpty() ? "Queue" : "Queue · " + audience, title);
    host.status.setText("Loading the whole queue…");
    LinearLayout actions = host.ui.row();
    host.content.addView(actions);
    LinearLayout notes = host.ui.column();
    host.content.addView(notes);
    LinearLayout area = host.ui.column();
    host.content.addView(area);
    if (needsQueueConnection(actions, "this queue")) return;
    // No profile gate: the server reads every queue as the profile the queue plays as. A queue
    // locked on the TV answers 423 until the household TV PIN is entered.
    String origin = host.prefs().getString("queue-origin", "");
    int request = host.generation;
    host.network.execute(
        () -> {
          try {
            String proof = host.profileProof();
            JSONObject full =
                NativeQueueApi.request(
                    origin,
                    "/api/native/queues/" + PlexAccount.encode(setId) + "/full",
                    null,
                    "X-Plex-Profile-Token",
                    proof);
            host.runOnUiThread(
                () -> {
                  if (request == host.generation) drawFullQueue(full, queue, actions, notes, area);
                });
          } catch (Exception error) {
            host.runOnUiThread(
                () -> {
                  if (request != host.generation) return;
                  if (host.tvPinPanel.askPinIfLocked(error, () -> host.render(host.current))) {
                    host.status.setText("This queue is locked on the TV. Enter the TV PIN to open it.");
                    host.ui.action(
                        actions,
                        "Enter PIN",
                        R.drawable.lucide_lock,
                        () -> host.tvPinPanel.askTvPin(() -> host.render(host.current)),
                        true);
                    host.focusFirst(actions.getChildAt(0));
                    return;
                  }
                  host.status.setText("The full queue couldn't load. " + error.getMessage());
                  host.ui.action(
                      actions, "Try again", R.drawable.lucide_rotate_cw, () -> host.render(host.current), true);
                  host.focusFirst(actions.getChildAt(0));
                });
          }
        });
  }

  private void drawFullQueue(
      JSONObject full,
      JSONObject queue,
      LinearLayout actions,
      LinearLayout notes,
      LinearLayout area) {
    QueueDetail.Summary summary = QueueDetail.summarize(full);
    host.status.setText(QueueDetail.summaryLine(summary));
    JSONObject next = null;
    JSONArray entries = full.optJSONArray("entries");
    for (int e = 0; next == null && entries != null && e < entries.length(); e++) {
      JSONArray items = entries.optJSONObject(e).optJSONArray("items");
      for (int i = 0; next == null && items != null && i < items.length(); i++)
        if (items.optJSONObject(i).optBoolean("isNext")) next = items.optJSONObject(i);
    }
    String nextLabel =
        next == null
            ? ""
            : "episode".equals(next.optString("type"))
                ? EpisodeLabel.secondary(
                    "episode",
                    QueueDetail.season(next),
                    QueueDetail.episode(next),
                    QueueDetail.title(next))
                : QueueDetail.primary(next);
    TextView play =
        host.ui.button(
            nextLabel.isEmpty() ? "Play queue" : "Continue · " + nextLabel,
            R.drawable.lucide_play,
            () -> host.nativeQueuePlayback.startNativeQueue(queue),
            true);
    play.setContentDescription(
        next == null ? "Play queue" : "Continue. " + QueueDetail.spoken(next));
    play.setTag("full-queue-play");
    actions.addView(play);
    boolean isGrid = "grid".equals(full.optString("layout"));
    if (!isGrid && summary.watched() > 0) {
      TextView[] fold = new TextView[1];
      fold[0] =
          host.ui.button(
              "",
              () -> {
                host.isWatchedShown = !host.isWatchedShown;
                foldLabel(fold[0], summary.watched());
                host.fullQueueItems.drawFullQueueItems(full, queue, area);
              },
              false);
      foldLabel(fold[0], summary.watched());
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
      lp.leftMargin = host.dp(12);
      actions.addView(fold[0], lp);
    }
    host.ui.gap(notes, 16);
    LinearLayout chips = host.ui.row();
    chip(chips, "Watched · " + summary.watched(), R.drawable.lucide_circle_check);
    chip(chips, "Tonight · " + summary.lineup(), R.drawable.lucide_play);
    chip(
        chips,
        "Later · " + Math.max(0, summary.items() - summary.watched() - summary.lineup()),
        R.drawable.lucide_clock);
    notes.addView(chips);
    if (!full.optBoolean("isWholeQueue", true))
      note(notes, "A Rules queue has no stored order, so this is its current lineup.");
    String startReason = QueueDetail.startUnavailable(full);
    if (startReason != null) note(notes, startReason);
    if (full.optBoolean("isTruncated"))
      note(notes, "Showing the first " + summary.items() + " items of a longer queue.");
    // The page opens at the viewer's place: the item chosen last, else the next item. Taking
    // focus from touch also scrolls it into view after a pointer tap.
    View target = host.fullQueueItems.drawFullQueueItems(full, queue, area);
    View focus = target == null ? play : target;
    focus.post(
        () -> {
          if (focus.isAttachedToWindow()) focus.requestFocusFromTouch();
        });
  }

  private void foldLabel(TextView fold, int watched) {
    fold.setText((host.isWatchedShown ? "Hide " : "Show ") + QueueDetail.count(watched, "watched item"));
    host.ui.withIcon(fold, host.isWatchedShown ? R.drawable.lucide_eye_off : R.drawable.lucide_eye);
    fold.setTag("watched-fold");
  }

  private void chip(LinearLayout row, String label, int icon) {
    TextView chip = host.ui.text(label, 13, TvUi.WHITE);
    chip.setPadding(host.dp(12), host.dp(7), host.dp(12), host.dp(7));
    chip.setBackground(host.ui.shape(TvUi.PANEL, 0, 16));
    host.ui.withIcon(chip, icon);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
    lp.rightMargin = host.dp(10);
    row.addView(chip, lp);
  }

  private void note(LinearLayout parent, String text) {
    host.ui.gap(parent, 12);
    TextView note = host.ui.text(text, 14, TvUi.MUTED);
    host.ui.withIcon(note, R.drawable.lucide_info);
    parent.addView(note);
  }

  View lineupDivider(JSONObject full, long lineupMs) {
    LinearLayout box = host.ui.column();
    box.setPadding(host.dp(20), host.dp(16), host.dp(20), host.dp(16));
    box.setBackground(host.ui.shape(TvTheme.ACCENT_SURFACE, TvTheme.ACCENT_BORDER, 9));
    TextView title = host.ui.title("Tonight's lineup ends here", 17);
    title.setTextColor(TvUi.ACCENT);
    box.addView(title);
    host.ui.gap(box, 8);
    box.addView(host.ui.text(QueueDetail.lineupNote(full, lineupMs), 14, TvUi.MUTED));
    box.setTag("lineup-end");
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.setMargins(0, host.dp(10), host.dp(14), host.dp(18));
    box.setLayoutParams(lp);
    return box;
  }
}
