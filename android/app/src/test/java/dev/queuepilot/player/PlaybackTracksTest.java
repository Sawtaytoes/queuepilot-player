package dev.queuepilot.player;

import static org.junit.Assert.*;

import java.util.List;
import org.junit.Test;

/** The subtitle a server conversion burns when the TV could not open the file. */
public class PlaybackTracksTest {
  private static final PlexTracks.Stream AUDIO =
      new PlexTracks.Stream(11, PlexTracks.AUDIO, 1, "ja", "", "aac", true, false, false, "", 0);
  private static final PlexTracks.Stream EMBEDDED =
      new PlexTracks.Stream(
          21, PlexTracks.SUBTITLE, 2, "en", "", "ass", true, false, false, "", 0);
  private static final PlexTracks.Stream SIDECAR =
      new PlexTracks.Stream(
          31, PlexTracks.SUBTITLE, -1, "en", "", "srt", false, false, false, "/library/streams/31",
          0);

  private static PlaybackTracks planned(PlexTracks.Stream subtitle, boolean isFromPlex) {
    return new PlaybackTracks(
        List.of(AUDIO, EMBEDDED, SIDECAR), new PlexTracks.Choice(AUDIO, subtitle, isFromPlex));
  }

  @Test
  public void burnsThePlannedSubtitleFromInsideTheFile() {
    assertEquals(21, planned(EMBEDDED, true).plannedBurnedSubtitleId());
    // A choice the app made from the account's languages is burned too; it is saved first.
    assertEquals(21, planned(EMBEDDED, false).plannedBurnedSubtitleId());
  }

  @Test
  public void burnsNothingForASidecarOrNoSubtitle() {
    assertEquals(-1, planned(SIDECAR, true).plannedBurnedSubtitleId());
    assertEquals(-1, planned(null, true).plannedBurnedSubtitleId());
  }

  @Test
  public void aConversionKeepsTheSidecarTheViewerHasOn() {
    // Started on the embedded subtitle, then chose the sidecar: the conversion keeps the sidecar.
    PlaybackTracks converted = planned(EMBEDDED, true).forConversion(SIDECAR);
    assertSame(SIDECAR, converted.plannedSidecar());
    // The server burns the embedded subtitle, so the converted stream does not pick it again.
    assertEquals(-1, converted.plannedBurnedSubtitleId());
  }

  @Test
  public void aConversionKeepsSubtitlesOffAfterTheViewerTurnedThemOff() {
    PlaybackTracks converted = planned(SIDECAR, true).forConversion(null);
    assertNull(converted.plannedSidecar());
    assertTrue(converted.hasStreams());
  }

  @Test
  public void theTVThatCouldNotOpenTheFileKeepsThePlannedSidecar() {
    assertSame(SIDECAR, planned(SIDECAR, true).plannedSidecar());
    assertNull(planned(EMBEDDED, true).plannedSidecar());
  }
}
