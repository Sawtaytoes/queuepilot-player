package dev.queuepilot.player;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import androidx.annotation.Nullable;
import androidx.media3.common.Format;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.upstream.BandwidthMeter;
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.ForwardingExtractorOutput;
import androidx.media3.extractor.ForwardingTrackOutput;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.SniffFailure;
import androidx.media3.extractor.TrackOutput;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Where {@link PlaybackStats} counts: every demuxed sample, through the extractors, and every
 * byte read from the network, through the bandwidth meter that Media3 hands to its data sources.
 * With no stats (the usual case) each tap returns its input unchanged, so playback is untouched.
 */
@androidx.media3.common.util.UnstableApi
final class PlaybackStatsTaps {
  private PlaybackStatsTaps() {}

  /** The player's builder, counting network bytes into {@code stats} when there are stats. */
  static ExoPlayer.Builder tap(
      @Nullable PlaybackStats stats, ExoPlayer.Builder builder, Context context) {
    if (stats == null) return builder;
    CountingBandwidthMeter meter =
        new CountingBandwidthMeter(DefaultBandwidthMeter.getSingletonInstance(context), stats);
    stats.setBandwidthMeter(meter);
    return builder.setBandwidthMeter(meter);
  }

  /** The extractors, counting every sample into {@code stats} when there are stats. */
  static ExtractorsFactory tap(@Nullable PlaybackStats stats, ExtractorsFactory factory) {
    if (stats == null) return factory;
    return new ExtractorsFactory() {
      @Override
      public Extractor[] createExtractors() {
        return wrap(factory.createExtractors());
      }

      @Override
      public Extractor[] createExtractors(Uri uri, Map<String, List<String>> headers) {
        return wrap(factory.createExtractors(uri, headers));
      }

      private Extractor[] wrap(Extractor[] extractors) {
        for (int index = 0; index < extractors.length; index++)
          extractors[index] = new CountingExtractor(extractors[index], stats.media);
        return extractors;
      }
    };
  }

  /** An extractor that tells the meter each sample's kind, time and size. */
  static final class CountingExtractor implements Extractor {
    private final Extractor delegate;
    private final MediaBitrateMeter meter;

    CountingExtractor(Extractor delegate, MediaBitrateMeter meter) {
      this.delegate = delegate;
      this.meter = meter;
    }

    @Override
    public boolean sniff(ExtractorInput input) throws IOException {
      return delegate.sniff(input);
    }

    @Override
    public List<SniffFailure> getSniffFailureDetails() {
      return delegate.getSniffFailureDetails();
    }

    @Override
    public void init(ExtractorOutput output) {
      delegate.init(
          new ForwardingExtractorOutput(output) {
            @Override
            public TrackOutput track(int id, int type) {
              int kind = MediaBitrateMeter.kindOf(type);
              return new ForwardingTrackOutput(super.track(id, type)) {
                /**
                 * This track's counter, found by its format id, which Media3 keeps as the id of
                 * the track it reports selected.
                 */
                private int counter = meter.track(kind, null);

                @Override
                public void format(Format format) {
                  counter = meter.track(kind, format.id);
                  super.format(format);
                }

                @Override
                public void sampleMetadata(
                    long timeUs, int flags, int size, int offset, @Nullable CryptoData cryptoData) {
                  meter.count(counter, timeUs, size);
                  super.sampleMetadata(timeUs, flags, size, offset, cryptoData);
                }
              };
            }
          });
    }

    @Override
    public int read(ExtractorInput input, PositionHolder seekPosition) throws IOException {
      return delegate.read(input, seekPosition);
    }

    @Override
    public void seek(long position, long timeUs) {
      // The loader starts again at another place: reloaded seconds must not count twice.
      meter.discontinuity();
      delegate.seek(position, timeUs);
    }

    @Override
    public void release() {
      delegate.release();
    }

    @Override
    public Extractor getUnderlyingImplementation() {
      // Media3 checks the real extractor's class (an MP3 one disables seeking, for example).
      return delegate.getUnderlyingImplementation();
    }
  }

  /** Media3's own meter, with every network byte its data sources read also counted. */
  static final class CountingBandwidthMeter implements BandwidthMeter, TransferListener {
    private final BandwidthMeter delegate;
    @Nullable private final TransferListener delegateListener;
    private final PlaybackStats stats;

    CountingBandwidthMeter(BandwidthMeter delegate, PlaybackStats stats) {
      this.delegate = delegate;
      this.delegateListener = delegate.getTransferListener();
      this.stats = stats;
    }

    @Override
    public long getBitrateEstimate() {
      return delegate.getBitrateEstimate();
    }

    @Override
    public long getTimeToFirstByteEstimateUs() {
      return delegate.getTimeToFirstByteEstimateUs();
    }

    @Override
    public TransferListener getTransferListener() {
      return this;
    }

    @Override
    public void addEventListener(Handler eventHandler, EventListener eventListener) {
      delegate.addEventListener(eventHandler, eventListener);
    }

    @Override
    public void removeEventListener(EventListener eventListener) {
      delegate.removeEventListener(eventListener);
    }

    @Override
    public void onTransferInitializing(DataSource source, DataSpec dataSpec, boolean isNetwork) {
      if (delegateListener != null)
        delegateListener.onTransferInitializing(source, dataSpec, isNetwork);
    }

    @Override
    public void onTransferStart(DataSource source, DataSpec dataSpec, boolean isNetwork) {
      if (delegateListener != null) delegateListener.onTransferStart(source, dataSpec, isNetwork);
    }

    @Override
    public void onBytesTransferred(
        DataSource source, DataSpec dataSpec, boolean isNetwork, int bytesTransferred) {
      if (isNetwork) stats.countNetworkBytes(bytesTransferred);
      if (delegateListener != null)
        delegateListener.onBytesTransferred(source, dataSpec, isNetwork, bytesTransferred);
    }

    @Override
    public void onTransferEnd(DataSource source, DataSpec dataSpec, boolean isNetwork) {
      if (delegateListener != null) delegateListener.onTransferEnd(source, dataSpec, isNetwork);
    }
  }
}
