package dev.queuepilot.player;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.Test;

public class AssCueIndexTest {
  @Test
  public void returnsAllOverlapsAcrossMinutesAndTracksWithExclusiveEnd() {
    AssCueIndex index = new AssCueIndex();
    var longCue = new AssCueIndex.Cue(5, 0, 182600, 0, 0);
    var dialogue = new AssCueIndex.Cue(5, 60000, 5000, 1, 0);
    var other = new AssCueIndex.Cue(6, 0, 182600, 2, 0);
    var unknown = new AssCueIndex.Cue(5, 20000, -1, 3, 0);
    index.publish(List.of(longCue, dialogue, other, unknown));
    assertEquals(List.of(longCue, dialogue, unknown), index.active(5, 62000));
    assertEquals(List.of(longCue, unknown), index.active(5, 65000));
    assertEquals(List.of(unknown), index.active(5, 182600));
    assertEquals(List.of(longCue), index.active(5, 0));
  }

  @Test
  public void rehydratesRawOverridesFromBlockGroupAndReadsMissingDuration() throws Exception {
    byte[] raw = "14,1,Sign,,0,0,0,,{\\move(1,2,3,4)}Hello, world".getBytes(StandardCharsets.UTF_8);
    byte[] block = concat(new byte[] {(byte) 0x85, 0, 0, 0}, raw);
    byte[] group =
        element(
            new byte[] {(byte) 0xa0},
            concat(
                element(new byte[] {(byte) 0xa1}, block),
                element(new byte[] {(byte) 0x9b}, new byte[] {0x02, (byte) 0xc9, 0x48})));
    byte[] cluster =
        concat(
            new byte[] {0x1f, 0x43, (byte) 0xb6, 0x75, (byte) 0xff},
            new byte[] {(byte) 0xe7, (byte) 0x81, 0},
            group,
            new byte[16]);
    AssCueIndex.Packet packet =
        AssCueIndex.read(new AssCueIndex.Cue(5, 5211100, -1, 0, 3), reader(cluster), 1000000);
    assertArrayEquals(raw, packet.data());
    assertEquals(182600, packet.durationMs());
    assertEquals(5211100, packet.startMs());
  }

  @Test(expected = IOException.class)
  public void rejectsWrongTrackRatherThanBorrowingItsSubtitle() throws Exception {
    byte[] group =
        element(
            new byte[] {(byte) 0xa0},
            element(new byte[] {(byte) 0xa1}, new byte[] {(byte) 0x86, 0, 0, 0, 1, 2, 3}));
    byte[] cluster =
        concat(new byte[] {0x1f, 0x43, (byte) 0xb6, 0x75, (byte) 0xff}, group, new byte[16]);
    AssCueIndex.read(new AssCueIndex.Cue(5, 0, 100, 0, 0), reader(cluster), 1000000);
  }

  @Test(expected = IOException.class)
  public void rejectsTruncatedBlockBeforeAllocatingOrIndexing() throws Exception {
    byte[] group =
        element(
            new byte[] {(byte) 0xa0}, element(new byte[] {(byte) 0xa1}, new byte[] {(byte) 0x85}));
    byte[] cluster =
        concat(new byte[] {0x1f, 0x43, (byte) 0xb6, 0x75, (byte) 0xff}, group, new byte[16]);
    AssCueIndex.read(new AssCueIndex.Cue(5, 0, 100, 0, 0), reader(cluster), 1000000);
  }

  static AssCueIndex.Reader reader(byte[] bytes) {
    return (position, length) -> {
      if (position < 0 || position + length > bytes.length) throw new IOException("EOF");
      return java.util.Arrays.copyOfRange(bytes, (int) position, (int) position + length);
    };
  }

  static byte[] element(byte[] id, byte[] data) {
    if (data.length > 126) throw new IllegalArgumentException();
    return concat(id, new byte[] {(byte) (0x80 | data.length)}, data);
  }

  static byte[] concat(byte[]... chunks) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (byte[] b : chunks) out.write(b, 0, b.length);
    return out.toByteArray();
  }
}
