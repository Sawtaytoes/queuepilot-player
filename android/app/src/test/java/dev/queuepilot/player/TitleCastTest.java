package dev.queuepilot.player;

import static org.junit.Assert.*;

import java.util.List;
import org.junit.Test;

public class TitleCastTest {
  @Test
  public void episodesInheritFromSeasonThenShow() {
    assertEquals(List.of("101", "100"), TitleCast.inheritedKeys("episode", "101", "100"));
    assertEquals(List.of("100"), TitleCast.inheritedKeys("episode", "", "100"));
  }

  @Test
  public void otherTitlesAndUnsafeKeysInheritNothing() {
    assertTrue(TitleCast.inheritedKeys("movie", "101", "100").isEmpty());
    assertTrue(TitleCast.inheritedKeys("show", "", "").isEmpty());
    assertTrue(TitleCast.inheritedKeys("episode", "../100", "100?x=1").isEmpty());
    assertTrue(TitleCast.inheritedKeys("episode", null, null).isEmpty());
  }

  @Test
  public void guestCreditsComeFirstAndShowDuplicatesCollapse() {
    record Credit(String kind, String name, String role) {}
    List<Credit> merged =
        TitleCast.distinct(
            List.of(
                new Credit("Role", "Nadia Lane", "Captain Mira (guest)"),
                new Credit("Director", "Nadia Lane", "Director"),
                new Credit("Role", "nadia lane ", "Captain Mira"),
                new Credit("Role", "Omar Reed", "Sol")),
            credit -> TitleCast.identity(credit.kind(), credit.name()));
    assertEquals(3, merged.size());
    assertEquals("Captain Mira (guest)", merged.get(0).role());
    assertEquals("Director", merged.get(1).kind());
    assertEquals("Omar Reed", merged.get(2).name());
  }

  @Test
  public void missingKindIsAnActingRole() {
    assertEquals(TitleCast.identity("Role", "Ada"), TitleCast.identity("", "Ada"));
  }

  @Test
  public void externalPortraitsGoThroughTheServerTranscoder() {
    assertEquals(
        "/photo/:/transcode?width=360&height=540&minSize=1&upscale=0&url="
            + "https%3A%2F%2Fimages.example.com%2Fmain%2F42.jpg",
        TitleCast.transcodePath("https://images.example.com/main/42.jpg", 360));
    assertEquals(
        "/photo/:/transcode?width=360&height=540&minSize=1&upscale=0&url=%2Fart%2Fnadia.jpg",
        TitleCast.transcodePath("/art/nadia.jpg", 360));
  }

  @Test
  public void unsafePhotoAddressesAreRefused() {
    for (String thumb :
        new String[] {
          "http://images.example.com/42.jpg",
          "https://owner:secret@images.example.com/42.jpg",
          "https://images.example.com:8443/42.jpg",
          "//images.example.com/42.jpg",
          "file:///data/42.jpg",
          ""
        }) {
      assertFalse(thumb, TitleCast.isExternalPhoto(thumb));
      try {
        TitleCast.transcodePath(thumb, 360);
        fail("Should refuse " + thumb);
      } catch (IllegalArgumentException expected) {
      }
    }
  }
}
