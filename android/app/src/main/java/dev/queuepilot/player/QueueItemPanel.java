package dev.queuepilot.player;

import android.widget.LinearLayout;
import org.json.JSONObject;

/**
 * The panel for one full-queue item: Start here, then continue; Play only this item; and Mark
 * watched or unwatched.
 */
@androidx.media3.common.util.UnstableApi
final class QueueItemPanel {
  private final MainActivity host;

  QueueItemPanel(MainActivity host) {
    this.host = host;
  }

  /** The side panel for one full-queue item: start here, play only it, or mark it. */
  void queueItemPanel(JSONObject full, JSONObject entry, JSONObject item, JSONObject queue) {
    LinearLayout body = host.ui.column();
    TvPanel[] holder = new TvPanel[1];
    String entryKey = QueueDetail.text(entry, "entryKey");
    String startReason = QueueDetail.startUnavailable(full);
    if (startReason == null && entryKey.isEmpty())
      startReason = "This entry can't start from a chosen item.";
    if (startReason == null) {
      host.panelButton(
          body,
          "Start here, then continue",
          R.drawable.lucide_list_start,
          true,
          () -> {
            holder[0].dismiss();
            startFrom(queue, entryKey, item, "continue");
          });
      host.panelButton(
          body,
          "Play only this item",
          R.drawable.lucide_circle_play,
          false,
          () -> {
            holder[0].dismiss();
            startFrom(queue, entryKey, item, "only");
          });
      if (!item.optString("state").equals("watched"))
        host.panelNote(body, "Earlier items you have not watched stay in the queue.");
    } else host.panelNote(body, startReason);
    String markReason = QueueDetail.markUnavailable(item);
    boolean isWatched = item.optString("state").equals("watched");
    if (markReason == null && !entryKey.isEmpty())
      host.panelButton(
          body,
          isWatched ? "Mark unwatched" : "Mark watched",
          isWatched ? R.drawable.lucide_undo_2 : R.drawable.lucide_circle_check,
          false,
          () -> {
            holder[0].dismiss();
            markWatched(queue, entryKey, item, !isWatched);
          });
    else
      host.panelNote(
          body, markReason == null ? "This item can't be marked from a chosen item." : markReason);
    host.panelButton(body, "Close", R.drawable.lucide_x, false, () -> holder[0].dismiss());
    String section = QueueDetail.section(item);
    String message = QueueDetail.detail(item) + "\n" + QueueDetail.state(item);
    if (section != null && !QueueDetail.state(item).equals(section)) message += " · " + section;
    holder[0] =
        host.panel().setTitle(QueueDetail.rowTitle(item)).setMessage(message).setView(body).create();
    holder[0].show();
    holder[0].setPanelSize(host.dp(440), -2);
  }

  void startFrom(JSONObject queue, String entryKey, JSONObject item, String mode) {
    try {
      String ratingKey = QueueDetail.text(item, "ratingKey");
      JSONObject start =
          new JSONObject().put("entryKey", entryKey).put("ratingKey", ratingKey).put("mode", mode);
      String server = QueueDetail.text(item, "plexServer");
      if (!server.isEmpty()) start.put("plexServer", server);
      host.fullQueueFocus = ratingKey;
      host.startNativeQueue(queue, start);
    } catch (Exception error) {
      host.message("Queue playback", error.getMessage());
    }
  }

  /** Marks one item of a queue-owned entry, then draws the queue again from the server. */
  private void markWatched(JSONObject queue, String entryKey, JSONObject item, boolean isWatched) {
    String origin = host.prefs().getString("queue-origin", ""), setId = queue.optString("id");
    String ratingKey = QueueDetail.text(item, "ratingKey"), server = QueueDetail.text(item, "plexServer");
    host.fullQueueFocus = ratingKey;
    host.status.setText(isWatched ? "Marking watched…" : "Marking unwatched…");
    int request = host.generation;
    host.network.execute(
        () -> {
          try {
            JSONObject body =
                new JSONObject()
                    .put("entryKey", entryKey)
                    .put("ratingKey", ratingKey)
                    .put("isWatched", isWatched);
            if (!server.isEmpty()) body.put("plexServer", server);
            NativeQueueApi.request(
                origin,
                "/api/native/queues/" + PlexAccount.encode(setId) + "/watched",
                body,
                "X-Plex-Profile-Token",
                host.profileProof());
            host.runOnUiThread(
                () -> {
                  if (request == host.generation) host.render(host.current);
                });
          } catch (Exception error) {
            host.runOnUiThread(
                () -> {
                  if (request != host.generation) return;
                  if (host.askPinIfLocked(
                      error, () -> markWatched(queue, entryKey, item, isWatched))) return;
                  host.status.setText("The change wasn't saved.");
                  host.message(isWatched ? "Mark watched" : "Mark unwatched", error.getMessage());
                });
          }
        });
  }
}
