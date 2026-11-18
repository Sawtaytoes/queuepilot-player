package dev.queuepilot.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.os.Handler;
import androidx.media3.common.C;
import androidx.media3.common.DataReader;
import androidx.media3.common.Format;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;
import androidx.media3.exoplayer.upstream.BandwidthMeter;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.SeekMap;
import androidx.media3.extractor.TrackOutput;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

@androidx.media3.common.util.UnstableApi
public class PlaybackStatsTapsTest {
  /** Records what reaches the real output. */
  static final class RecordingOutput implements ExtractorOutput, TrackOutput {
    final List<Long> sampleTimes = new ArrayList<>();
    boolean hasEnded;

    @Override
    public TrackOutput track(int id, int type) {
      return this;
    }

    @Override
    public void endTracks() {
      hasEnded = true;
    }

    @Override
    public void seekMap(SeekMap seekMap) {}

    @Override
    public void format(Format format) {}

    @Override
    public int sampleData(DataReader input, int length, boolean allowEndOfInput, int part) {
      return length;
    }

    @Override
    public void sampleData(ParsableByteArray data, int length, int part) {}

    @Override
    public void sampleMetadata(
        long timeUs, int flags, int size, int offset, TrackOutput.CryptoData cryptoData) {
      sampleTimes.add(timeUs);
    }
  }

  /** An extractor that writes one video and one audio sample per read. */
  static final class FakeExtractor implements Extractor {
    ExtractorOutput output;
    long seekedTo = -1;
    boolean isReleased;

    @Override
    public boolean sniff(ExtractorInput input) {
      return true;
    }

    @Override
    public void init(ExtractorOutput output) {
      this.output = output;
    }

    @Override
    public int read(ExtractorInput input, PositionHolder seekPosition) {
      TrackOutput video = output.track(0, C.TRACK_TYPE_VIDEO);
      video.format(new Format.Builder().setId("1").build());
      video.sampleMetadata(2_500_000, 0, 50_000, 0, null);
      TrackOutput audio = output.track(1, C.TRACK_TYPE_AUDIO);
      audio.format(new Format.Builder().setId("2").build());
      audio.sampleMetadata(2_500_000, 0, 1_000, 0, null);
      output.endTracks();
      return RESULT_CONTINUE;
    }

    @Override
    public void seek(long position, long timeUs) {
      seekedTo = timeUs;
    }

    @Override
    public void release() {
      isReleased = true;
    }
  }

  @Test
  public void withoutStatsTheExtractorsAreUntouched() {
    ExtractorsFactory factory = () -> new Extractor[] {new FakeExtractor()};
    assertSame(factory, PlaybackStatsTaps.tap(null, factory));
  }

  @Test
  public void everySampleIsCountedAndStillDelivered() throws Exception {
    PlaybackStats stats = new PlaybackStats();
    FakeExtractor fake = new FakeExtractor();
    Extractor[] extractors = PlaybackStatsTaps.tap(stats, () -> new Extractor[] {fake}).createExtractors();
    RecordingOutput output = new RecordingOutput();
    extractors[0].init(output);
    extractors[0].read(null, null);
    assertEquals(List.of(2_500_000L, 2_500_000L), output.sampleTimes);
    assertTrue(output.hasEnded);
    assertEquals(400_000, stats.media.bitsPerSecondAt(MediaBitrateMeter.VIDEO, 2));
    assertEquals(8_000, stats.media.bitsPerSecondAt(MediaBitrateMeter.AUDIO, 2));
    assertSame("Media3 still sees the real extractor", fake, extractors[0].getUnderlyingImplementation());
    extractors[0].seek(0, 2_000_000);
    assertEquals(2_000_000, fake.seekedTo);
    // The same sample demuxed again after the seek is not counted twice.
    extractors[0].read(null, null);
    assertEquals(400_000, stats.media.bitsPerSecondAt(MediaBitrateMeter.VIDEO, 2));
    extractors[0].release();
    assertTrue(fake.isReleased);
  }

  @Test
  public void anUnselectedTrackIsDemuxedButNotCountedAsConsumed() throws Exception {
    PlaybackStats stats = new PlaybackStats();
    stats.media.select(MediaBitrateMeter.AUDIO, "7"); // another audio track is playing
    Extractor[] extractors =
        PlaybackStatsTaps.tap(stats, () -> new Extractor[] {new FakeExtractor()}).createExtractors();
    extractors[0].init(new RecordingOutput());
    extractors[0].read(null, null);
    assertEquals(MediaBitrateMeter.UNKNOWN, stats.media.bitsPerSecondAt(MediaBitrateMeter.AUDIO, 2));
    assertEquals(400_000, stats.media.bitsPerSecondAt(MediaBitrateMeter.VIDEO, 2));
    assertEquals(408_000, stats.media.bitsPerSecondAt(MediaBitrateMeter.ALL, 2));
  }

  @Test
  public void onlyNetworkBytesAreCountedAndTheRealMeterStillHearsAll() {
    int[] heard = new int[1];
    TransferListener listener =
        new TransferListener() {
          @Override
          public void onTransferInitializing(DataSource source, DataSpec spec, boolean isNetwork) {}

          @Override
          public void onTransferStart(DataSource source, DataSpec spec, boolean isNetwork) {}

          @Override
          public void onBytesTransferred(
              DataSource source, DataSpec spec, boolean isNetwork, int bytes) {
            heard[0] += bytes;
          }

          @Override
          public void onTransferEnd(DataSource source, DataSpec spec, boolean isNetwork) {}
        };
    BandwidthMeter real =
        new BandwidthMeter() {
          @Override
          public long getBitrateEstimate() {
            return 42_000_000;
          }

          @Override
          public TransferListener getTransferListener() {
            return listener;
          }

          @Override
          public void addEventListener(Handler handler, EventListener eventListener) {}

          @Override
          public void removeEventListener(EventListener eventListener) {}
        };
    PlaybackStats stats = new PlaybackStats();
    PlaybackStatsTaps.CountingBandwidthMeter meter =
        new PlaybackStatsTaps.CountingBandwidthMeter(real, stats);
    meter.getTransferListener().onBytesTransferred(null, null, true, 125_000);
    meter.getTransferListener().onBytesTransferred(null, null, false, 999_999);
    assertEquals(125_000 + 999_999, heard[0]);
    assertEquals(42_000_000, meter.getBitrateEstimate());
    PlaybackStatsTest.FakeProbe probe = new PlaybackStatsTest.FakeProbe();
    stats.tick(probe, 0);
    meter.getTransferListener().onBytesTransferred(null, null, true, 125_000);
    stats.tick(probe, 1_000);
    assertEquals("Only the network bytes since the last sample", 1_000_000, stats.networkBps.latest(), 1);
  }
}
