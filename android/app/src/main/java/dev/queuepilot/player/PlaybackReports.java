package dev.queuepilot.player;

import java.util.Set;
import java.util.UUID;
import org.json.JSONObject;

/**
 * Playback reports to Plex and QueuePilot: each event goes to an encrypted outbox in order,
 * and the outbox is sent off the main thread and retried.
 */
@androidx.media3.common.util.UnstableApi
final class PlaybackReports {
  private final MainActivity host;

  PlaybackReports(MainActivity host) {
    this.host = host;
  }

  private final java.util.concurrent.atomic.AtomicBoolean isFlushQueued =
      new java.util.concurrent.atomic.AtomicBoolean();

  /** One outbox for the process, so a recreated activity never holds a stale copy. */
  private static PendingEvents sharedPendingEvents;

  void reportPlayback(String state) {
    reportPlayback(state, !PendingEvents.isProgressTick(state));
  }

  /**
   * Reports playback. A queue's events go to the encrypted outbox and are sent in order; {@code
   * isDurable} writes the outbox to disk before sending, for an event that may be the last before
   * the process ends.
   */
  void reportPlayback(String state, boolean isDurable) {
    if (host.player == null || host.playingEntry == null) return;
    // An extra is never scrobbled and never moves a resume point; a queue never plays one.
    if (host.isPlayingExtra && host.nativePlan == null) return;
    long position = Math.max(0, host.player.getCurrentPosition()),
        duration = Math.max(0, host.playerScreen.playingDuration());
    String origin = host.playingOrigin, secret = host.playingToken, key = host.playingEntry.key();
    JSONObject plan = host.nativePlan;
    if (plan != null) {
      try {
        JSONObject event =
            new JSONObject()
                .put("id", UUID.randomUUID().toString())
                .put("sequence", ++host.nativeSequence)
                .put("index", host.nativeIndex)
                .put("state", state)
                .put("positionMs", position)
                .put("durationMs", duration);
        String queueOrigin = host.prefs().getString("queue-origin", "");
        JSONObject envelope =
            new JSONObject()
                .put("origin", queueOrigin)
                .put("sessionId", plan.getString("id"))
                .put("sessionKey", plan.getString("sessionKey"))
                .put("profileProof", plan.optString("profileProof"))
                .put("event", event);
        MainActivity.eventStore.execute(
            () -> {
              try {
                pendingEvents().add(envelope, isDurable);
              } catch (Exception ignored) {
                // The outbox could not be read or written; the event cannot be kept.
              }
              scheduleFlush();
            });
      } catch (Exception ignored) {
        // Includes a report after onDestroy shut the executors down.
      }
    } else {
      String client = host.prefs().getString("plex-client-id", "");
      host.progressNetwork.execute(
          () -> {
            try {
              String path =
                  "/:/timeline?type=video&ratingKey="
                      + PlexAccount.encode(key)
                      + "&key="
                      + PlexAccount.encode("/library/metadata/" + key)
                      + "&state="
                      + PlexAccount.encode(
                          Set.of("ended", "error").contains(state) ? "stopped" : state)
                      + "&time="
                      + position
                      + "&duration="
                      + duration
                      + "&X-Plex-Client-Identifier="
                      + PlexAccount.encode(client)
                      + "&X-Plex-Device-Name="
                      + PlexAccount.encode(host.deviceName)
                      + "&X-Plex-Product=QueuePilot%20TV&X-Plex-Platform=Android";
              PlexCatalog.get(PlexCatalog.url(origin, path), secret, 1024 * 1024);
            } catch (Exception ignored) {
            }
          });
    }
  }

  private PendingEvents pendingEvents() {
    synchronized (MainActivity.class) {
      if (sharedPendingEvents == null) {
        ProfileStore secrets = new ProfileStore(host.getApplicationContext());
        sharedPendingEvents =
            new PendingEvents(
                new PendingEvents.Storage() {
                  @Override
                  public String load() throws Exception {
                    return secrets.nativeEvents();
                  }

                  @Override
                  public void save(String json, boolean isDurable) throws Exception {
                    secrets.nativeEvents(json, isDurable);
                  }
                },
                System::currentTimeMillis);
      }
      return sharedPendingEvents;
    }
  }

  /** Sends the outbox soon. A send already waiting to start covers this request. */
  void scheduleFlush() {
    if (!isFlushQueued.compareAndSet(false, true)) return;
    try {
      host.progressNetwork.execute(
          () -> {
            isFlushQueued.set(false);
            flushNativeEvents();
          });
    } catch (java.util.concurrent.RejectedExecutionException closed) {
      // onDestroy shut the sender down; the next start sends the outbox.
      isFlushQueued.set(false);
    }
  }

  /** Waits until every event reported so far is in the outbox. */
  private void awaitQueuedEvents() {
    try {
      MainActivity.eventStore.submit(() -> {}).get();
    } catch (Exception interrupted) {
      // The sender is stopping; the next start sends what is saved.
    }
  }

  /**
   * Waits on the main thread, at most {@code 1 s}, for the events just reported to reach the disk.
   * Only for leaving the screen: Android may end a stopped app's process at any time after.
   */
  void awaitSavedEvents() {
    try {
      MainActivity.eventStore.submit(() -> {}).get(1, java.util.concurrent.TimeUnit.SECONDS);
    } catch (Exception slow) {
      // The write continues in the background.
    }
  }

  /** Sends the outbox in order. Runs on {@link #progressNetwork}. */
  void flushNativeEvents() {
    // A continuation or a new session must follow the events reported before it, such as the
    // "ended" that advances the server's cursor.
    awaitQueuedEvents();
    PendingEvents.Drained drained;
    try {
      drained =
          pendingEvents()
              .drain(
                  record ->
                      NativeQueueApi.request(
                          record.optString("origin"),
                          "/api/native/sessions/" + record.optString("sessionId") + "/events",
                          record.optJSONObject("event"),
                          "X-Native-Session-Key",
                          record.optString("sessionKey"),
                          record.optString("profileProof")),
                  error ->
                      error instanceof NativeQueueApi.Failure
                          ? ((NativeQueueApi.Failure) error).status
                          : 0);
    } catch (Exception unreadable) {
      return;
    }
    String message =
        drained.isWaiting
            ? "Progress is saved locally and waiting to sync."
            : drained.isRejected
                ? "Some saved history needs review. Its playback session is no longer valid."
                : null;
    if (message != null)
      host.runOnUiThread(
          () -> {
            if (host.player != null && host.status != null) host.status.setText(message);
          });
  }
}
