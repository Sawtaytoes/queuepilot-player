package dev.queuepilot.player;

import static org.junit.Assert.*;

import androidx.media3.common.PlaybackException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

public class ServerConversionTest {
  private static Map<String, String> query(String path) {
    Map<String, String> values = new HashMap<>();
    for (String pair : URI.create("http://server" + path).getRawQuery().split("&")) {
      String[] kv = pair.split("=", 2);
      values.put(
          URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
          URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
    }
    return values;
  }

  private static final ServerConversion.Request REQUEST =
      new ServerConversion.Request("102", "session-1", "client-1", "Living Room TV", 9001, false);

  @Test
  public void startsAnHlsConversionOfTheItem() {
    String path = REQUEST.startPath();
    assertTrue(path.startsWith("/video/:/transcode/universal/start.m3u8?"));
    assertTrue(ServerConversion.isConversion("http://server:32400" + path));
    assertFalse(ServerConversion.isConversion("http://server:32400/library/parts/1/file.mkv"));
    Map<String, String> q = query(path);
    assertEquals("/library/metadata/102", q.get("path"));
    assertEquals("hls", q.get("protocol"));
    assertEquals("0", q.get("directPlay"));
    assertEquals("1", q.get("directStream"));
    assertEquals("session-1", q.get("session"));
    assertEquals("session-1", q.get("X-Plex-Session-Identifier"));
    assertEquals("9001", q.get("audioStreamID"));
    assertEquals("none", q.get("subtitles"));
    assertEquals("client-1", q.get("X-Plex-Client-Identifier"));
    assertEquals("Living Room TV", q.get("X-Plex-Device-Name"));
    assertTrue(q.get("X-Plex-Client-Profile-Extra").contains("container=mpegts&videoCodec=h264"));
    // No offset: the player seeks inside the stream. No token in the address.
    assertFalse(q.containsKey("offset"));
    assertFalse(path.contains("Token"));
  }

  @Test
  public void decisionAndStartAskTheSameQuestion() {
    assertEquals(query(REQUEST.decisionPath()), query(REQUEST.startPath()));
    assertTrue(REQUEST.decisionPath().startsWith("/video/:/transcode/universal/decision?"));
    assertEquals("/video/:/transcode/universal/stop?session=session-1", REQUEST.stopPath());
    assertEquals("/video/:/transcode/universal/ping?session=session-1", REQUEST.pingPath());
  }

  @Test
  public void burnsTheSelectedSubtitleAndLeavesUnknownAudioToPlex() {
    Map<String, String> q =
        query(new ServerConversion.Request("7", "s", "c", "d", -1, true).startPath());
    assertEquals("burn", q.get("subtitles"));
    assertFalse(q.containsKey("audioStreamID"));
  }

  @Test
  public void readsTheDecision() {
    assertTrue(new ServerConversion.Decision(1001, 1001, -1, "").isPlayable());
    assertTrue(new ServerConversion.Decision(1000, -1, -1, "").isPlayable());
    assertFalse(new ServerConversion.Decision(2000, -1, -1, "").isPlayable());
    assertFalse(new ServerConversion.Decision(1001, 4005, -1, "").isPlayable());
    assertFalse(new ServerConversion.Decision(1001, 1001, 2001, "").isPlayable());
    assertFalse(new ServerConversion.Decision(-1, -1, -1, "").isPlayable());
    assertEquals(1001, ServerConversion.Decision.code(" 1001"));
    assertEquals(-1, ServerConversion.Decision.code(""));
  }

  @Test
  public void theSettingDefaultsToAsk() {
    assertEquals("ask", ServerConversion.mode(null));
    assertEquals("ask", ServerConversion.mode("bogus"));
    assertEquals("convert", ServerConversion.mode("convert"));
    assertEquals("Convert on the server", ServerConversion.label("convert"));
    assertEquals("Stop", ServerConversion.label("stop"));
    assertEquals(2, ServerConversion.index("stop"));
  }

  @Test
  public void namesTheReasonForTheViewer() {
    DirectPlayFailure.Reason container =
        DirectPlayFailure.fromError(
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, "asf", "wmv2", "wmav2");
    assertEquals(DirectPlayFailure.Kind.CONTAINER, container.kind());
    assertEquals("This TV can't open WMV files like this one.", container.text());
    DirectPlayFailure.Reason decoder =
        DirectPlayFailure.fromError(
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES, "mkv", "hevc", "dca");
    assertEquals(
        "This TV's decoder couldn't play the HEVC video with DTS audio.", decoder.text());
    assertTrue(decoder.isConvertible());
    DirectPlayFailure.Reason network =
        DirectPlayFailure.fromError(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, "mkv", "h264", "aac");
    assertFalse(network.isConvertible());
  }

  @Test
  public void namesAnUndecodableTrack() {
    assertEquals(
        "The video is MPEG-2, which this TV can't decode.",
        DirectPlayFailure.fromTracks(true, false, "mpeg2video", "ac3").text());
    assertEquals(
        "The audio is Dolby TrueHD, which this TV can't decode.",
        DirectPlayFailure.fromTracks(false, true, "h264", "truehd").text());
    assertNull(DirectPlayFailure.fromTracks(false, false, "h264", "aac"));
    assertEquals("XVID", DirectPlayFailure.name("xvid"));
  }
}
