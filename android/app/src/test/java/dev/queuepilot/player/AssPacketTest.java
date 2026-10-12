package dev.queuepilot.player;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class AssPacketTest {
  @Test
  public void preservesOverridesAndCommasAndUsesPacketDuration() {
    String body = "42,1,Default,,0,0,0,,{\\pos(30,40)\\bord4}Hello, world";
    AssPacket packet =
        AssPacket.decode(
            ("Dialogue: 0:00:00:00,0:01:02:34," + body).getBytes(StandardCharsets.UTF_8));
    assertEquals(62340, packet.durationMs);
    assertEquals(body, new String(packet.data, StandardCharsets.UTF_8));
  }

  @Test(expected = IllegalArgumentException.class)
  public void rejectsMalformedTiming() {
    AssPacket.decode("Dialogue: 0:00:00:00,bad,packet".getBytes(StandardCharsets.UTF_8));
  }

  @Test
  public void acceptsLargeDrawingWithoutTruncating() {
    String drawing = "{\\p1}" + "m 0 0 l 10 10 ".repeat(100000);
    String packet = "2,0,Sign,,0,0,0,," + drawing;
    assertEquals(
        packet,
        new String(
            AssPacket.decode(
                    ("Dialogue: 0:00:00:00,0:00:03:00," + packet).getBytes(StandardCharsets.UTF_8))
                .data,
            StandardCharsets.UTF_8));
  }
}
