package dev.queuepilot.player;

import static org.junit.Assert.*;

import org.junit.Test;

public class RewindOnResumeTest {
  @Test
  public void rewindsOnceAfterPause() {
    var r = new RewindOnResume();
    assertEquals(9000, r.resume(9000, 0, 3));
    r.pause();
    assertEquals(6000, r.resume(9000, 0, 3));
    assertEquals(6000, r.resume(6000, 0, 3));
  }

  @Test
  public void neverCrossesStartOrAuthoredSection() {
    var r = new RewindOnResume();
    r.pause();
    assertEquals(0, r.resume(1000, 0, 3));
    r.pause();
    assertEquals(12000, r.resume(13000, 12000, 3));
  }

  @Test
  public void disabledAndSeekedPauseUseChosenPosition() {
    var r = new RewindOnResume();
    r.pause();
    assertEquals(10000, r.resume(10000, 0, 0));
    r.pause();
    assertEquals(27000, r.resume(30000, 0, 3));
  }
}
