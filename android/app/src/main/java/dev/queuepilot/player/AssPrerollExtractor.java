package dev.queuepilot.player;

import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.extractor.*;
import java.io.IOException;

/** Retain short overlapping cues when the video seek point starts after the cue. */
@androidx.media3.common.util.UnstableApi
final class AssPrerollExtractor implements Extractor {
  private final Extractor delegate;
  private volatile boolean hasAss;

  AssPrerollExtractor(Extractor delegate) {
    this.delegate = delegate;
  }

  @Override
  public boolean sniff(ExtractorInput input) throws IOException {
    return delegate.sniff(input);
  }

  @Override
  public void init(ExtractorOutput output) {
    delegate.init(
        new ForwardingExtractorOutput(output) {
          @Override
          public TrackOutput track(int id, int type) {
            return new ForwardingTrackOutput(super.track(id, type)) {
              @Override
              public void format(Format format) {
                hasAss |= MimeTypes.TEXT_SSA.equals(format.sampleMimeType);
                super.format(format);
              }
            };
          }

          @Override
          public void seekMap(SeekMap map) {
            super.seekMap(new AssSeekMap(map, () -> hasAss));
          }
        });
  }

  @Override
  public int read(ExtractorInput input, PositionHolder position) throws IOException {
    return delegate.read(input, position);
  }

  @Override
  public void seek(long position, long timeUs) {
    delegate.seek(position, timeUs);
  }

  @Override
  public void release() {
    delegate.release();
  }
}
