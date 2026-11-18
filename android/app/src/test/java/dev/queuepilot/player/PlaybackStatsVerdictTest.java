package dev.queuepilot.player;

import static dev.queuepilot.player.Sustainability.MARGINAL;
import static dev.queuepilot.player.Sustainability.NOT_SUSTAINABLE;
import static dev.queuepilot.player.Sustainability.SUSTAINABLE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import dev.queuepilot.player.PlaybackStatsTest.FakeProbe;
import org.junit.Test;

/** The verdict as PlaybackStats reaches it over time: seeks, gaps, the end, and fallbacks. */
@androidx.media3.common.util.UnstableApi
public class PlaybackStatsVerdictTest {
  @Test
  public void theEndOfATitleIsNotDraining() {
    PlaybackStats stats = new PlaybackStats();
    FakeProbe probe = new FakeProbe();
    PlaybackStatsTest.media(stats, 30);
    probe.duration = 30_000;
    // Everything is loaded: the buffer is the rest of the clip and shrinks as it plays.
    for (int second = 1; second <= 25; second++) {
      probe.position = second * 1_000L + 500;
      probe.buffered = 30_000 - probe.position;
      probe.isLoading = false;
      stats.tick(probe, second * 1_000L);
    }
    assertEquals(SUSTAINABLE, stats.verdict());
  }

  @Test
  public void bufferHistoryFromBeforeASeekNeverMeansDraining() {
    PlaybackStats stats = new PlaybackStats();
    FakeProbe probe = new FakeProbe();
    stats.setStated(4_000_000, -1, -1);
    probe.isLoading = false; // no throughput reading: the buffer alone decides
    probe.buffered = 20_000;
    long now = 0;
    for (int second = 0; second < 12; second++) stats.tick(probe, now += 1_000);
    assertEquals(SUSTAINABLE, stats.verdict());
    // A seek to a place with 4 s buffered, which then fills.
    stats.seeked(now);
    for (int second = 0; second < 10; second++) {
      probe.buffered = 4_000 + second * 500L;
      stats.tick(probe, now += 1_000);
      assertTrue("Second " + second + " after the seek", stats.verdict() != NOT_SUSTAINABLE);
    }
    // Ten seconds after the seek, a real fall counts again.
    for (int second = 0; second < 11; second++) {
      probe.buffered = 12_000 - second * 500L;
      stats.tick(probe, now += 1_000);
    }
    assertEquals(NOT_SUSTAINABLE, stats.verdict());
  }

  @Test
  public void aGapInSamplingStartsTheBufferHistoryAgain() {
    PlaybackStats stats = new PlaybackStats();
    FakeProbe probe = new FakeProbe();
    stats.setStated(4_000_000, -1, -1);
    probe.isLoading = false;
    probe.buffered = 14_000;
    long now = 0;
    for (int second = 0; second < 12; second++) stats.tick(probe, now += 1_000);
    stats.stop(); // hidden for a minute
    probe.buffered = 6_000;
    stats.tick(probe, now += 60_000);
    assertEquals(MARGINAL, stats.verdict());
  }

  @Test
  public void throughputFromBeforeAGapInSamplingNoLongerCounts() {
    PlaybackStats stats = new PlaybackStats();
    FakeProbe probe = new FakeProbe();
    stats.setStated(4_000_000, -1, -1);
    probe.buffered = 4_000;
    long now = 0;
    for (int second = 0; second < 6; second++) {
      stats.countNetworkBytes(1_250_000); // 10 Mb/s while loading
      stats.tick(probe, now += 1_000);
    }
    assertEquals(SUSTAINABLE, stats.verdict());
    stats.stop();
    probe.isLoading = false;
    stats.tick(probe, now += 60_000);
    assertEquals(MARGINAL, stats.verdict());
  }

  @Test
  public void withoutCountedSamplesTheTracksStatedRatesDecide() {
    PlaybackStats stats = new PlaybackStats();
    FakeProbe probe = new FakeProbe(); // an adaptive stream: nothing demuxed through the taps
    probe.videoFormat = 4_000_000;
    probe.audioFormat = 128_000;
    probe.buffered = 20_000;
    stats.tick(probe, 1_000);
    assertEquals(SUSTAINABLE, stats.verdict());
  }
}
