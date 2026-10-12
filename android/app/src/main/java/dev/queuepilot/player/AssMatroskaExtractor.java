package dev.queuepilot.player;

import androidx.media3.common.ParserException;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.mkv.MatroskaExtractor;
import androidx.media3.extractor.text.SubtitleParser;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/** Uses the public EBML extension hooks; no reflection or Media3 fork. */
@androidx.media3.common.util.UnstableApi
final class AssMatroskaExtractor extends MatroskaExtractor {
  private final AssFonts fonts;
  private final AssSeekReader seekReader;
  private final java.util.ArrayList<AssCueIndex.Cue> cueIndex = new java.util.ArrayList<>();
  private long segment, cueTime, cueTrack, cueCluster, cueRelative, cueDuration;
  private String filename = "", mime = "";
  private File attachment;

  AssMatroskaExtractor(SubtitleParser.Factory parsers, AssFonts fonts, AssSeekReader seekReader) {
    super(parsers);
    this.fonts = fonts;
    this.seekReader = seekReader;
  }

  @Override
  protected int getElementType(int id) {
    return switch (id) {
      case 0x1941A469, 0x61A7 -> 1; // Attachments, AttachedFile: master
      case 0x466E, 0x4660 -> 3; // FileName, FileMimeType: string
      case 0xB2 -> 2; // CueDuration
      case 0x465C -> 4; // FileData: binary
      default -> super.getElementType(id);
    };
  }

  @Override
  protected void startMasterElement(int id, long position, long size) throws ParserException {
    if (id == 0x18538067) segment = position;
    if (id == 0x1c53bb6b) cueIndex.clear();
    if (id == 0xB7) {
      cueTrack = -1;
      cueCluster = -1;
      cueRelative = -1;
      cueDuration = -1;
    }
    if (id == 0x61A7) {
      filename = "";
      mime = "";
      attachment = null;
    }
    super.startMasterElement(id, position, size);
  }

  @Override
  protected void integerElement(int id, long value) throws ParserException {
    switch (id) {
      case 0x2ad7b1:
        seekReader.scaleNs = value;
        break;
      case 0xB3:
        cueTime = value;
        break;
      case 0xF7:
        cueTrack = value;
        break;
      case 0xF1:
        cueCluster = value;
        break;
      case 0xF0:
        cueRelative = value;
        break;
      case 0xB2:
        cueDuration = value;
        return;
    }
    super.integerElement(id, value);
  }

  @Override
  protected void stringElement(int id, String value) throws ParserException {
    if (id == 0x466E) filename = value;
    else if (id == 0x4660) mime = value;
    else super.stringElement(id, value);
  }

  @Override
  protected void binaryElement(int id, int size, ExtractorInput input) throws IOException {
    if (id != 0x465C) {
      super.binaryElement(id, size, input);
      return;
    }
    if (size <= 0 || size > AssFonts.MAX_FONT_BYTES) {
      input.skipFully(size);
      return;
    }
    attachment = fonts.temporary();
    try (FileOutputStream output = new FileOutputStream(attachment)) {
      byte[] chunk = new byte[64 * 1024];
      for (int remaining = size; remaining > 0; ) {
        int count = Math.min(remaining, chunk.length);
        input.readFully(chunk, 0, count);
        output.write(chunk, 0, count);
        remaining -= count;
      }
    }
  }

  @Override
  protected void endMasterElement(int id) throws ParserException {
    if (id == 0xB7 && cueTrack > 0 && cueCluster >= 0 && cueIndex.size() < 1000000) {
      long scale = seekReader.scaleNs;
      cueIndex.add(
          new AssCueIndex.Cue(
              (int) cueTrack,
              cueTime * scale / 1000000,
              cueDuration < 0 ? -1 : cueDuration * scale / 1000000,
              segment + cueCluster,
              cueRelative));
    }
    if (id == 0x1c53bb6b) seekReader.index.publish(cueIndex);

    if (id == 0x61A7) {
      if (attachment != null) fonts.add(filename, mime, attachment);
      attachment = null;
    }
    super.endMasterElement(id);
  }
}
