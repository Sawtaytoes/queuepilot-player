package dev.queuepilot.player;

import static dev.queuepilot.player.MediaBitrateMeter.ALL;
import static dev.queuepilot.player.MediaBitrateMeter.AUDIO;
import static dev.queuepilot.player.MediaBitrateMeter.OTHER;
import static dev.queuepilot.player.MediaBitrateMeter.UNKNOWN;
import static dev.queuepilot.player.MediaBitrateMeter.VIDEO;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class MediaBitrateMeterTest {
  /** 24 frames a second of {@code frameBytes}, from second {@code from} to before {@code to}. */
  static void video(MediaBitrateMeter meter, int from, int to, int frameBytes) {
    for (long frame = from * 24L; frame < to * 24L; frame++)
      meter.sample(VIDEO, frame * 1_000_000L / 24, frameBytes);
  }

  @Test
  public void mapsMedia3TrackTypes() {
    assertEquals(VIDEO, MediaBitrateMeter.kindOf(2));
    assertEquals(AUDIO, MediaBitrateMeter.kindOf(1));
    assertEquals(OTHER, MediaBitrateMeter.kindOf(3));
    assertEquals(OTHER, MediaBitrateMeter.kindOf(-1));
  }

  @Test
  public void theCurrentRateIsTheSecondThatJustPlayed() {
    MediaBitrateMeter meter = new MediaBitrateMeter();
    video(meter, 0, 5, 10_000); // 1.92 Mb/s
    video(meter, 5, 10, 30_000); // 5.76 Mb/s
    assertEquals(24 * 10_000 * 8, meter.currentBps(VIDEO, 4_999));
    assertEquals(24 * 10_000 * 8, meter.currentBps(VIDEO, 5_000));
    assertEquals(24 * 30_000 * 8, meter.currentBps(VIDEO, 6_000));
    assertEquals(UNKNOWN, meter.currentBps(VIDEO, 500)); // nothing has played yet
    assertEquals(UNKNOWN, meter.currentBps(VIDEO, 20_000)); // not demuxed
    assertEquals(UNKNOWN, meter.currentBps(AUDIO, 6_000));
  }

  @Test
  public void theAverageCoversEverySampleOverItsMediaTime() {
    MediaBitrateMeter meter = new MediaBitrateMeter();
    assertEquals(UNKNOWN, meter.averageBps(VIDEO));
    video(meter, 0, 10, 20_000);
    // 240 frames over the 239/24 s between the first and last timestamps.
    assertEquals(240L * 20_000 * 8 * 24 / 239, meter.averageBps(VIDEO), 1);
  }

  @Test
  public void aSecondDemuxedAgainAfterASeekCountsOnce() {
    MediaBitrateMeter meter = new MediaBitrateMeter();
    video(meter, 0, 10, 20_000);
    meter.discontinuity();
    // Buffered seconds are not demuxed again, so they keep their rates.
    assertEquals(24 * 20_000 * 8, meter.currentBps(VIDEO, 9_000));
    video(meter, 4, 10, 20_000); // the same seconds again, after a seek back
    assertEquals(24 * 20_000 * 8, meter.currentBps(VIDEO, 5_000));
    assertEquals(24 * 20_000 * 8, meter.currentBps(VIDEO, 10_000));
    // The average keeps the earlier span: the same rate overall.
    assertEquals(24 * 20_000 * 8, meter.averageBps(VIDEO), 24 * 20_000 * 8 / 20);
  }

  @Test
  public void theSecondADemuxerRestartsInCountsOnlyWhatFollows() {
    MediaBitrateMeter meter = new MediaBitrateMeter();
    video(meter, 0, 6, 20_000);
    meter.discontinuity();
    // A retry restarts at a key frame half way through second 3.
    for (long frame = 84; frame < 96; frame++) meter.sample(VIDEO, frame * 1_000_000 / 24, 20_000);
    assertEquals(12 * 20_000 * 8, meter.bitsPerSecondAt(VIDEO, 3));
    assertEquals(24 * 20_000 * 8, meter.bitsPerSecondAt(VIDEO, 2));
  }

  @Test
  public void aSlotIsReusedOnlyForItsOwnSecond() {
    MediaBitrateMeter meter = new MediaBitrateMeter();
    meter.sample(AUDIO, 3_000_000, 1_000);
    long later = 3 + MediaBitrateMeter.SECONDS; // the same slot, a different second
    meter.sample(AUDIO, later * 1_000_000, 500);
    assertEquals(UNKNOWN, meter.bitsPerSecondAt(AUDIO, 3));
    assertEquals(4_000, meter.bitsPerSecondAt(AUDIO, later));
  }

  @Test
  public void negativeTimestampsBelongToTheSecondBeforeZero() {
    MediaBitrateMeter meter = new MediaBitrateMeter();
    meter.sample(AUDIO, -1, 100);
    assertEquals(800, meter.bitsPerSecondAt(AUDIO, -1));
    assertEquals(UNKNOWN, meter.bitsPerSecondAt(AUDIO, 0));
  }

  @Test
  public void theSelectedTrackIsReadEvenForSecondsBufferedBeforeASwitch() {
    MediaBitrateMeter meter = new MediaBitrateMeter();
    // English (id 2) and commentary (id 3) are demuxed together, seconds 0 to 9.
    for (long second = 0; second < 10; second++) {
      meter.sample(AUDIO, "2", second * 1_000_000, 1_000);
      meter.sample(AUDIO, "3", second * 1_000_000, 500);
      meter.sample(VIDEO, "1", second * 1_000_000, 10_000);
    }
    assertEquals("Before a selection, every track", 12_000, meter.bitsPerSecondAt(AUDIO, 0));
    meter.select(AUDIO, "2");
    assertEquals(8_000, meter.bitsPerSecondAt(AUDIO, 0));
    // Ten samples spanning 9 s of media.
    assertEquals(10 * 1_000 * 8 / 9, meter.averageBps(AUDIO));
    // A switch to the commentary plays the seconds already buffered: they read its rate at once.
    meter.select(AUDIO, "3");
    assertEquals(4_000, meter.bitsPerSecondAt(AUDIO, 5));
    assertEquals("Bytes counted before the selection do not stay in", 10 * 500 * 8 / 9, meter.averageBps(AUDIO));
    assertEquals("Video has no selection: its one track", 80_000, meter.bitsPerSecondAt(VIDEO, 5));
    assertEquals("Every track, selected or not", 92_000, meter.bitsPerSecondAt(ALL, 5));
    assertEquals(10 * 11_500 * 8 / 9, meter.averageBps(ALL));
  }

  @Test
  public void aSelectedTrackThatWasNeverCountedIsUnknown() {
    MediaBitrateMeter meter = new MediaBitrateMeter();
    meter.sample(AUDIO, "2", 0, 1_000);
    meter.select(AUDIO, "9");
    assertEquals(UNKNOWN, meter.bitsPerSecondAt(AUDIO, 0));
    assertEquals(8_000, meter.bitsPerSecondAt(ALL, 0));
  }

  @Test
  public void tracksPastTheLimitAndWithoutIdsShareACounter() {
    MediaBitrateMeter meter = new MediaBitrateMeter();
    for (int track = 0; track < MediaBitrateMeter.TRACKS + 3; track++)
      meter.sample(AUDIO, "a" + track, 0, 100);
    meter.sample(AUDIO, null, 0, 100);
    meter.sample(OTHER, "subtitles", 0, 50);
    assertEquals((MediaBitrateMeter.TRACKS + 4) * 800, meter.bitsPerSecondAt(AUDIO, 0));
    assertEquals((MediaBitrateMeter.TRACKS + 4) * 800 + 400, meter.bitsPerSecondAt(ALL, 0));
    meter.select(AUDIO, "a0");
    assertEquals(800, meter.bitsPerSecondAt(AUDIO, 0));
  }

  @Test
  public void emptySamplesAreIgnored() {
    MediaBitrateMeter meter = new MediaBitrateMeter();
    meter.sample(OTHER, 0, 0);
    assertEquals(UNKNOWN, meter.bitsPerSecondAt(OTHER, 0));
  }
}
