package dev.queuepilot.player;

import static org.junit.Assert.*;

import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class PlexTracksTest {
  private static PlexTracks.Stream audio(long id, int index, String language, boolean selected) {
    return new PlexTracks.Stream(
        id, PlexTracks.AUDIO, index, language, "", "aac", selected, false, false, "", 0);
  }

  private static PlexTracks.Stream subtitle(
      long id, int index, String language, boolean selected, boolean forced) {
    return new PlexTracks.Stream(
        id, PlexTracks.SUBTITLE, index, language, "", "srt", selected, false, forced, "", 0);
  }

  private static PlexTracks.Stream external(long id, String language, boolean selected) {
    return new PlexTracks.Stream(
        id,
        PlexTracks.SUBTITLE,
        -1,
        language,
        "",
        "srt",
        selected,
        false,
        false,
        "/library/streams/" + id,
        0);
  }

  private static final PlexTracks.Languages ENGLISH_ALWAYS =
      new PlexTracks.Languages("en", true, "en", PlexTracks.SUBTITLES_ALWAYS);

  @Test
  public void followsWhatPlexSelected() {
    List<PlexTracks.Stream> streams =
        List.of(
            audio(1, 1, "jpn", false),
            audio(2, 2, "eng", true),
            subtitle(3, 3, "eng", false, false),
            subtitle(4, 4, "eng", true, true));
    PlexTracks.Choice choice = PlexTracks.choose(streams, ENGLISH_ALWAYS);
    assertEquals(2, choice.audio().id());
    assertEquals(4, choice.subtitle().id());
    assertTrue(choice.isFromPlex());
  }

  @Test
  public void plexChoosingAudioWithoutSubtitlesMeansSubtitlesOff() {
    List<PlexTracks.Stream> streams =
        List.of(audio(1, 1, "jpn", true), subtitle(3, 2, "eng", false, false));
    PlexTracks.Choice choice = PlexTracks.choose(streams, ENGLISH_ALWAYS);
    assertEquals(1, choice.audio().id());
    assertNull(choice.subtitle());
  }

  @Test
  public void withNothingSelectedTheAccountLanguagesApply() {
    List<PlexTracks.Stream> streams =
        List.of(
            audio(1, 1, "jpn", false),
            audio(2, 2, "eng", false),
            subtitle(3, 3, "spa", false, false),
            subtitle(4, 4, "eng", false, true),
            subtitle(5, 5, "eng", false, false));
    PlexTracks.Choice choice = PlexTracks.choose(streams, ENGLISH_ALWAYS);
    assertFalse(choice.isFromPlex());
    assertEquals(2, choice.audio().id());
    // Always: the full English subtitles, not the forced ones.
    assertEquals(5, choice.subtitle().id());
  }

  @Test
  public void foreignAudioModeShowsSubtitlesOnlyForForeignAudio() {
    PlexTracks.Languages foreign =
        new PlexTracks.Languages("en", true, "en", PlexTracks.SUBTITLES_WITH_FOREIGN_AUDIO);
    List<PlexTracks.Stream> japaneseOnly =
        List.of(
            audio(1, 1, "jpn", false),
            subtitle(4, 2, "eng", false, true),
            subtitle(5, 3, "eng", false, false));
    assertEquals(5, PlexTracks.choose(japaneseOnly, foreign).subtitle().id());
    List<PlexTracks.Stream> english =
        List.of(
            audio(1, 1, "eng", false),
            subtitle(4, 2, "eng", false, true),
            subtitle(5, 3, "eng", false, false));
    // English audio: only forced English subtitles, for the parts in another language.
    assertEquals(4, PlexTracks.choose(english, foreign).subtitle().id());
    List<PlexTracks.Stream> noForced =
        List.of(audio(1, 1, "eng", false), subtitle(5, 2, "eng", false, false));
    assertNull(PlexTracks.choose(noForced, foreign).subtitle());
  }

  @Test
  public void subtitlesAreOnlyInThePreferredLanguage() {
    List<PlexTracks.Stream> streams =
        List.of(audio(1, 1, "jpn", false), subtitle(3, 2, "spa", false, false));
    assertNull(PlexTracks.choose(streams, ENGLISH_ALWAYS).subtitle());
  }

  @Test
  public void manualModeAndUnknownSettingsLeaveSubtitlesOff() {
    List<PlexTracks.Stream> streams =
        List.of(audio(1, 1, "jpn", false), subtitle(3, 2, "eng", false, false));
    assertNull(
        PlexTracks.choose(
                streams, new PlexTracks.Languages("en", true, "en", PlexTracks.SUBTITLES_MANUAL))
            .subtitle());
    assertNull(PlexTracks.choose(streams, PlexTracks.Languages.NONE).subtitle());
  }

  @Test
  public void audioFallsBackToTheDefaultThenTheFirstTrack() {
    PlexTracks.Stream flagged =
        new PlexTracks.Stream(2, PlexTracks.AUDIO, 2, "fra", "", "aac", false, true, false, "", 0);
    assertEquals(
        2,
        PlexTracks.choose(List.of(audio(1, 1, "jpn", false), flagged), PlexTracks.Languages.NONE)
            .audio()
            .id());
    assertEquals(
        1,
        PlexTracks.choose(List.of(audio(1, 1, "jpn", false)), PlexTracks.Languages.NONE)
            .audio()
            .id());
    // Automatic audio selection off: the account language is not used.
    PlexTracks.Languages manualAudio =
        new PlexTracks.Languages("en", false, "en", PlexTracks.SUBTITLES_ALWAYS);
    assertEquals(
        1,
        PlexTracks.choose(List.of(audio(1, 1, "jpn", false), audio(2, 2, "eng", false)), manualAudio)
            .audio()
            .id());
  }

  @Test
  public void readsTheAccountProfile() throws Exception {
    JSONObject user =
        new JSONObject()
            .put(
                "profile",
                new JSONObject()
                    .put("autoSelectAudio", true)
                    .put("defaultAudioLanguage", "en")
                    .put("defaultSubtitleLanguage", new JSONArray().put("en").put("ja"))
                    .put("autoSelectSubtitle", 1));
    PlexTracks.Languages languages = PlexTracks.Languages.fromUser(user);
    assertEquals("en", languages.audio());
    assertTrue(languages.isAutoSelectAudio());
    assertEquals("en", languages.subtitle());
    assertEquals(PlexTracks.SUBTITLES_WITH_FOREIGN_AUDIO, languages.subtitleMode());
    PlexTracks.Languages csv =
        PlexTracks.Languages.fromUser(
            new JSONObject()
                .put("profile", new JSONObject().put("defaultAudioLanguage", "ja, en").put("autoSelectAudio", "0")));
    assertEquals("ja", csv.audio());
    assertFalse(csv.isAutoSelectAudio());
    assertEquals(-1, csv.subtitleMode());
    assertEquals(PlexTracks.Languages.NONE, PlexTracks.Languages.fromUser(null));
  }

  @Test
  public void normalizesLanguageCodes() {
    assertEquals("en", PlexTracks.normalize("eng"));
    assertEquals("en", PlexTracks.normalize("en-US"));
    assertEquals("de", PlexTracks.normalize("ger"));
    assertEquals("de", PlexTracks.normalize("deu"));
    assertEquals("ja", PlexTracks.normalize("JPN"));
    assertTrue(PlexTracks.sameLanguage("fre", "fr"));
    assertFalse(PlexTracks.sameLanguage("est", "es"));
    assertFalse(PlexTracks.sameLanguage("und", "und"));
    assertFalse(PlexTracks.sameLanguage("", ""));
  }

  @Test
  public void mapsEmbeddedStreamsByContainerOrder() {
    List<PlexTracks.Stream> streams =
        List.of(
            subtitle(31, 4, "eng", false, false),
            subtitle(30, 3, "eng", false, false),
            external(40, "eng", false));
    List<PlexTracks.Track> tracks =
        List.of(
            new PlexTracks.Track("0:3", "en"),
            new PlexTracks.Track("0:4", "en"),
            new PlexTracks.Track("1:plex-stream-40", "en"));
    // Index 3 comes first in the file, whatever order Plex lists it in.
    assertEquals(0, PlexTracks.trackFor(streams, streams.get(1), tracks));
    assertEquals(1, PlexTracks.trackFor(streams, streams.get(0), tracks));
    assertEquals(2, PlexTracks.trackFor(streams, streams.get(2), tracks));
    assertEquals(30, PlexTracks.streamFor(streams, PlexTracks.SUBTITLE, tracks, 0).id());
    assertEquals(40, PlexTracks.streamFor(streams, PlexTracks.SUBTITLE, tracks, 2).id());
    assertNull(PlexTracks.streamFor(streams, PlexTracks.SUBTITLE, tracks, 5));
  }

  @Test
  public void mapsByLanguageWhenThePlayerFoundADifferentNumberOfTracks() {
    List<PlexTracks.Stream> streams =
        List.of(
            audio(1, 1, "jpn", false), audio(2, 2, "eng", false), audio(3, 3, "eng", false));
    List<PlexTracks.Track> tracks =
        List.of(new PlexTracks.Track("2", "en"), new PlexTracks.Track("3", "en"));
    assertEquals(1, PlexTracks.trackFor(streams, streams.get(2), tracks));
    assertEquals(-1, PlexTracks.trackFor(streams, streams.get(0), tracks));
    assertEquals(-1, PlexTracks.trackFor(streams, external(9, "eng", true), tracks));
  }

  @Test
  public void loadsOnlyExternalTextSubtitles() {
    PlexTracks.Stream picture =
        new PlexTracks.Stream(
            8, PlexTracks.SUBTITLE, -1, "eng", "", "pgs", false, false, false, "/library/streams/8", 0);
    List<PlexTracks.Stream> loadable =
        PlexTracks.loadableExternal(
            List.of(external(7, "eng", false), picture, subtitle(3, 2, "eng", false, false)));
    assertEquals(1, loadable.size());
    assertEquals(7, loadable.get(0).id());
    assertEquals("application/x-subrip", PlexTracks.subtitleMime("srt"));
    assertEquals("text/x-ssa", PlexTracks.subtitleMime("ass"));
    assertNull(PlexTracks.subtitleMime("pgs"));
  }

  @Test
  public void savesTheChoiceForAllParts() {
    assertEquals("audioStreamID=12&allParts=1", PlexTracks.saveQuery(PlexTracks.AUDIO, 12));
    assertEquals("subtitleStreamID=0&allParts=1", PlexTracks.saveQuery(PlexTracks.SUBTITLE, 0));
  }

  @Test
  public void streamsSurviveJson() throws Exception {
    PlexTracks.Stream stream = external(7, "eng", true);
    JSONArray array = new JSONArray().put(stream.json());
    assertEquals(List.of(stream), PlexTracks.streams(array));
    PlexTracks.Stream video =
        new PlexTracks.Stream(1, PlexTracks.VIDEO, 0, "", "", "h264", false, true, false, "", 23.976);
    assertEquals(23.976, PlexTracks.frameRate(List.of(video)), 0.0001);
  }

  @Test
  public void readsTheContainerTrackNumberBehindAMergedId() {
    assertEquals(3, PlexTracks.containerTrackNumber("3"));
    assertEquals(3, PlexTracks.containerTrackNumber("0:3"));
    assertEquals(12, PlexTracks.containerTrackNumber("1:0:12"));
  }
}
