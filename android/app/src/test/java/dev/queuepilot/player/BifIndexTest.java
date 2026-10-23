package dev.queuepilot.player;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import org.junit.Test;

public class BifIndexTest {
  private static final byte[] MAGIC = {
    (byte) 0x89, 0x42, 0x49, 0x46, 0x0d, 0x0a, 0x1a, 0x0a
  };

  /** A BIF with one fake "image" per timestamp; frames hold their own index as bytes. */
  static byte[] bif(int multiplier, long... times) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes(MAGIC);
    out.writeBytes(le(0));
    out.writeBytes(le(times.length));
    out.writeBytes(le(multiplier));
    out.writeBytes(new byte[64 - 20]);
    int offset = 64 + (times.length + 1) * 8;
    for (int i = 0; i < times.length; i++) {
      out.writeBytes(le(times[i]));
      out.writeBytes(le(offset));
      offset += i + 1;
    }
    out.writeBytes(le(0xffffffffL));
    out.writeBytes(le(offset));
    for (int i = 0; i < times.length; i++) {
      byte[] frame = new byte[i + 1];
      Arrays.fill(frame, (byte) i);
      out.writeBytes(frame);
    }
    return out.toByteArray();
  }

  static byte[] le(long value) {
    return new byte[] {
      (byte) value, (byte) (value >> 8), (byte) (value >> 16), (byte) (value >> 24)
    };
  }

  static void put(byte[] data, int at, long value) {
    System.arraycopy(le(value), 0, data, at, 4);
  }

  @Test
  public void readsFramesAndTheirTimes() {
    BifIndex index = BifIndex.parse(bif(1000, 0, 10, 20, 30));
    assertEquals(4, index.frameCount());
    assertEquals(20_000, index.frameTime(2));
    assertEquals(3, index.frameLength(2));
    assertEquals(2, index.data()[index.frameOffset(2)]);
  }

  @Test
  public void zeroMultiplierMeansOneSecond() {
    assertEquals(10_000, BifIndex.parse(bif(0, 0, 10)).frameTime(1));
    assertEquals(500, BifIndex.parse(bif(50, 0, 10)).frameTime(1));
  }

  @Test
  public void frameAtPicksTheFrameShowingAtThatTime() {
    BifIndex index = BifIndex.parse(bif(1000, 0, 10, 20, 30));
    assertEquals(0, index.frameAt(0));
    assertEquals(0, index.frameAt(9_999));
    assertEquals(1, index.frameAt(10_000));
    assertEquals(3, index.frameAt(3_600_000));
    assertEquals(0, index.frameAt(-5));
  }

  @Test
  public void rejectsTruncatedFiles() {
    byte[] whole = bif(1000, 0, 10, 20);
    for (int length : new int[] {0, 8, 63, 64, 70, whole.length - 1})
      assertRejected(Arrays.copyOf(whole, length));
  }

  @Test
  public void rejectsMalformedFiles() {
    byte[] badMagic = bif(1000, 0, 10);
    badMagic[1] = 'X';
    assertRejected(badMagic);

    byte[] noTerminator = bif(1000, 0, 10);
    put(noTerminator, 64 + 2 * 8, 30);
    assertRejected(noTerminator);

    byte[] backward = bif(1000, 0, 10, 20);
    put(backward, 64 + 8, 25);
    assertRejected(backward);

    byte[] overlapping = bif(1000, 0, 10, 20);
    put(overlapping, 64 + 8 + 4, 64 + 4 * 8 - 1);
    assertRejected(overlapping);

    byte[] hugeCount = bif(1000, 0, 10);
    put(hugeCount, 12, 0xfffffff0L);
    assertRejected(hugeCount);

    byte[] noFrames = bif(1000, 0);
    put(noFrames, 12, 0);
    assertRejected(noFrames);

    byte[] pastEnd = bif(1000, 0, 10);
    put(pastEnd, 64 + 2 * 8 + 4, 1 << 20);
    assertRejected(pastEnd);
  }

  @Test
  public void rejectsFilesOverTheLimit() {
    byte[] large = Arrays.copyOf(bif(1000, 0, 10), BifIndex.MAX_BYTES + 1);
    assertRejected(large);
  }

  private static void assertRejected(byte[] data) {
    assertThrows(IllegalArgumentException.class, () -> BifIndex.parse(data));
  }
}
