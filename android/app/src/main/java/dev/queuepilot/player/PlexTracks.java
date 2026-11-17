package dev.queuepilot.player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Which audio and subtitle streams to play, from what Plex says about the part.
 *
 * <p>Plex Media Server marks one audio and at most one subtitle stream {@code selected="1"} for the
 * profile whose token asked. It derives that from the profile's own choice for the item (saved with
 * {@code PUT /library/parts/<id>}) and from the account's language settings on plex.tv. So the
 * selected streams are followed first. Only when the server selected nothing at all, which means it
 * made no decision for this part, are the account settings applied here: the default audio
 * language when automatic audio selection is on, and the default subtitle language with the
 * automatic subtitle mode (0 manual, 1 shown with foreign audio, 2 always). When the server chose
 * an audio stream but no subtitle stream, subtitles are off, as Plex decided.
 *
 * <p>No Android dependency, so the rules are unit tested.
 */
final class PlexTracks {
  static final int VIDEO = 1, AUDIO = 2, SUBTITLE = 3;

  /** Plex's {@code autoSelectSubtitle}: subtitles only when chosen by hand. */
  static final int SUBTITLES_MANUAL = 0;

  /** Plex's {@code autoSelectSubtitle}: subtitles when the audio is not in the preferred language. */
  static final int SUBTITLES_WITH_FOREIGN_AUDIO = 1;

  /** Plex's {@code autoSelectSubtitle}: subtitles always, in the preferred language. */
  static final int SUBTITLES_ALWAYS = 2;

  /** Prefix of the Media3 format id given to a Plex external subtitle loaded beside the video. */
  static final String EXTERNAL_PREFIX = "plex-stream-";

  private PlexTracks() {}

  /** One {@code <Stream>} of a part. {@code key} is set only for an external (sidecar) file. */
  record Stream(
      long id,
      int type,
      int index,
      String language,
      String title,
      String codec,
      boolean isSelected,
      boolean isDefault,
      boolean isForced,
      String key,
      double frameRate) {
    boolean isExternal() {
      return !key.isEmpty();
    }

    JSONObject json() throws Exception {
      return new JSONObject()
          .put("id", id)
          .put("type", type)
          .put("index", index)
          .put("language", language)
          .put("title", title)
          .put("codec", codec)
          .put("selected", isSelected)
          .put("default", isDefault)
          .put("forced", isForced)
          .put("key", key)
          .put("frameRate", frameRate);
    }

    static Stream fromJson(JSONObject j) {
      return new Stream(
          j.optLong("id", -1),
          j.optInt("type"),
          j.optInt("index", -1),
          j.optString("language"),
          j.optString("title"),
          j.optString("codec"),
          j.optBoolean("selected"),
          j.optBoolean("default"),
          j.optBoolean("forced"),
          j.optString("key"),
          j.optDouble("frameRate", 0));
    }
  }

  static List<Stream> streams(JSONArray array) {
    List<Stream> list = new ArrayList<>();
    for (int i = 0; array != null && i < array.length(); i++) {
      JSONObject j = array.optJSONObject(i);
      if (j != null) list.add(Stream.fromJson(j));
    }
    return list;
  }

  /** The account's plex.tv language settings; {@link #NONE} when unknown. */
  record Languages(String audio, boolean isAutoSelectAudio, String subtitle, int subtitleMode) {
    static final Languages NONE = new Languages("", false, "", -1);

    /**
     * Reads {@code GET https://plex.tv/api/v2/user}: the settings sit in its {@code profile}
     * object. A value the account API sends as a list or a comma-separated string uses its first
     * entry.
     */
    static Languages fromUser(JSONObject user) {
      if (user == null) return NONE;
      JSONObject profile = user.optJSONObject("profile");
      if (profile == null) profile = user;
      return new Languages(
          first(profile.opt("defaultAudioLanguage")),
          flag(profile.opt("autoSelectAudio")),
          first(profile.opt("defaultSubtitleLanguage")),
          profile.has("autoSelectSubtitle") ? profile.optInt("autoSelectSubtitle", -1) : -1);
    }

    private static String first(Object value) {
      if (value instanceof JSONArray array) return array.length() == 0 ? "" : array.optString(0);
      if (value == null || value == JSONObject.NULL) return "";
      return value.toString().split(",")[0].trim();
    }

    private static boolean flag(Object value) {
      if (value instanceof Boolean b) return b;
      if (value instanceof Number n) return n.intValue() != 0;
      return value != null && java.util.Set.of("1", "true").contains(value.toString().trim());
    }
  }

  /**
   * The streams to play. {@code subtitle} is null when subtitles are off. {@code isFromPlex} is true
   * when the server's selection was followed rather than the account settings.
   */
  record Choice(Stream audio, Stream subtitle, boolean isFromPlex) {}

  static Choice choose(List<Stream> streams, Languages account) {
    List<Stream> audio = ofType(streams, AUDIO), subtitles = ofType(streams, SUBTITLE);
    Stream selectedAudio = selected(audio), selectedSubtitle = selected(subtitles);
    if (selectedAudio != null || selectedSubtitle != null) {
      Stream chosenAudio = selectedAudio != null ? selectedAudio : fallbackAudio(audio, account);
      return new Choice(chosenAudio, selectedSubtitle, true);
    }
    Stream chosenAudio = fallbackAudio(audio, account);
    return new Choice(chosenAudio, fallbackSubtitle(subtitles, chosenAudio, account), false);
  }

  private static Stream fallbackAudio(List<Stream> audio, Languages account) {
    if (audio.isEmpty()) return null;
    if (account.isAutoSelectAudio() && !account.audio().isEmpty())
      for (Stream s : audio) if (sameLanguage(s.language(), account.audio())) return s;
    for (Stream s : audio) if (s.isDefault()) return s;
    return audio.get(0);
  }

  private static Stream fallbackSubtitle(
      List<Stream> subtitles, Stream audio, Languages account) {
    String preferred = account.subtitle();
    if (subtitles.isEmpty() || preferred.isEmpty()) return null;
    boolean isForeignAudio =
        audio != null
            && !audio.language().isEmpty()
            && !account.audio().isEmpty()
            && !sameLanguage(audio.language(), account.audio());
    return switch (account.subtitleMode()) {
      case SUBTITLES_ALWAYS -> full(subtitles, preferred);
      case SUBTITLES_WITH_FOREIGN_AUDIO ->
          isForeignAudio ? full(subtitles, preferred) : forced(subtitles, preferred);
      default -> null;
    };
  }

  /** Full subtitles in the language, or forced ones when there are no others; else null. */
  private static Stream full(List<Stream> subtitles, String language) {
    Stream forced = null;
    for (Stream s : subtitles)
      if (sameLanguage(s.language(), language)) {
        if (!s.isForced()) return s;
        if (forced == null) forced = s;
      }
    return forced;
  }

  private static Stream forced(List<Stream> subtitles, String language) {
    for (Stream s : subtitles)
      if (s.isForced() && sameLanguage(s.language(), language)) return s;
    return null;
  }

  private static Stream selected(List<Stream> streams) {
    for (Stream s : streams) if (s.isSelected()) return s;
    return null;
  }

  static List<Stream> ofType(List<Stream> streams, int type) {
    List<Stream> list = new ArrayList<>();
    for (Stream s : streams) if (s.type() == type) list.add(s);
    return list;
  }

  /** A part's streams of one type that are inside the media file, in container order. */
  static List<Stream> embedded(List<Stream> streams, int type) {
    List<Stream> list = new ArrayList<>();
    for (Stream s : ofType(streams, type)) if (!s.isExternal()) list.add(s);
    list.sort(Comparator.comparingInt(Stream::index));
    return list;
  }

  /** Subtitle files beside the media that the player can read as text. */
  static List<Stream> loadableExternal(List<Stream> streams) {
    List<Stream> list = new ArrayList<>();
    for (Stream s : ofType(streams, SUBTITLE))
      if (s.isExternal() && s.id() >= 0 && subtitleMime(s.codec()) != null) list.add(s);
    return list;
  }

  /** The Media3 MIME type for an external subtitle codec, or null when it is not loaded. */
  static String subtitleMime(String codec) {
    return switch (codec.toLowerCase(Locale.ROOT)) {
      case "srt", "subrip" -> "application/x-subrip";
      case "vtt", "webvtt" -> "text/vtt";
      case "ass", "ssa" -> "text/x-ssa";
      default -> null;
    };
  }

  static String externalId(Stream stream) {
    return EXTERNAL_PREFIX + stream.id();
  }

  /** One track the player found: its format id and language, in the player's order. */
  record Track(String id, String language) {}

  /**
   * The position, among the player's tracks of one type, of the Plex stream {@code target}; -1
   * when it cannot be found. An external stream is found by its format id. An embedded stream is
   * found by its position among the embedded streams, which follow container order in both lists;
   * when the counts differ, by its position among the streams of its own language.
   */
  static int trackFor(List<Stream> streams, Stream target, List<Track> tracks) {
    if (target == null) return -1;
    if (target.isExternal()) {
      String id = externalId(target);
      // Media3 prefixes a merged source's format ids with its source index ("1:plex-stream-7").
      for (int i = 0; i < tracks.size(); i++) {
        String found = tracks.get(i).id() == null ? "" : tracks.get(i).id();
        if (found.equals(id) || found.endsWith(":" + id)) return i;
      }
      return -1;
    }
    List<Stream> plex = embedded(streams, target.type());
    List<Integer> inFile = new ArrayList<>();
    for (int i = 0; i < tracks.size(); i++)
      if (!isExternalTrack(tracks.get(i))) inFile.add(i);
    int position = indexOfId(plex, target);
    if (position < 0) return -1;
    if (plex.size() == inFile.size()) return inFile.get(position);
    int nth = 0;
    for (int i = 0; i < position; i++)
      if (sameLanguage(plex.get(i).language(), target.language())) nth++;
    for (int i : inFile)
      if (sameLanguage(tracks.get(i).language(), target.language()) && nth-- == 0) return i;
    return -1;
  }

  /** The Plex stream for the player's track at {@code position}, or null. The reverse of {@link #trackFor}. */
  static Stream streamFor(List<Stream> streams, int type, List<Track> tracks, int position) {
    if (position < 0 || position >= tracks.size()) return null;
    for (Stream s : ofType(streams, type)) if (trackFor(streams, s, tracks) == position) return s;
    return null;
  }

  private static boolean isExternalTrack(Track track) {
    return track.id() != null && track.id().contains(EXTERNAL_PREFIX);
  }

  private static int indexOfId(List<Stream> list, Stream target) {
    for (int i = 0; i < list.size(); i++) if (list.get(i).id() == target.id()) return i;
    return -1;
  }

  private static final Map<String, String> ALPHA3 = new HashMap<>();

  static {
    for (String two : Locale.getISOLanguages()) {
      try {
        ALPHA3.put(new Locale(two).getISO3Language(), two);
      } catch (java.util.MissingResourceException ignored) {
        // A language without a three-letter code.
      }
    }
    // ISO 639-2/B codes, which Plex and Matroska use for these languages.
    String[][] bibliographic = {
      {"alb", "sq"}, {"arm", "hy"}, {"baq", "eu"}, {"bur", "my"}, {"chi", "zh"}, {"cze", "cs"},
      {"dut", "nl"}, {"fre", "fr"}, {"geo", "ka"}, {"ger", "de"}, {"gre", "el"}, {"ice", "is"},
      {"mac", "mk"}, {"mao", "mi"}, {"may", "ms"}, {"per", "fa"}, {"rum", "ro"}, {"slo", "sk"},
      {"tib", "bo"}, {"wel", "cy"}
    };
    for (String[] pair : bibliographic) ALPHA3.put(pair[0], pair[1]);
  }

  /** "eng", "en", "en-US" and "EN" all become "en"; an unknown code is kept, lower-cased. */
  static String normalize(String language) {
    if (language == null) return "";
    String code = language.trim().toLowerCase(Locale.ROOT).replace('_', '-');
    int dash = code.indexOf('-');
    if (dash > 0) code = code.substring(0, dash);
    if (code.length() == 3 && ALPHA3.containsKey(code)) return ALPHA3.get(code);
    return code;
  }

  static boolean sameLanguage(String a, String b) {
    String left = normalize(a), right = normalize(b);
    return !left.isEmpty() && !left.equals("und") && left.equals(right);
  }

  /** The query that saves the viewer's choice to Plex for this profile; 0 turns subtitles off. */
  static String saveQuery(int type, long streamId) {
    return (type == AUDIO ? "audioStreamID=" : "subtitleStreamID=")
        + Math.max(0, streamId)
        + "&allParts=1";
  }

  /** The video frame rate Plex reports for the part, or 0. */
  static double frameRate(List<Stream> streams) {
    for (Stream s : ofType(streams, VIDEO)) if (s.frameRate() > 0) return s.frameRate();
    return 0;
  }

  /**
   * The container's track number in a Media3 format id. Once sideloaded subtitles are merged in,
   * Media3 prefixes each source's ids with its index ("0:3"); the file's own number is the last
   * part.
   */
  static int containerTrackNumber(String formatId) {
    return Integer.parseInt(formatId.substring(formatId.lastIndexOf(':') + 1));
  }
}
