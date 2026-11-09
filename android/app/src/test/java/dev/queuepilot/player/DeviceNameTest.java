package dev.queuepilot.player;

import static org.junit.Assert.*;

import org.junit.Test;

public class DeviceNameTest {
  @Test
  public void theChosenNameWins() {
    assertEquals("Den Television", DeviceName.choose("Den Television", "Model 9"));
  }

  @Test
  public void aMissingNameFallsBackToTheModelThenTheProduct() {
    assertEquals("Model 9", DeviceName.choose(null, "Model 9"));
    assertEquals("Model 9", DeviceName.choose("   ", "Model 9"));
    assertEquals("QueuePilot TV", DeviceName.choose("", null));
  }

  @Test
  public void headerBreakingCharactersAreRemoved() {
    assertEquals("Den Television", DeviceName.choose(" Den\r\nTelevision\t", null));
  }

  @Test
  public void longNamesAreBounded() {
    assertEquals(64, DeviceName.choose("x".repeat(100), null).length());
  }

  @Test
  public void boundingNeverSplitsACharacter() {
    String name = "x".repeat(63) + "\uD83D\uDCFA\uD83D\uDCFA";
    assertEquals("x".repeat(63) + "\uD83D\uDCFA", DeviceName.choose(name, null));
  }
}
