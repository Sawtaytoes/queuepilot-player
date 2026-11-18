package dev.queuepilot.player;

import java.util.UUID;

/**
 * Conversion on the server: when a file cannot play directly, asking with its reason,
 * converting from the resume point, and ending the conversion on leaving.
 */
@androidx.media3.common.util.UnstableApi
final class ConversionPlayback {
  private final MainActivity host;

  ConversionPlayback(MainActivity host) {
    this.host = host;
  }

  /** The server conversion the player is streaming, or null when it plays the file directly. */
  ConversionSession conversion;

  /** True once the playing item has asked what to do about a file that can't play directly. */
  boolean isDirectPlayHandled;

  String playingFact(String name) {
    return host.playingEntry == null ? "" : host.playingEntry.facts().optString(name);
  }

  /**
   * A file this TV can't play directly: ask, convert on the server, or stop, as Settings → Playback
   * says. Once per item; a failing conversion only says so.
   */
  void cannotPlayDirectly(DirectPlayFailure.Reason reason) {
    if (host.player == null) return;
    if (conversion != null) {
      // The converted stream failed: end the server's session instead of keeping it alive.
      endConversion();
      host.status.setText("The server's conversion stopped.");
      return;
    }
    if (isDirectPlayHandled) return;
    isDirectPlayHandled = true;
    if (!reason.isConvertible() || host.playingEntry == null) {
      host.status.setText("This file couldn't be played. " + reason.text());
      return;
    }
    host.status.setText(reason.text());
    switch (ServerConversion.mode(host.prefs().getString(ServerConversion.PREFERENCE_KEY, null))) {
      case ServerConversion.CONVERT -> convertOnServer();
      case ServerConversion.STOP -> {
        host.back();
        host.message("This file can't play directly", reason.text() + " Settings says to stop.");
      }
      default ->
          ConversionSession.ask(
              host,
              host.panel(),
              reason,
              this::convertOnServer,
              () -> {
                host.back();
                host.message("Stopped", reason.text());
              });
    }
  }

  /**
   * Plays the item again through Plex's universal transcoder, from where it was, as the same
   * profile. Plex's own stream choice for the part decides the converted audio; a subtitle from
   * inside the file is burned into the picture.
   */
  private void convertOnServer() {
    PlexCatalog.Entry entry = host.playingEntry;
    if (host.player == null || entry == null) return;
    long position = Math.max(host.sectionFloor, host.player.getCurrentPosition());
    androidx.media3.common.Tracks current = host.player.getCurrentTracks();
    long burned =
        host.playbackTracks != null
            ? host.playbackTracks.burnedSubtitleId(current)
            : PlaybackTracks.isEmbeddedTextSelected(current) ? 0 : -1;
    String partId = entry.facts().optString("partId");
    ConversionSession session =
        new ConversionSession(
            host.playingOrigin,
            host.playingToken,
            new ServerConversion.Request(
                entry.key(),
                UUID.randomUUID().toString(),
                host.prefs().getString("plex-client-id", ""),
                host.deviceName,
                -1,
                burned >= 0));
    PlaybackTracks tracks =
        host.playbackTracks == null
            ? null
            : host.playbackTracks.forConversion(host.playbackTracks.shownSidecar(current));
    host.status.setText("Asking the server to convert this file…");
    int request = host.generation;
    host.network.execute(
        () -> {
          String refusal;
          try {
            // Plex burns the part's stored subtitle, so the one on screen is saved first.
            if (burned > 0
                && !PlaybackTracks.save(
                    session.origin, session.token, partId, PlexTracks.SUBTITLE, burned)) {
              refusal = "Plex didn't save the subtitle to burn into the picture.";
            } else {
              ServerConversion.Decision decision = session.decide();
              refusal = decision.isPlayable() ? null : decision.text();
            }
          } catch (Exception e) {
            refusal = e.getMessage() == null ? "" : e.getMessage();
          }
          String reason = refusal;
          host.runOnUiThread(
              () -> {
                if (request != host.generation || host.player == null) return;
                if (reason != null) {
                  host.status.setText("The server can't convert this file.");
                  host.message(
                      "The server can't convert this file",
                      reason.isEmpty() ? "Plex refused the conversion." : reason);
                  return;
                }
                boolean wasAdvancing = host.advancing;
                host.advancing = true; // Not a stop: the same item continues.
                host.playbackTracks = tracks;
                host.playerScreen.startPlayer(session.url(), session.token, entry.title(), entry.cast());
                host.advancing = wasAdvancing;
                // Set after startPlayer, whose release of the direct player ends no conversion.
                conversion = session;
                host.player.seekTo(position);
                host.status.setText("Converting on the server");
              });
        });
  }

  /** Ends the server's conversion session, if one is playing. */
  void endConversion() {
    if (conversion == null) return;
    conversion.stop(host.progressNetwork);
    conversion = null;
  }
}
