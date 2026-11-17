package dev.queuepilot.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ArtworkLoaderTest {
  @Test
  public void posterDerivativeDecodesAtHalfSizeForA450PixelCard() {
    // A 720 x 1080 derivative in a card 450 pixels tall (1080p at density 320, Display 100%).
    assertEquals(2, ArtworkLoader.sampleSize(720, 1080, 0, 450));
  }

  @Test
  public void titleArtworkDecodesAtAQuarterForItsView() {
    // The 1440-pixel title artwork in a 180 x 270 dp view at density 320 (360 x 540 pixels).
    assertEquals(4, ArtworkLoader.sampleSize(1440, 2160, 360, 540));
  }

  @Test
  public void neverDecodesSmallerThanTheView() {
    assertEquals(1, ArtworkLoader.sampleSize(720, 1080, 0, 541));
    assertEquals(1, ArtworkLoader.sampleSize(400, 600, 360, 540));
    // Both dimensions must still cover the view: a wide picture in a tall view keeps its height.
    assertEquals(1, ArtworkLoader.sampleSize(1920, 300, 360, 270));
    assertEquals(2, ArtworkLoader.sampleSize(1920, 1080, 360, 540));
  }

  @Test
  public void anUnknownViewSizeDecodesAtFullSize() {
    assertEquals(1, ArtworkLoader.sampleSize(720, 1080, 0, 0));
    assertEquals(1, ArtworkLoader.sampleSize(0, 0, 360, 540));
  }

  @Test
  public void exactlyHalfIsStillLargeEnough() {
    assertEquals(2, ArtworkLoader.sampleSize(720, 1080, 360, 540));
    assertEquals(8, ArtworkLoader.sampleSize(1440, 2160, 180, 270));
  }

  @Test
  public void boundsMatchTheExistingLimits() {
    assertTrue(ArtworkLoader.isAcceptable(4096, 6144));
    assertFalse(ArtworkLoader.isAcceptable(4097, 100));
    assertFalse(ArtworkLoader.isAcceptable(100, 6145));
    assertFalse(ArtworkLoader.isAcceptable(0, 100));
    assertFalse(ArtworkLoader.isAcceptable(-1, -1));
  }

  @Test
  public void cacheKeysSeparateServersProfilesAndSizes() {
    String base = ArtworkLoader.cacheKey("http://a", "token-a", "/thumb/1", 720, 0, 450);
    assertNotEquals(base, ArtworkLoader.cacheKey("http://b", "token-a", "/thumb/1", 720, 0, 450));
    assertNotEquals(base, ArtworkLoader.cacheKey("http://a", "token-b", "/thumb/1", 720, 0, 450));
    assertNotEquals(base, ArtworkLoader.cacheKey("http://a", "token-a", "/thumb/2", 720, 0, 450));
    assertNotEquals(base, ArtworkLoader.cacheKey("http://a", "token-a", "/thumb/1", 1440, 0, 450));
    assertNotEquals(base, ArtworkLoader.cacheKey("http://a", "token-a", "/thumb/1", 720, 0, 300));
    assertEquals(base, ArtworkLoader.cacheKey("http://a", "token-a", "/thumb/1", 720, 0, 450));
    // These two have the same String.hashCode(); their keys must still differ.
    assertEquals("token-Aa".hashCode(), "token-BB".hashCode());
    assertNotEquals(
        ArtworkLoader.cacheKey("http://a", "token-Aa", "/thumb/1", 720, 0, 450),
        ArtworkLoader.cacheKey("http://a", "token-BB", "/thumb/1", 720, 0, 450));
    assertFalse("The key holds no credential", base.contains("token-a"));
    assertFalse("The key never holds the credential", base.contains("token-a"));
  }
}
