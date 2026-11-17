package dev.queuepilot.player;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Loads the queue lists from QueuePilot. Until it answers, Home and Watching show the last
 * saved queues and keep asking: after 2, 4, 8, 15 and 30 seconds, when Android reports a
 * network, and when the app resumes.
 */
@androidx.media3.common.util.UnstableApi
final class QueueLoader {
  private final MainActivity host;

  QueueLoader(MainActivity host) {
    this.host = host;
  }

  /**
   * Like {@link #loadQueueLists}, but a screen that got the saved copy, or nothing, keeps asking
   * for the live list: after a short backoff ({@link QueueList#liveRetryDelayMs}), as soon as
   * Android reports a new default network, and when the app resumes. {@code loaded} runs again
   * with the live answer. Leaving the screen cancels the retry.
   */
  void loadLiveQueueLists(
      String origin,
      java.util.function.BiConsumer<JSONObject, JSONObject> loaded,
      java.util.function.Consumer<Exception> failed) {
    attemptLiveQueueLists(new LiveQueueRetry(origin, loaded, failed, host.generation));
  }

  private void attemptLiveQueueLists(LiveQueueRetry retry) {
    loadQueueLists(
        retry.origin,
        (all, shown) -> {
          if (retry.request != host.generation) return;
          String state = all.optBoolean("cached") ? "saved" : "live";
          if (state.equals("saved")) armQueueRetry(retry);
          else if (!retry.state.isEmpty()) android.util.Log.i(MainActivity.QUEUE_LOG, "Live queues after a retry");
          // The same saved copy again is not drawn again: that would move a viewer's focus.
          if (state.equals("saved") && state.equals(retry.state)) return;
          retry.state = state;
          retry.loaded.accept(all, shown);
        },
        error -> {
          if (retry.request != host.generation) return;
          android.util.Log.w(MainActivity.QUEUE_LOG, "Queue list and its saved copy unavailable", error);
          armQueueRetry(retry);
          if ("failed".equals(retry.state)) return;
          retry.state = "failed";
          retry.failed.accept(error);
        });
  }

  private void armQueueRetry(LiveQueueRetry retry) {
    if (host.queueRetry != null) host.handler.removeCallbacks(host.queueRetry);
    host.queueRetry = retry;
    long delay = QueueList.liveRetryDelayMs(retry.attempt);
    retry.attempt++;
    if (delay >= 0) host.handler.postDelayed(retry, delay);
    android.util.Log.i(
        MainActivity.QUEUE_LOG,
        "Queues not live; retry "
            + (delay >= 0 ? "in " + delay + " ms" : "on a new network or a resume"));
  }

  /** A new default network or a resume asks at once, and starts the backoff again. */
  void retryQueuesNow(String reason) {
    LiveQueueRetry retry = host.queueRetry;
    if (retry == null || retry.request != host.generation) return;
    android.util.Log.i(MainActivity.QUEUE_LOG, "Live queue retry now: " + reason);
    retry.attempt = 0;
    host.handler.removeCallbacks(retry);
    retry.run();
  }

  /** One screen's pending request for the live queue list. */
  final class LiveQueueRetry implements Runnable {
    final String origin;
    final java.util.function.BiConsumer<JSONObject, JSONObject> loaded;
    final java.util.function.Consumer<Exception> failed;
    final int request;
    int attempt;
    /** What the screen shows: "" before the first answer, then "saved", "failed", or "live". */
    String state = "";

    LiveQueueRetry(
        String origin,
        java.util.function.BiConsumer<JSONObject, JSONObject> loaded,
        java.util.function.Consumer<Exception> failed,
        int request) {
      this.origin = origin;
      this.loaded = loaded;
      this.failed = failed;
      this.request = request;
    }

    @Override
    public void run() {
      if (host.queueRetry != this) return;
      host.queueRetry = null;
      if (request == host.generation) attemptLiveQueueLists(this);
    }
  }

  /**
   * Saved queues ask again the moment Android has a default network. At boot the emulator's
   * Wi-Fi connected 23 s after the app opened, and the first request had failed with
   * "ConnectException: Failed to connect" in that gap.
   */
  void watchDefaultNetwork() {
    try {
      android.net.ConnectivityManager connectivity =
          host.getSystemService(android.net.ConnectivityManager.class);
      if (connectivity == null) return;
      host.networkCallback =
          new android.net.ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(android.net.Network network) {
              host.runOnUiThread(() -> retryQueuesNow("network available"));
            }
          };
      connectivity.registerDefaultNetworkCallback(host.networkCallback);
    } catch (RuntimeException e) {
      host.networkCallback = null;
      android.util.Log.w(MainActivity.QUEUE_LOG, "No network callback; saved queues retry on a timer", e);
    }
  }

  /**
   * Loads every queue, and the filtered list too when a people filter is active, so the screen
   * can say how many queues the filter hides.
   */
  private void loadQueueLists(
      String origin,
      java.util.function.BiConsumer<JSONObject, JSONObject> loaded,
      java.util.function.Consumer<Exception> failed) {
    Set<String> selected = new HashSet<>(host.prefs().getStringSet("queue-people", Set.of()));
    fetchQueues(
        origin,
        Set.of(),
        all -> {
          host.queuePeople = all.optJSONArray("people");
          if (host.queuePeople == null) host.queuePeople = new JSONArray();
          if (selected.isEmpty()) loaded.accept(all, all);
          else fetchQueues(origin, selected, shown -> loaded.accept(all, shown), failed);
        },
        failed);
  }

  /** Reads the queue list, falling back to the last saved copy when QueuePilot is unreachable. */
  void fetchQueues(
      String origin,
      Set<String> selected,
      java.util.function.Consumer<JSONObject> loaded,
      java.util.function.Consumer<Exception> failed) {
    host.network.execute(
        () -> {
          try {
            String path =
                "/api/native/queues?people="
                    + PlexAccount.encode(String.join(",", new TreeSet<>(selected)));
            JSONObject response;
            try {
              response = NativeQueueApi.request(origin, path, null, "", "");
              java.nio.file.Files.write(
                  host.cacheFile("native-tv", origin, path).toPath(),
                  response.toString().getBytes(StandardCharsets.UTF_8));
            } catch (Exception offline) {
              android.util.Log.w(MainActivity.QUEUE_LOG, "Queue list failed, showing the saved copy: " + path, offline);
              response =
                  new JSONObject(
                      new String(
                          java.nio.file.Files.readAllBytes(
                              host.cacheFile("native-tv", origin, path).toPath()),
                          StandardCharsets.UTF_8));
              response.put("cached", true);
            }
            JSONObject result = response;
            host.runOnUiThread(() -> loaded.accept(result));
          } catch (Exception error) {
            host.runOnUiThread(() -> failed.accept(error));
          }
        });
  }

  String snapshotKey() {
    return "queue-snapshot:" + host.activeId;
  }

  /** The last queue this profile played and the item it was on, or null. */
  JSONObject queueSnapshot() {
    try {
      String saved = host.prefs().getString(snapshotKey(), "");
      if (saved.isEmpty()) return null;
      JSONObject snapshot = new JSONObject(saved);
      return snapshot.optJSONObject("queue") == null || snapshot.optJSONObject("item") == null
          ? null
          : snapshot;
    } catch (Exception e) {
      return null;
    }
  }

  void saveQueueSnapshot(JSONObject item) {
    if (host.nativeQueue == null || item == null) return;
    try {
      JSONObject saved = new JSONObject();
      for (String key : new String[] {"ratingKey", "type", "title", "show", "season", "episode"})
        if (item.has(key)) saved.put(key, item.get(key));
      host.prefs()
          .edit()
          .putString(
              snapshotKey(),
              new JSONObject().put("queue", host.nativeQueue).put("item", saved).toString())
          .apply();
    } catch (Exception ignored) {
    }
  }
}
