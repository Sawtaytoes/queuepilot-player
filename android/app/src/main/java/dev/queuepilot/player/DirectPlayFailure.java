package dev.queuepilot.player;

import androidx.media3.common.PlaybackException;
import java.util.Locale;
import java.util.Map;

/**
 * Why a file could not play directly, in words, and whether converting it on the server could
 * help. A network failure is not the file's fault, so it is never offered for conversion.
 */
final class DirectPlayFailure {
  enum Kind {
    CONTAINER,
    VIDEO,
    AUDIO,
    DECODER
  }

  /** {@code kind} null means conversion would not help. */
  record Reason(Kind kind, String text) {
    boolean isConvertible() {
      return kind != null;
    }
  }

  private DirectPlayFailure() {}

  /** From a Media3 playback error code and Plex's {@code Media} facts. */
  static Reason fromError(int code, String container, String videoCodec, String audioCodec) {
    return switch (code) {
      case PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
              PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ->
          new Reason(
              Kind.CONTAINER,
              container.isEmpty()
                  ? "This TV can't open the file's format."
                  : "This TV can't open " + name(container) + " files like this one.");
      case PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
              PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
              PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
              PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
              PlaybackException.ERROR_CODE_DECODING_FAILED ->
          new Reason(Kind.DECODER, decoder(videoCodec, audioCodec));
      case PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
              PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED ->
          new Reason(
              Kind.AUDIO,
              audioCodec.isEmpty()
                  ? "This TV couldn't play the file's audio."
                  : "This TV couldn't play the " + name(audioCodec) + " audio.");
      default -> new Reason(null, "The file couldn't be read from the server.");
    };
  }

  /**
   * From the tracks the player found: a file can open and still have no video or no audio this TV
   * can decode. Null when both play.
   */
  static Reason fromTracks(
      boolean isVideoUnsupported, boolean isAudioUnsupported, String videoCodec, String audioCodec) {
    if (isVideoUnsupported)
      return new Reason(
          Kind.VIDEO,
          videoCodec.isEmpty()
              ? "This TV can't decode the file's video."
              : "The video is " + name(videoCodec) + ", which this TV can't decode.");
    if (isAudioUnsupported)
      return new Reason(
          Kind.AUDIO,
          audioCodec.isEmpty()
              ? "This TV can't decode the file's audio."
              : "The audio is " + name(audioCodec) + ", which this TV can't decode.");
    return null;
  }

  private static String decoder(String videoCodec, String audioCodec) {
    if (videoCodec.isEmpty() && audioCodec.isEmpty())
      return "This TV's decoder couldn't play the file.";
    String codecs =
        videoCodec.isEmpty()
            ? name(audioCodec) + " audio"
            : audioCodec.isEmpty()
                ? name(videoCodec) + " video"
                : name(videoCodec) + " video with " + name(audioCodec) + " audio";
    return "This TV's decoder couldn't play the " + codecs + ".";
  }

  private static final Map<String, String> NAMES =
      Map.ofEntries(
          Map.entry("h264", "H.264"),
          Map.entry("hevc", "HEVC"),
          Map.entry("h265", "HEVC"),
          Map.entry("mpeg2video", "MPEG-2"),
          Map.entry("mpeg4", "MPEG-4"),
          Map.entry("msmpeg4v3", "DivX 3"),
          Map.entry("vc1", "VC-1"),
          Map.entry("wmv3", "WMV 9"),
          Map.entry("wmv2", "WMV 8"),
          Map.entry("vp9", "VP9"),
          Map.entry("av1", "AV1"),
          Map.entry("aac", "AAC"),
          Map.entry("ac3", "Dolby Digital"),
          Map.entry("eac3", "Dolby Digital Plus"),
          Map.entry("truehd", "Dolby TrueHD"),
          Map.entry("dca", "DTS"),
          Map.entry("dts", "DTS"),
          Map.entry("wmav2", "WMA"),
          Map.entry("mp3", "MP3"),
          Map.entry("flac", "FLAC"),
          Map.entry("mkv", "MKV"),
          Map.entry("avi", "AVI"),
          Map.entry("asf", "WMV"),
          Map.entry("wmv", "WMV"),
          Map.entry("mp4", "MP4"),
          Map.entry("mpegts", "MPEG-TS"));

  /** Plex's codec or container name as a viewer knows it: "hevc" is HEVC, "dca" is DTS. */
  static String name(String plexName) {
    String key = plexName.trim().toLowerCase(Locale.ROOT);
    return NAMES.getOrDefault(key, key.toUpperCase(Locale.ROOT));
  }
}
