package dev.queuepilot.player;

import static dev.queuepilot.player.Sustainability.MARGINAL;
import static dev.queuepilot.player.Sustainability.NOT_SUSTAINABLE;
import static dev.queuepilot.player.Sustainability.SUSTAINABLE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.media3.common.Player;
import org.junit.Test;

public class PlaybackStatsTest {
  /** A player with values a test sets directly. */
  static final class FakeProbe implements PlaybackStats.Probe {
    long position, duration = -1, buffered, estimate = -1, dropped, rendered, videoFormat = -1, audioFormat = -1;
    boolean isPlaying = true, isLoading = true;
    Object counters = new Object();

    @Override
    public long positionMs() {
      return position;
    }

    @Override
    public long durationMs() {
      return duration;
    }

    @Override
    public long bufferedMs() {
      return buffered;
    }

    @Override
    public boolean isPlaying() {
      return isPlaying;
    }

    @Override
    public boolean isLoading() {
      return isLoading;
    }

    @Override
    public long bandwidthEstimateBps() {
      return estimate;
    }

    @Override
    public Object videoCounters() {
      return counters;
    }

    @Override
    public long droppedFrames() {
      return dropped;
    }

    @Override
    public long renderedFrames() {
      return rendered;
    }

    @Override
    public long formatVideoBps() {
      return videoFormat;
    }

    @Override
    public long formatAudioBps() {
      return audioFormat;
    }
  }

  /** 4 Mb/s of video (24 frames of 20,833 bytes) and 192 kb/s of audio (50 of 480). */
  static void media(PlaybackStats stats, int seconds) {
    for (long frame = 0; frame < seconds * 24L; frame++)
      stats.media.sample(MediaBitrateMeter.VIDEO, frame * 1_000_000 / 24, 20_833);
    for (long frame = 0; frame < seconds * 50L; frame++)
      stats.media.sample(MediaBitrateMeter.AUDIO, frame * 20_000, 480);
  }

  @Test
  public void aSampleReadsTheRatesAtThePlayheadAndTheNetwork() {
    PlaybackStats stats = new PlaybackStats();
    FakeProbe probe = new FakeProbe();
    media(stats, 30);
    probe.position = 10_400;
    probe.buffered = 18_000;
    probe.estimate = 50_000_000;
    stats.tick(probe, 1_000);
    assertTrue("No rate before a second has passed", Float.isNaN(stats.networkBps.latest()));
    stats.countNetworkBytes(1_250_000);
    probe.position = 11_400;
    probe.rendered = 24;
    stats.tick(probe, 2_000);
    assertEquals(24 * 20_833 * 8, stats.videoBps.latest(), 1);
    assertEquals(50 * 480 * 8, stats.audioBps.latest(), 1);
    assertEquals(24 * 20_833 * 8 + 50 * 480 * 8, stats.mediaBps.latest(), 1);
    assertEquals(10_000_000, stats.networkBps.latest(), 1);
    assertEquals(50_000_000, stats.estimateBps.latest(), 1);
    assertEquals(18f, stats.bufferedSeconds.latest(), 0);
    assertEquals(24f, stats.framesPerSecond(), 0.01);
    // 10 Mb/s while loading, against 4.2 Mb/s of media: more than 1.2 times.
    assertEquals(SUSTAINABLE, stats.verdict());
  }

  @Test
  public void plexsStatedBitrateIsTheMediaRateForTheVerdict() {
    PlaybackStats stats = new PlaybackStats();
    FakeProbe probe = new FakeProbe();
    media(stats, 30);
    stats.setStated(20_000_000, -1, -1);
    probe.buffered = 4_000;
    for (int second = 1; second <= 3; second++) {
      probe.position = second * 1_000L + 500;
      stats.countNetworkBytes(1_250_000); // 10 Mb/s: enough for the measured 4.2, not for 20
      stats.tick(probe, second * 1_000L);
    }
    assertEquals(MARGINAL, stats.verdict());
    assertEquals(20_000_000, stats.statedTotalBps());
  }

  @Test
  public void throughputCountsOnlyWhileLoadingAtBothEnds() {
    PlaybackStats stats = new PlaybackStats();
    FakeProbe probe = new FakeProbe();
    media(stats, 30);
    probe.buffered = 5_000;
    probe.isLoading = false; // a full buffer: the network only keeps pace
    for (int second = 1; second <= 4; second++) {
      probe.position = second * 1_000L + 500;
      stats.countNetworkBytes(1_250_000);
      stats.tick(probe, second * 1_000L);
    }
    assertEquals(MARGINAL, stats.verdict());
  }

  @Test
  public void formatBitratesFillTheStatedTrackRatesOnlyWhenPlexGaveNone() {
    PlaybackStats stats = new PlaybackStats();
    FakeProbe probe = new FakeProbe();
    probe.videoFormat = 3_000_000;
    probe.audioFormat = 640_000;
    stats.tick(probe, 1_000);
    assertEquals(3_000_000, stats.statedVideoBps());
    assertEquals(640_000, stats.statedAudioBps());
    PlaybackStats stated = new PlaybackStats();
    stated.setStated(9_000_000, 8_000_000, 1_000_000);
    stated.tick(probe, 1_000);
    assertEquals(8_000_000, stated.statedVideoBps());
  }

  @Test
  public void whilePausedTheBufferAndThroughputKeepMovingAndTheRatesHold() {
    PlaybackStats stats = new PlaybackStats();
    FakeProbe probe = new FakeProbe();
    media(stats, 30);
    probe.position = 10_400;
    probe.buffered = 3_000;
    stats.tick(probe, 1_000);
    probe.position = 11_400;
    stats.tick(probe, 2_000);
    float video = stats.videoBps.latest(), audio = stats.audioBps.latest();
    assertEquals(24 * 20_833 * 8, video, 1);
    long averageVideo = stats.averageVideoBps(), averageAudio = stats.averageAudioBps();
    assertTrue(averageVideo > 0 && averageAudio > 0);
    // Paused, then a seek while paused to a second not demuxed yet: the rates hold, and the
    // buffer fills at 2 Mb/s while the viewer watches.
    probe.isPlaying = false;
    probe.position = 25_000;
    for (int second = 3; second <= 7; second++) {
      probe.buffered += 1_000;
      stats.countNetworkBytes(250_000);
      // Richer media keeps arriving from the network: the averages still hold while paused.
      stats.media.sample(MediaBitrateMeter.VIDEO, (30 + second) * 1_000_000L, 2_000_000);
      stats.media.sample(MediaBitrateMeter.AUDIO, (30 + second) * 1_000_000L, 100_000);
      stats.tick(probe, second * 1_000L);
      assertEquals(video, stats.videoBps.latest(), 0);
      assertEquals(audio, stats.audioBps.latest(), 0);
      assertEquals(2_000_000, stats.networkBps.latest(), 1);
      assertEquals(averageVideo, stats.averageVideoBps());
      assertEquals(averageAudio, stats.averageAudioBps());
    }
    assertEquals(8f, stats.bufferedSeconds.latest(), 0);
    assertEquals(7, stats.bufferedSeconds.size());
    // Paused while the buffer fills is never draining.
    assertTrue(stats.verdict() != NOT_SUSTAINABLE);
    // Playing again reads the playhead's second.
    probe.isPlaying = true;
    probe.position = 12_400;
    stats.tick(probe, 8_000);
    assertEquals(24 * 20_833 * 8, stats.videoBps.latest(), 1);
  }

  @Test
  public void droppedFramesSurviveANewDecoder() {
    PlaybackStats stats = new PlaybackStats();
    FakeProbe probe = new FakeProbe();
    probe.dropped = 5;
    stats.tick(probe, 1_000);
    probe.dropped = 7;
    stats.tick(probe, 2_000);
    probe.counters = new Object(); // the renderer is enabled again: counts start from zero
    probe.dropped = 2;
    stats.tick(probe, 3_000);
    assertEquals(9, stats.droppedFrames());
  }

  @Test
  public void aReenabledRendererCountsItsDropsInFull() {
    PlaybackStats stats = new PlaybackStats();
    FakeProbe probe = new FakeProbe();
    probe.dropped = 5;
    stats.tick(probe, 1_000);
    probe.counters = new Object();
    probe.dropped = 7; // 7 new drops, more than the old renderer's 5
    stats.tick(probe, 2_000);
    assertEquals(12, stats.droppedFrames());
    probe.counters = null; // video disabled
    probe.dropped = 0;
    stats.tick(probe, 3_000);
    probe.counters = new Object();
    probe.dropped = 3;
    stats.tick(probe, 4_000);
    assertEquals(15, stats.droppedFrames());
  }

  @Test
  public void aStallWhilePlayingIsARebufferAndItsTimeCounts() {
    PlaybackStats stats = new PlaybackStats();
    stats.rebuffers.playWhenReadyChanged(true, 0);
    stats.rebuffers.playbackStateChanged(Player.STATE_BUFFERING, 0); // the first load is not a rebuffer
    stats.rebuffers.playbackStateChanged(Player.STATE_READY, 2_000);
    assertEquals(0, stats.rebuffers.count());
    stats.rebuffers.playbackStateChanged(Player.STATE_BUFFERING, 10_000);
    assertTrue(stats.rebuffers.isRebuffering());
    assertEquals(1, stats.rebuffers.count());
    assertEquals(500, stats.rebuffers.ms(10_500));
    stats.rebuffers.playbackStateChanged(Player.STATE_READY, 11_500);
    assertFalse(stats.rebuffers.isRebuffering());
    assertEquals(1_500, stats.rebuffers.ms(99_000));
  }

  @Test
  public void aSeeksWaitIsNotARebuffer() {
    PlaybackStats stats = new PlaybackStats();
    stats.rebuffers.playWhenReadyChanged(true, 0);
    stats.rebuffers.playbackStateChanged(Player.STATE_READY, 0);
    stats.seeked(5_000);
    stats.rebuffers.playbackStateChanged(Player.STATE_BUFFERING, 5_000);
    stats.rebuffers.playbackStateChanged(Player.STATE_READY, 6_000);
    assertEquals(0, stats.rebuffers.count());
    // After the seek has settled, a stall counts again.
    stats.rebuffers.playbackStateChanged(Player.STATE_BUFFERING, 9_000);
    assertEquals(1, stats.rebuffers.count());
    // A seek during a rebuffer ends it.
    stats.seeked(9_400);
    assertEquals(400, stats.rebuffers.ms(20_000));
  }

  @Test
  public void aPausedStallIsNotARebuffer() {
    PlaybackStats stats = new PlaybackStats();
    stats.rebuffers.playWhenReadyChanged(true, 0);
    stats.rebuffers.playbackStateChanged(Player.STATE_READY, 0);
    stats.rebuffers.playWhenReadyChanged(false, 1_000);
    stats.rebuffers.playbackStateChanged(Player.STATE_BUFFERING, 1_000);
    assertEquals(0, stats.rebuffers.count());
    // Pausing during a rebuffer ends its time.
    stats.rebuffers.playWhenReadyChanged(true, 2_000);
    stats.rebuffers.playbackStateChanged(Player.STATE_READY, 2_000);
    stats.rebuffers.playbackStateChanged(Player.STATE_BUFFERING, 3_000);
    stats.rebuffers.playWhenReadyChanged(false, 3_250);
    assertEquals(250, stats.rebuffers.ms(10_000));
  }

  @Test
  public void stoppingLeavesAGapInsteadOfAFalseRate() {
    PlaybackStats stats = new PlaybackStats();
    FakeProbe probe = new FakeProbe();
    stats.tick(probe, 1_000);
    stats.stop();
    stats.countNetworkBytes(50_000_000); // bytes read while nothing was shown
    stats.tick(probe, 60_000);
    assertTrue(Float.isNaN(stats.networkBps.latest()));
  }

  /**
   * This thread's allocated bytes, from the JVM's ThreadMXBean. Unit tests compile against
   * android.jar, which has no java.lang.management, so it is reached by reflection.
   */
  private static long allocatedBytes() throws Exception {
    Object bean =
        Class.forName("java.lang.management.ManagementFactory")
            .getMethod("getThreadMXBean")
            .invoke(null);
    return (Long)
        Class.forName("com.sun.management.ThreadMXBean")
            .getMethod("getThreadAllocatedBytes", long.class)
            .invoke(bean, Thread.currentThread().getId());
  }

  @Test
  public void aSampleAllocatesNothing() throws Exception {
    PlaybackStats stats = new PlaybackStats();
    FakeProbe probe = new FakeProbe();
    media(stats, 120);
    long now = 0;
    for (int warm = 0; warm < 20_000; warm++) {
      probe.position = 1_000 + warm % 100_000;
      stats.countNetworkBytes(1_000);
      stats.tick(probe, now += 1_000);
    }
    long before = allocatedBytes();
    for (int sample = 0; sample < 10_000; sample++) {
      probe.position = 1_000 + sample * 10L;
      stats.countNetworkBytes(1_000);
      stats.tick(probe, now += 1_000);
    }
    long allocated = allocatedBytes() - before;
    System.out.println("Allocated " + allocated + " bytes in 10,000 samples");
    // The measurement itself may allocate a few bytes; a sample that allocated would be 160 kB+.
    assertTrue("Allocated " + allocated + " bytes in 10,000 samples", allocated < 4_096);
  }
}
