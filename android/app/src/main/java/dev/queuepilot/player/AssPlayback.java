package dev.queuepilot.player;

import android.content.Context;
import android.net.Uri;
import android.os.Looper;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.text.TextOutput;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.mkv.MatroskaExtractor;
import androidx.media3.extractor.text.DefaultSubtitleParserFactory;
import androidx.media3.extractor.text.SubtitleParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@androidx.media3.common.util.UnstableApi
final class AssPlayback {
  final AssFonts fonts;
  final AssSeekReader seekReader;
  final AssOverlay overlay;
  private AssRenderer renderer;
  private int videoWidth = 1920, videoHeight = 1080;

  AssPlayback(Context context, androidx.media3.datasource.DataSource.Factory factory, Uri uri) {
    seekReader = new AssSeekReader(factory, uri);
    fonts = new AssFonts(context);
    overlay = new AssOverlay(context);
    overlay.addOnLayoutChangeListener(
        (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> updateViewport());
  }

  void close() {
    seekReader.close();
    fonts.close();
  }

  void videoSize(int width, int height) {
    if (width <= 0 || height <= 0) return;
    videoWidth = width;
    videoHeight = height;
    updateViewport();
  }

  private void updateViewport() {
    if (renderer == null || overlay.getWidth() == 0 || overlay.getHeight() == 0) return;
    float scale =
        Math.min(
            (float) overlay.getWidth() / videoWidth, (float) overlay.getHeight() / videoHeight);
    renderer.videoSize(
        Math.max(1, Math.round(videoWidth * scale)), Math.max(1, Math.round(videoHeight * scale)));
  }

  DefaultRenderersFactory renderers(Context context) {
    return new DefaultRenderersFactory(context) {
      @Override
      protected void buildTextRenderers(
          Context context, TextOutput output, Looper looper, int mode, ArrayList<Renderer> out) {
        renderer = new AssRenderer(fonts, overlay, seekReader);
        out.add(renderer);
        super.buildTextRenderers(context, output, looper, mode, out);
      }
    };
  }

  ExtractorsFactory extractors() {
    SubtitleParser.Factory defaults = new DefaultSubtitleParserFactory();
    SubtitleParser.Factory parsers =
        new SubtitleParser.Factory() {
          @Override
          public boolean supportsFormat(Format format) {
            return !(MimeTypes.TEXT_SSA.equals(format.sampleMimeType)
                    && MimeTypes.VIDEO_MATROSKA.equals(format.containerMimeType))
                && defaults.supportsFormat(format);
          }

          @Override
          public int getCueReplacementBehavior(Format format) {
            return defaults.getCueReplacementBehavior(format);
          }

          @Override
          public SubtitleParser create(Format format) {
            return defaults.create(format);
          }
        };
    DefaultExtractorsFactory defaultsFactory =
        new DefaultExtractorsFactory().setSubtitleParserFactory(parsers);
    return new ExtractorsFactory() {
      private Extractor[] replace(Extractor[] extractors) {
        for (int index = 0; index < extractors.length; index++)
          if (extractors[index] instanceof MatroskaExtractor)
            extractors[index] =
                new AssPrerollExtractor(new AssMatroskaExtractor(parsers, fonts, seekReader));
        return extractors;
      }

      @Override
      public Extractor[] createExtractors() {
        return replace(defaultsFactory.createExtractors());
      }

      @Override
      public Extractor[] createExtractors(Uri uri, Map<String, List<String>> headers) {
        return replace(defaultsFactory.createExtractors(uri, headers));
      }
    };
  }
}
