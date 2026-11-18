package dev.queuepilot.player;

import static dev.queuepilot.player.Sustainability.MARGINAL;
import static dev.queuepilot.player.Sustainability.NOT_SUSTAINABLE;
import static dev.queuepilot.player.Sustainability.SUSTAINABLE;
import static dev.queuepilot.player.Sustainability.UNKNOWN;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class SustainabilityTest {
  @Test
  public void theVerdictRule() {
    // No media bitrate: nothing to compare.
    assertEquals(UNKNOWN, Sustainability.of(-1, false, 9e9, 30, 30, true, false));
    // Rebuffering now.
    assertEquals(NOT_SUSTAINABLE, Sustainability.of(4e6, false, 9e9, 0, 5, true, true));
    // Draining: under 15 s and 3 s lower than 10 s ago, while playing.
    assertEquals(NOT_SUSTAINABLE, Sustainability.of(4e6, false, Double.NaN, 6, 9, true, false));
    // The same numbers while paused are not draining.
    assertEquals(MARGINAL, Sustainability.of(4e6, false, Double.NaN, 6, 9, false, false));
    // A fall that leaves 15 s or more is not draining yet, but a falling buffer is not full.
    assertEquals(MARGINAL, Sustainability.of(4e6, false, Double.NaN, 20, 30, true, false));
    // A full buffer that moves by less than 3 s is at its limit.
    assertEquals(SUSTAINABLE, Sustainability.of(4e6, false, Double.NaN, 11, 12, true, false));
    assertEquals(SUSTAINABLE, Sustainability.of(4e6, false, Double.NaN, 11, Float.NaN, true, false));
    assertEquals(MARGINAL, Sustainability.of(4e6, false, Double.NaN, 16, 20, true, false));
    // Headroom: 1.2 times the media rate.
    assertEquals(SUSTAINABLE, Sustainability.of(4e6, false, 4.8e6, 3, 3, true, false));
    assertEquals(MARGINAL, Sustainability.of(4e6, false, 4.79e6, 3, 3, true, false));
    // A full buffer of 10 s or more.
    assertEquals(SUSTAINABLE, Sustainability.of(4e6, false, 4.0e6, 10, 10, true, false));
    assertEquals(MARGINAL, Sustainability.of(4e6, false, 4.0e6, 9.9f, 10, true, false));
    // The rest of the title is buffered: a shrinking buffer at the end is not draining.
    assertEquals(SUSTAINABLE, Sustainability.of(4e6, true, Double.NaN, 4, 9, true, false));
    // A rebuffer still counts, even then.
    assertEquals(NOT_SUSTAINABLE, Sustainability.of(4e6, true, Double.NaN, 0, 9, true, true));
    // No buffer history yet.
    assertEquals(MARGINAL, Sustainability.of(4e6, false, Double.NaN, Float.NaN, Float.NaN, true, false));
  }
}
