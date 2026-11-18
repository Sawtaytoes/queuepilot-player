package dev.queuepilot.player;

import android.graphics.BitmapFactory;
import android.view.View;
import org.json.JSONArray;

/**
 * Starting playback of a library title or an extra, with the metadata and poster that the
 * media session shows for it.
 */
@androidx.media3.common.util.UnstableApi
final class PlaybackLauncher {
  private final MainActivity host;

  PlaybackLauncher(MainActivity host) {
    this.host = host;
  }

  /**
   * Plays an extra and comes back to this screen. An extra reports nothing to Plex, so it is
   * never marked watched, never moves the title's resume point, and never touches a queue.
   */
  void playExtra(PlexCatalog.Entry extra, View from) {
    if (host.current != null && from != null) host.focusMemory.put(host.current, host.focusKey(from));
    int request = host.generation;
    String origin = host.server(), secret = host.token();
    host.network.execute(
        () -> {
          try {
            PlexCatalog.Entry detail = extra;
            if (detail.part().isEmpty()) {
              JSONArray items =
                  PlexCatalog.list(
                      PlexCatalog.get(
                          PlexCatalog.url(origin, "/library/metadata/" + extra.key()),
                          secret,
                          4 * 1024 * 1024));
              detail = PlexCatalog.Entry.fromJson(items.getJSONObject(0));
            }
            String url = PlexCatalog.url(origin, detail.part());
            PlexCatalog.Entry playing = detail;
            host.runOnUiThread(
                () -> {
                  if (request != host.generation) return;
                  host.nativePlan = null;
                  host.playingEntry = playing;
                  host.playingArtwork = null;
                  host.playingArtworkUri = "";
                  host.playingOrigin = origin;
                  host.playingToken = secret;
                  host.sectionFloor = 0;
                  host.sectionEnd = -1;
                  host.advancing = false;
                  host.isPlayingExtra = true;
                  // An extra plays its file's own tracks: never the last title's choices or
                  // subtitle files, and a track change in it is not saved to Plex.
                  host.playbackTracks = null;
                  host.playerScreen.startPlayer(url, secret, playing.title(), "");
                });
          } catch (Exception e) {
            host.runOnUiThread(
                () -> {
                  if (request == host.generation)
                    host.message("Extras", "This extra couldn't be opened for direct playback.");
                });
          }
        });
  }

  void playEntry(PlexCatalog.Entry entry) {
    host.status.setText("Opening video…");
    int request = host.generation;
    // A single title outside a queue plays as the profile the viewer chose: its token, its
    // restrictions, and its watch history. Only queues play as their own profile.
    String origin = host.server(), secret = host.token();
    boolean isQueueHome =
        host.profile != null
            && host.profile.optString("serverId").equals(host.prefs().getString("queue-home-server", "-"));
    host.network.execute(
        () -> {
          try {
            JSONArray items =
                PlexCatalog.list(
                    PlexCatalog.get(
                        PlexCatalog.url(
                            origin, "/library/metadata/" + entry.key() + "?includeMarkers=1&includeChapters=1"),
                        secret,
                        16 * 1024 * 1024));
            PlexCatalog.Entry detail = PlexCatalog.Entry.fromJson(items.getJSONObject(0));
            if (detail.part().isEmpty()) throw new IllegalStateException();
            // The chosen profile plays a single title, so its own language settings apply.
            PlaybackTracks tracks = PlaybackTracks.forEntry(detail, host.playbackMenus::chosenLanguages);
            byte[] artwork = nowPlayingArtwork(origin, secret, detail);
            String artworkUri = isQueueHome ? queueThumbUri(detail) : "";
            host.runOnUiThread(
                () -> {
                  if (request != host.generation) return;
                  ResumePrompt.ask(
                      host,
                      host.panel(),
                      detail.primary(),
                      detail.resume(),
                      0,
                      detail.duration(),
                      host.rewindSeconds(),
                      start -> {
                        if (request != host.generation) return;
                        host.nativePlan = null;
                        host.isPlayingExtra = false;
                        host.playingEntry = detail;
                        host.playingArtwork = artwork;
                        host.playingArtworkUri = artworkUri;
                        host.playingOrigin = origin;
                        host.playingToken = secret;
                        host.playbackTracks = tracks;
                        host.sectionFloor = 0;
                        host.sectionEnd = -1;
                        host.advancing = false;
                        host.playerScreen.startPlayer(
                            PlexCatalog.url(origin, detail.part()),
                            secret,
                            detail.title(),
                            detail.cast());
                        host.player.seekTo(start);
                      });
                });
          } catch (Exception e) {
            host.runOnUiThread(
                () -> {
                  if (request == host.generation)
                    host.status.setText("This title couldn't be opened for direct playback.");
                });
          }
        });
  }

  /**
   * What other devices show for the playing item. A universal remote (the Unfolded Circle Remote
   * 3 through its Android TV integration) reads the Shield's Cast media status, which Android
   * fills from this media session: the title line, and the artwork. Without artwork the remote
   * falls back to an app icon. So the one title line carries the show, number and episode title,
   * and the poster is given twice: as bytes, for the TV's own now-playing card, and as a
   * tokenless QueuePilot address, for a device on the LAN that can only fetch a URL. A Plex
   * address is never given: it would carry the profile's token to every listener.
   */
  androidx.media3.common.MediaMetadata nowPlayingMetadata(String fallbackTitle) {
    androidx.media3.common.MediaMetadata.Builder metadata =
        new androidx.media3.common.MediaMetadata.Builder();
    PlexCatalog.Entry entry = host.playingEntry;
    if (entry == null) return metadata.setTitle(fallbackTitle).build();
    boolean isEpisode = "episode".equals(entry.type());
    String line =
        EpisodeLabel.nowPlaying(
            entry.type(), entry.title(), entry.showTitle(), entry.season(), entry.episode());
    metadata
        .setTitle(line)
        .setDisplayTitle(line)
        .setSubtitle(isEpisode ? entry.secondary() : null)
        .setMediaType(
            isEpisode
                ? androidx.media3.common.MediaMetadata.MEDIA_TYPE_TV_SHOW
                : androidx.media3.common.MediaMetadata.MEDIA_TYPE_MOVIE);
    if (isEpisode && !entry.showTitle().isEmpty()) metadata.setArtist(entry.showTitle());
    if (isEpisode && !entry.seasonTitle().isEmpty()) metadata.setAlbumTitle(entry.seasonTitle());
    if (host.playingArtwork != null)
      metadata.setArtworkData(
          host.playingArtwork, androidx.media3.common.MediaMetadata.PICTURE_TYPE_FRONT_COVER);
    if (!host.playingArtworkUri.isEmpty())
      metadata.setArtworkUri(android.net.Uri.parse(host.playingArtworkUri));
    return metadata.build();
  }

  /** The poster as JPEG bytes from the playing server, or null. Call off the main thread. */
  static byte[] nowPlayingArtwork(String origin, String secret, PlexCatalog.Entry entry) {
    try {
      String thumb = entry.posterThumb();
      if (thumb.isEmpty()) return null;
      byte[] bytes =
          PlexCatalog.get(
              PlexCatalog.url(origin, TitleCast.transcodePath(thumb, 400)), secret, 2 * 1024 * 1024);
      BitmapFactory.Options bounds = new BitmapFactory.Options();
      bounds.inJustDecodeBounds = true;
      BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
      return bounds.outWidth > 0 && bounds.outHeight > 0 ? bytes : null;
    } catch (Exception unavailable) {
      return null;
    }
  }

  /**
   * QueuePilot's tokenless poster address for the playing title, or empty. QueuePilot's poster
   * proxy reads its home Plex server, so this is used only for an item on that server. An
   * episode uses its show's poster, as the queue's own cards do.
   */
  String queueThumbUri(PlexCatalog.Entry entry) {
    String origin = host.prefs().getString("queue-origin", "");
    String key =
        "episode".equals(entry.type()) && !entry.showKey().isEmpty() ? entry.showKey() : entry.key();
    if (origin.isEmpty() || key.isEmpty() || !key.matches("[0-9]+")) return "";
    return PlexCatalog.url(origin, "/api/thumb/" + key);
  }
}
