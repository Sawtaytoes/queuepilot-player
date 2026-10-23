package dev.queuepilot.player;

import static org.junit.Assert.*;

import org.junit.Test;

public class EpisodeLabelTest {
  @Test
  public void episodesLeadWithTheShow() {
    assertEquals("Orbit Nine", EpisodeLabel.primary("episode", "The signal", "Orbit Nine"));
    assertEquals("S1 · E9 · The signal", EpisodeLabel.secondary("episode", 1, 9, "The signal"));
    assertEquals(
        "Orbit Nine, S1 · E9 · The signal",
        EpisodeLabel.spoken("episode", "The signal", "Orbit Nine", 1, 9));
  }

  @Test
  public void specialsSayWhatTheyAre() {
    assertEquals(
        "Special · A day between stars",
        EpisodeLabel.secondary("episode", 0, 2, "A day between stars"));
  }

  @Test
  public void missingNumbersAreLeftOut() {
    assertEquals(
        "E4 · Lanterns", EpisodeLabel.secondary("episode", EpisodeLabel.UNKNOWN, 4, "Lanterns"));
    assertEquals("S2 · Lanterns", EpisodeLabel.secondary("episode", 2, 0, "Lanterns"));
    assertEquals(
        "Lanterns", EpisodeLabel.secondary("episode", EpisodeLabel.UNKNOWN, 0, "Lanterns"));
  }

  @Test
  public void otherItemsAndOldCachedEpisodesKeepTheirTitle() {
    assertEquals("Glass City", EpisodeLabel.primary("movie", "Glass City", ""));
    assertEquals("", EpisodeLabel.secondary("movie", 1, 1, "Glass City"));
    assertEquals("Season 1", EpisodeLabel.primary("season", "Season 1", "Orbit Nine"));
    // A cached episode saved before show names were stored still renders by its own title.
    assertEquals("The signal", EpisodeLabel.primary("episode", "The signal", ""));
    assertEquals("The signal", EpisodeLabel.spoken("episode", "The signal", "", 1, 9));
  }
}
