package dev.queuepilot.player;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Queue playback: a QueuePilot queue played item by item as its own Plex profile, and leaving
 * the player for the full queue.
 */
@androidx.media3.common.util.UnstableApi
final class NativeQueuePlayback {
  private final MainActivity host;

  NativeQueuePlayback(MainActivity host) {
    this.host = host;
  }

  void startNativeQueue(JSONObject queue) {
    startNativeQueue(queue, null);
  }

  /**
   * Starts a queue session. {@code start} is null for the engine's own next item, or a chosen
   * item {@code {entryKey, ratingKey, plexServer?, mode}}; earlier unwatched items stay unwatched.
   */
  void startNativeQueue(JSONObject queue, JSONObject start) {
    if (host.profile == null) {
      host.message("Connect Plex", "Sign in with Plex before starting a queue.");
      return;
    }
    String origin = host.prefs().getString("queue-origin", "");
    host.status.setText("Preparing your queue…");
    int request = host.generation;
    host.network.execute(
        () -> {
          try {
            String proof = host.profileProof();
            host.progressNetwork.submit(host.playbackReports::flushNativeEvents).get();
            JSONObject body = new JSONObject().put("setId", queue.getString("id"));
            if (start != null) body.put("start", start);
            JSONObject plan =
                NativeQueueApi.request(
                    origin, "/api/native/sessions", body, "X-Plex-Profile-Token", proof);
            plan.put("profileProof", proof);
            host.runOnUiThread(
                () -> {
                  if (request != host.generation) return;
                  host.nativePlan = plan;
                  host.nativeQueue = queue;
                  // Remembered so a library title on the same server can name its poster too.
                  if (!plan.optString("homeServerId").isEmpty())
                    host.prefs()
                        .edit()
                        .putString("queue-home-server", plan.optString("homeServerId"))
                        .apply();
                  host.nativeStartMode = start == null ? "" : start.optString("mode");
                  host.nativeIndex = plan.optInt("cursor");
                  host.nativeSequence = plan.optLong("sequence");
                  loadNativeItem();
                });
          } catch (Exception error) {
            host.runOnUiThread(
                () -> {
                  if (request != host.generation) return;
                  if (host.tvPinPanel.askPinIfLocked(error, () -> startNativeQueue(queue, start))) return;
                  host.status.setText("This queue couldn't start.");
                  host.message("Queue playback", error.getMessage());
                });
          }
        });
  }

  void loadNativeItem() {
    host.advancing = true;
    host.handler.removeCallbacksAndMessages(null);
    host.playerScreen.releasePlayer();
    JSONArray items = host.nativePlan.optJSONArray("items");
    if (items == null || host.nativeIndex >= items.length()) {
      JSONObject previous = host.nativePlan;
      int continuationRequest = host.generation;
      String origin = host.prefs().getString("queue-origin", "");
      host.progressNetwork.execute(
          () -> {
            try {
              host.playbackReports.flushNativeEvents();
              JSONObject next =
                  NativeQueueApi.request(
                      origin,
                      "/api/native/sessions/" + previous.getString("id") + "/continue",
                      new JSONObject(),
                      "X-Native-Session-Key",
                      previous.getString("sessionKey"),
                      previous.optString("profileProof"));
              host.runOnUiThread(
                  () -> {
                    if (continuationRequest != host.generation || host.nativePlan != previous) return;
                    if (next.optBoolean("complete")) {
                      host.nativePlan = null;
                      if (host.nativeStartMode.equals("only")) {
                        // One chosen item has finished, not the queue: return to where it was
                        // chosen, and keep the queue's place on Home.
                        if (!host.history.isEmpty()) host.render(host.history.pop());
                        else host.render(new Screen("queues"));
                        return;
                      }
                      // A finished queue has nothing to continue on Home.
                      host.prefs().edit().remove(host.queueLoader.snapshotKey()).apply();
                      host.render(new Screen("queues"));
                    } else {
                      try {
                        next.put("profileProof", previous.optString("profileProof"));
                      } catch (Exception ignored) {
                      }
                      host.nativePlan = next;
                      host.nativeIndex = 0;
                      host.nativeSequence = 0;
                      loadNativeItem();
                    }
                  });
            } catch (Exception error) {
              host.runOnUiThread(
                  () -> {
                    if (continuationRequest != host.generation || host.nativePlan != previous) return;
                    host.message("Queue finished", error.getMessage());
                    host.nativePlan = null;
                    host.render(new Screen("queues"));
                  });
            }
          });
      return;
    }
    JSONObject item = items.optJSONObject(host.nativeIndex);
    host.queueLoader.saveQueueSnapshot(item);
    int request = host.generation;
    String defaultOrigin = host.server(), defaultToken = host.token();
    // A JSON null reads as the text "null" through optString, so test for it first.
    String itemServer =
        item.isNull("plexServer") || item.optString("plexServer").isEmpty()
            ? host.nativePlan.optString("homeServerId")
            : item.optString("plexServer");
    JSONObject grant = queueGrant(host.nativePlan, itemServer);
    boolean isQueueHome = itemServer.equals(host.nativePlan.optString("homeServerId"));
    JSONObject boundProfile = host.profile;
    // When Plex selects no streams, the queue profile's own plex.tv language settings decide. The
    // QueuePilot server sends them with the plan, since this TV holds no plex.tv token for another
    // profile. Without them, a queue that plays as the chosen profile uses the chosen profile's
    // settings; another profile's queue keeps Plex's selection, never the chosen profile's.
    JSONObject planLanguages = host.nativePlan.optJSONObject("languages");
    boolean isChosenProfile =
        grant == null || host.activeId.equals(host.nativePlan.optString("profileUuid"));
    java.util.concurrent.Callable<PlexTracks.Languages> queueLanguages =
        planLanguages != null
            ? () -> PlexTracks.Languages.fromUser(planLanguages)
            : isChosenProfile ? host.playbackMenus::chosenLanguages : null;
    host.network.execute(
        () -> {
          try {
            String origin = defaultOrigin, secret = defaultToken;
            if (grant != null) {
              // THE QUEUE'S OWN PROFILE PLAYS IT. The session handed over that profile's grant
              // for this server, so the item streams as the queue's profile, not the profile
              // this TV has chosen for library browsing. The chosen profile's connection is
              // reused when it is the same server; the grant's verified address otherwise.
              secret = grant.getString("token");
              if (!itemServer.equals(boundProfile.optString("serverId"))) {
                origin = PlexCatalog.base(grant.getString("baseUrl"));
                if (!ServerConnectionPolicy.allowed(origin, true, false))
                  throw new IllegalStateException("The queued server is unavailable");
              }
            } else if (!itemServer.isEmpty()
                && !itemServer.equals(boundProfile.optString("serverId"))) {
              JSONArray resources = host.auth.servers(boundProfile.getString("profileProof"));
              JSONObject resource = null;
              for (int i = 0; i < resources.length(); i++)
                if (resources.getJSONObject(i).optString("clientIdentifier").equals(itemServer))
                  resource = resources.getJSONObject(i);
              if (resource == null)
                throw new IllegalStateException("This profile cannot access the queued server");
              origin = "";
              secret = resource.getString("accessToken");
              JSONArray connections = resource.getJSONArray("connections");
              for (int i = 0; i < connections.length(); i++) {
                JSONObject c = connections.getJSONObject(i);
                String uri = c.optString("uri");
                if (ServerConnectionPolicy.allowed(
                    uri, c.optBoolean("local"), c.optBoolean("relay"))) {
                  try {
                    PlexCatalog.get(
                        PlexCatalog.url(uri, "/library/sections"), secret, 8 * 1024 * 1024);
                    origin = PlexCatalog.base(uri);
                    break;
                  } catch (Exception ignored) {
                  }
                }
              }
              if (origin.isEmpty())
                throw new IllegalStateException("The queued server is unavailable");
            }
            PlexCatalog.Entry entry =
                PlexCatalog.Entry.fromJson(
                    PlexCatalog.list(
                            PlexCatalog.get(
                                PlexCatalog.url(
                                    origin,
                                    "/library/metadata/"
                                        + item.getString("ratingKey")
                                        + "?includeMarkers=1&includeChapters=1"),
                                secret,
                                16 * 1024 * 1024))
                        .getJSONObject(0));
            if (entry.part().isEmpty())
              throw new IllegalStateException("This item has no direct playback source");
            String mediaOrigin = origin, mediaToken = secret;
            // Plex marks the queue profile's own choices, since the queue's grant asked. The
            // chosen profile's language settings are never applied to another profile's queue.
            PlaybackTracks tracks = PlaybackTracks.forEntry(entry, queueLanguages);
            byte[] artwork = PlaybackLauncher.nowPlayingArtwork(origin, secret, entry);
            String artworkUri = isQueueHome ? host.playbackLauncher.queueThumbUri(entry) : "";
            host.runOnUiThread(
                () -> {
                  if (request != host.generation) return;
                  host.playingEntry = entry;
                  host.playingArtwork = artwork;
                  host.playingArtworkUri = artworkUri;
                  host.playingOrigin = mediaOrigin;
                  host.playingToken = mediaToken;
                  host.playbackTracks = tracks;
                  host.sectionFloor = item.optLong("sectionStartMs", 0);
                  host.sectionEnd =
                      item.has("sectionEndMs") && !item.isNull("sectionEndMs")
                          ? item.optLong("sectionEndMs")
                          : -1;
                  host.playerScreen.startPlayer(
                      PlexCatalog.url(mediaOrigin, entry.part()),
                      mediaToken,
                      entry.title(),
                      entry.cast());
                  host.advancing = false;
                  host.player.seekTo(item.optLong("resumeMs", 0));
                });
          } catch (Exception error) {
            host.runOnUiThread(
                () -> {
                  if (request == host.generation) {
                    host.playbackReports.reportPlayback("error");
                    host.message("Queue playback", error.getMessage());
                  }
                });
          }
        });
  }

  /**
   * The queue profile's playback grant for {@code serverId}, from the session QueuePilot started,
   * or null when the server sent none (an older server; the chosen profile's access is used).
   */
  private static JSONObject queueGrant(JSONObject plan, String serverId) {
    JSONArray grants = plan == null ? null : plan.optJSONArray("grants");
    for (int i = 0; grants != null && i < grants.length(); i++) {
      JSONObject grant = grants.optJSONObject(i);
      if (grant != null
          && serverId.equals(grant.optString("serverId"))
          && !grant.optString("token").isEmpty()) return grant;
    }
    return null;
  }

  /** Leaves playback for the playing queue's full list; Back returns to where playback began. */
  void leaveForFullQueue(JSONObject queue) {
    if (host.player != null) host.playbackReports.reportPlayback("stopped");
    host.advancing = true;
    host.nativePlan = null;
    String id = queue.optString("id");
    host.knownQueues.put(id, queue);
    host.isWatchedShown = false;
    host.fullQueueFocus = host.playingEntry == null ? "" : host.playingEntry.key();
    Screen previous = host.history.peek();
    if (previous != null && previous.kind().equals("fullqueue") && previous.path().equals(id))
      host.render(host.history.pop());
    else host.render(new Screen("fullqueue", id, queue.optString("title", "Movies & Shows"), null));
  }
}
