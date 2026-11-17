package dev.queuepilot.player;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Plex's universal transcoder, for a file this TV cannot play directly. The player asks the server
 * for HLS with H.264 video and AAC, AC3, E-AC3 or MP3 audio in MPEG-TS segments, the target Plex's
 * own long-standing clients request. Streams the TV can play are copied ({@code directStream=1});
 * only the rest is converted. The URLs carry no token: {@link PlexDataSource} sends it as a header
 * to the playing server only.
 *
 * <p>The request follows the Plezy reference's measured behavior against Plex Media Server: no
 * {@code offset} (the player seeks inside the stream instead), {@code subtitles=burn} burns the
 * part's selected subtitle stream and ignores a {@code subtitleStreamID}, and {@code ping} keeps a
 * paused session alive.
 */
final class ServerConversion {
  static final String PREFERENCE_KEY = "unplayable-file";
  static final String ASK = "ask", CONVERT = "convert", STOP = "stop";
  static final String DEFAULT = ASK;
  static final String[] MODES = {ASK, CONVERT, STOP};
  static final String[] LABELS = {"Ask", "Convert on the server", "Stop"};

  private static final String BASE = "/video/:/transcode/universal";

  /** Video and audio the TV is asked to receive, and the stream choice Plex should honor. */
  static final String PROFILE_EXTRA =
      "add-settings(DirectPlayStreamSelection=true)"
          + "+add-transcode-target(type=videoProfile&context=streaming&protocol=hls"
          + "&container=mpegts&videoCodec=h264&audioCodec=aac%2Cac3%2Ceac3%2Cmp3)";

  private ServerConversion() {}

  static String mode(String stored) {
    for (String mode : MODES) if (mode.equals(stored)) return mode;
    return DEFAULT;
  }

  static String label(String mode) {
    String valid = mode(mode);
    for (int i = 0; i < MODES.length; i++) if (MODES[i].equals(valid)) return LABELS[i];
    return LABELS[0];
  }

  static int index(String mode) {
    String valid = mode(mode);
    for (int i = 0; i < MODES.length; i++) if (MODES[i].equals(valid)) return i;
    return 0;
  }

  /** One conversion: the item, the session that names it to the server, and the client. */
  record Request(
      String ratingKey,
      String session,
      String clientId,
      String deviceName,
      long audioStreamId,
      boolean isSubtitleBurned) {
    Map<String, String> parameters() {
      Map<String, String> p = new LinkedHashMap<>();
      p.put("hasMDE", "1");
      p.put("path", "/library/metadata/" + ratingKey);
      p.put("mediaIndex", "0");
      p.put("partIndex", "0");
      p.put("protocol", "hls");
      p.put("fastSeek", "1");
      p.put("directPlay", "0");
      p.put("directStream", "1");
      p.put("directStreamAudio", "1");
      p.put("subtitleSize", "100");
      p.put("audioBoost", "100");
      p.put("location", "lan");
      p.put("autoAdjustQuality", "0");
      p.put("mediaBufferSize", "102400");
      p.put("session", session);
      p.put("subtitles", isSubtitleBurned ? "burn" : "none");
      if (audioStreamId >= 0) p.put("audioStreamID", Long.toString(audioStreamId));
      p.put("X-Plex-Session-Identifier", session);
      p.put("X-Plex-Client-Profile-Extra", PROFILE_EXTRA);
      p.put("X-Plex-Client-Profile-Name", "Generic");
      p.put("X-Plex-Product", "QueuePilot TV");
      p.put("X-Plex-Platform", "Android");
      p.put("X-Plex-Client-Identifier", clientId);
      p.put("X-Plex-Device-Name", deviceName);
      return p;
    }

    /** Asks the server what it would do, before the player starts. */
    String decisionPath() {
      return BASE + "/decision?" + query(parameters());
    }

    /** The HLS playlist the player opens. */
    String startPath() {
      return BASE + "/start.m3u8?" + query(parameters());
    }

    String pingPath() {
      return BASE + "/ping?session=" + PlexAccount.encode(session);
    }

    String stopPath() {
      return BASE + "/stop?session=" + PlexAccount.encode(session);
    }
  }

  static boolean isConversion(String url) {
    return url != null && url.contains(BASE + "/start.m3u8");
  }

  static String query(Map<String, String> parameters) {
    StringBuilder out = new StringBuilder();
    for (Map.Entry<String, String> e : parameters.entrySet()) {
      if (out.length() > 0) out.append('&');
      out.append(PlexAccount.encode(e.getKey())).append('=').append(PlexAccount.encode(e.getValue()));
    }
    return out.toString();
  }

  /**
   * The decision's {@code generalDecisionCode}, {@code transcodeDecisionCode} and {@code
   * mdeDecisionCode} (-1 when absent), with {@code generalDecisionText}. A code of 2000 or more is
   * a refusal; 1000s mean Plex can play, copy or convert the item.
   */
  record Decision(int general, int transcode, int mde, String text) {
    boolean isPlayable() {
      return general >= 0 && general < 2000 && transcode < 2000 && mde < 2000;
    }

    static int code(String value) {
      try {
        return Integer.parseInt(value.trim());
      } catch (RuntimeException e) {
        return -1;
      }
    }
  }
}
