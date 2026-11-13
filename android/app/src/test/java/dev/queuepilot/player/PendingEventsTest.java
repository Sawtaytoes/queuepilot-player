package dev.queuepilot.player;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class PendingEventsTest {
  /** The encrypted store, as the outbox sees it: one string, and how each save was asked for. */
  private static final class MemoryStorage implements PendingEvents.Storage {
    String json = "[]";
    final List<Boolean> saves = new ArrayList<>();

    @Override
    public String load() {
      return json;
    }

    @Override
    public void save(String json, boolean isDurable) {
      this.json = json;
      saves.add(isDurable);
    }
  }

  private long now = 1_700_000_000_000L;
  private int sequence;
  private final MemoryStorage storage = new MemoryStorage();

  private PendingEvents outbox() {
    return new PendingEvents(storage, () -> now);
  }

  private JSONObject envelope(String session, String proof, int index, String state)
      throws Exception {
    return new JSONObject()
        .put("origin", "https://queuepilot.example")
        .put("sessionId", session)
        .put("sessionKey", "key-" + session)
        .put("profileProof", proof)
        .put(
            "event",
            new JSONObject()
                .put("id", "event-" + session + "-" + ++sequence)
                .put("sequence", sequence)
                .put("index", index)
                .put("state", state)
                .put("positionMs", sequence * 10_000L)
                .put("durationMs", 1_200_000L));
  }

  private static String id(JSONObject record) {
    return record.optJSONObject("event").optString("id");
  }

  private static String state(JSONObject record) {
    return record.optJSONObject("event").optString("state");
  }

  /** Sends everything the way the player does and returns the states in the order sent. */
  private static List<String> drain(PendingEvents outbox) throws Exception {
    List<String> sent = new ArrayList<>();
    for (JSONObject next = outbox.next(); next != null; next = outbox.next()) {
      sent.add(state(next));
      outbox.sent(id(next));
    }
    return sent;
  }

  @Test
  public void aPausedTvKeepsOneRecordNotOnePerTick() throws Exception {
    PendingEvents outbox = outbox();
    outbox.add(envelope("s1", "proof-ada", 0, "playing"), false);
    outbox.add(envelope("s1", "proof-ada", 0, "paused"), false);
    for (int tick = 0; tick < 500; tick++) {
      now += 10_000;
      outbox.add(envelope("s1", "proof-ada", 0, "paused"), false);
    }
    assertEquals(2, outbox.pendingCount());
    JSONObject first = outbox.next();
    assertEquals("playing", state(first));
    outbox.sent(id(first));
    JSONObject latest = outbox.next();
    assertEquals("paused", state(latest));
    // The latest position wins.
    assertEquals(sequence, latest.getJSONObject("event").getInt("sequence"));
  }

  @Test
  public void transitionsAndOtherItemsAreNeverMerged() throws Exception {
    PendingEvents outbox = outbox();
    outbox.add(envelope("s1", "p", 0, "playing"), false);
    outbox.add(envelope("s1", "p", 0, "playing"), false);
    outbox.add(envelope("s1", "p", 0, "paused"), false);
    outbox.add(envelope("s1", "p", 0, "playing"), false);
    outbox.add(envelope("s1", "p", 0, "ended"), true);
    outbox.add(envelope("s1", "p", 0, "ended"), true);
    outbox.add(envelope("s1", "p", 1, "playing"), false);
    assertEquals(
        List.of("playing", "paused", "playing", "ended", "ended", "playing"), drain(outbox));
  }

  @Test
  public void terminalEventsAskForADurableWriteAndTicksDoNot() throws Exception {
    assertTrue(PendingEvents.isProgressTick("playing"));
    assertTrue(PendingEvents.isProgressTick("paused"));
    for (String state : new String[] {"stopped", "ended", "skip", "section-end", "error"})
      assertFalse(state, PendingEvents.isProgressTick(state));
    PendingEvents outbox = outbox();
    outbox.add(envelope("s1", "p", 0, "playing"), false);
    outbox.add(envelope("s1", "p", 0, "stopped"), true);
    assertEquals(List.of(false, true), storage.saves);
  }

  @Test
  public void theQueueIsCappedAndDropsTheOldestFirst() throws Exception {
    PendingEvents outbox = outbox();
    int total = PendingEvents.MAX_PENDING + 25;
    for (int i = 0; i < total; i++)
      outbox.add(envelope("s1", "p", i, i % 2 == 0 ? "skip" : "section-end"), true);
    assertEquals(PendingEvents.MAX_PENDING, outbox.pendingCount());
    // The 25 oldest went; the first left is item 25.
    assertEquals(25, outbox.next().getJSONObject("event").getInt("index"));
  }

  @Test
  public void aRejectedSessionIsKeptOnceForReviewAndNeverSentAgain() throws Exception {
    PendingEvents outbox = outbox();
    outbox.add(envelope("old", "p", 0, "playing"), false);
    outbox.add(envelope("old", "p", 0, "stopped"), true);
    outbox.add(envelope("old", "p", 1, "playing"), false);
    outbox.add(envelope("new", "p", 0, "playing"), false);
    outbox.rejected("old", "This native session has expired");
    JSONArray failed = outbox.failed();
    assertEquals(1, failed.length());
    // The latest event of the session is the one kept.
    assertEquals(1, failed.getJSONObject(0).getJSONObject("event").getInt("index"));
    assertEquals("This native session has expired", failed.getJSONObject(0).getString("failed"));
    assertEquals(List.of("playing"), drain(outbox));
    assertEquals(1, outbox.failed().length());
  }

  @Test
  public void reviewRecordsAreCappedOldestFirst() throws Exception {
    PendingEvents outbox = outbox();
    int sessions = PendingEvents.MAX_FAILED_SESSIONS + 5;
    for (int i = 0; i < sessions; i++) {
      outbox.add(envelope("s" + i, "p", 0, "stopped"), true);
      outbox.rejected("s" + i, "Invalid session authorization");
    }
    JSONArray failed = outbox.failed();
    assertEquals(PendingEvents.MAX_FAILED_SESSIONS, failed.length());
    assertEquals("s5", failed.getJSONObject(0).getString("sessionId"));
    assertEquals("s" + (sessions - 1), failed.getJSONObject(failed.length() - 1).get("sessionId"));
  }

  @Test
  public void onlyRefusalsOfTheSessionAreRejections() {
    for (int status : new int[] {400, 401, 403, 404, 409, 410, 422})
      assertTrue(String.valueOf(status), PendingEvents.isRejection(status));
    for (int status : new int[] {0, 200, 408, 429, 500, 502, 503})
      assertFalse(String.valueOf(status), PendingEvents.isRejection(status));
  }

  @Test
  public void serverErrorsAreRetriedALimitedNumberOfTimes() throws Exception {
    PendingEvents outbox = outbox();
    outbox.add(envelope("s1", "p", 0, "stopped"), true);
    outbox.add(envelope("s1", "p", 1, "playing"), false);
    JSONObject head = outbox.next();
    for (int i = 1; i < PendingEvents.MAX_SERVER_ERRORS; i++)
      assertFalse(outbox.retryLater(head, 503, "Progress could not be saved"));
    assertEquals(id(head), id(outbox.next()));
    assertTrue(outbox.retryLater(head, 503, "Progress could not be saved"));
    // It no longer holds the next event back.
    assertEquals(1, outbox.next().getJSONObject("event").getInt("index"));
    assertEquals(1, outbox.failed().length());
  }

  @Test
  public void anUnreachableServerIsRetriedUntilTheSessionCouldNoLongerAccept() throws Exception {
    PendingEvents outbox = outbox();
    outbox.add(envelope("s1", "p", 0, "stopped"), true);
    JSONObject head = outbox.next();
    for (int i = 0; i < PendingEvents.MAX_SERVER_ERRORS * 5; i++) {
      now += 60_000;
      assertFalse(outbox.retryLater(head, 0, "QueuePilot is unavailable"));
    }
    now += PendingEvents.MAX_AGE_MS;
    assertTrue(outbox.retryLater(head, 0, "QueuePilot is unavailable"));
    assertNull(outbox.next());
    assertTrue(outbox.failed().getJSONObject(0).getString("failed").startsWith("Expired"));
  }

  @Test
  public void aSentEventThatATickReplacedIsNotLostOrDuplicated() throws Exception {
    PendingEvents outbox = outbox();
    outbox.add(envelope("s1", "p", 0, "paused"), false);
    JSONObject inFlight = outbox.next();
    // While it is being sent, the next paused tick replaces it.
    outbox.add(envelope("s1", "p", 0, "paused"), false);
    outbox.sent(id(inFlight));
    JSONObject next = outbox.next();
    assertNotNull(next);
    assertNotEquals(id(inFlight), id(next));
    outbox.sent(id(next));
    assertNull(outbox.next());
    assertFalse(outbox.retryLater(inFlight, 503, "late"));
  }

  @Test
  public void mergingTicksNeverRestartsTheRetryLimits() throws Exception {
    PendingEvents outbox = outbox();
    outbox.add(envelope("s1", "p", 0, "paused"), false);
    long firstQueuedAt = now;
    // A paused TV whose QueuePilot answers 503: each failed send is followed by a new tick that
    // replaces the record, sometimes while it is still being sent.
    boolean isGivenUp = false;
    for (int tick = 0; tick < PendingEvents.MAX_SERVER_ERRORS && !isGivenUp; tick++) {
      JSONObject inFlight = outbox.next();
      now += 10_000;
      if (tick % 2 == 0) outbox.add(envelope("s1", "p", 0, "paused"), false);
      isGivenUp = outbox.retryLater(inFlight, 503, "Progress could not be saved");
      if (tick % 2 == 1) outbox.add(envelope("s1", "p", 0, "paused"), false);
    }
    assertTrue("server errors must accumulate across merged ticks", isGivenUp);
    assertEquals(firstQueuedAt, outbox.failed().getJSONObject(0).getLong("queuedAt"));

    // The age limit holds across merges and a restart too.
    JSONObject leftover = outbox.next();
    if (leftover != null) outbox.sent(id(leftover));
    outbox.add(envelope("s2", "p", 0, "paused"), false);
    for (int hour = 0; hour < 25; hour++) {
      now += 60 * 60 * 1000;
      outbox().add(envelope("s2", "p", 0, "paused"), false);
    }
    PendingEvents reloaded = outbox();
    JSONObject waiting = reloaded.next();
    assertTrue(reloaded.retryLater(waiting, 0, "QueuePilot is unavailable"));
    assertNull(reloaded.next());
  }

  @Test
  public void aFailureOfAReplacedTickCountsAgainstItsOwnReplacementOnly() throws Exception {
    PendingEvents outbox = outbox();
    outbox.add(envelope("s1", "p", 0, "paused"), false);
    JSONObject a = outbox.next();
    for (int i = 1; i < PendingEvents.MAX_SERVER_ERRORS; i++)
      assertFalse(outbox.retryLater(a, 503, "Progress could not be saved"));
    a = outbox.next();
    // While A is being sent: B replaces it, then a transition C and a new paused tick D.
    outbox.add(envelope("s1", "p", 0, "paused"), false);
    outbox.add(envelope("s1", "p", 0, "playing"), false);
    outbox.add(envelope("s1", "p", 0, "paused"), false);
    // A's 20th server error belongs to B, which inherited A's 19.
    assertTrue(outbox.retryLater(a, 503, "Progress could not be saved"));
    assertEquals(1, outbox.failed().length());
    assertEquals(2, outbox.pendingCount());
    JSONObject c = outbox.next();
    assertEquals("playing", state(c));
    outbox.sent(id(c));
    assertEquals(0, outbox.next().getInt("serverErrors"));

    // When the last addition is a transition, the failure is still not lost.
    PendingEvents other = new PendingEvents(new MemoryStorage(), () -> now);
    other.add(envelope("s2", "p", 0, "paused"), false);
    JSONObject inFlight = other.next();
    other.add(envelope("s2", "p", 0, "paused"), false);
    other.add(envelope("s2", "p", 0, "ended"), true);
    assertFalse(other.retryLater(inFlight, 503, "Progress could not be saved"));
    assertEquals(1, other.next().getInt("serverErrors"));
  }

  @Test
  public void oneSenderAtATimeSoAReplacedRecordIsNeverOvertaken() throws Exception {
    PendingEvents outbox = outbox();
    outbox.add(envelope("s1", "p", 0, "paused"), false);
    java.util.concurrent.CountDownLatch firstStarted = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch releaseFirst = new java.util.concurrent.CountDownLatch(1);
    List<Long> serverOrder = java.util.Collections.synchronizedList(new ArrayList<>());
    PendingEvents.Transport server =
        record -> {
          long sequence = record.getJSONObject("event").getLong("sequence");
          if (serverOrder.isEmpty()) {
            firstStarted.countDown();
            assertTrue(releaseFirst.await(5, java.util.concurrent.TimeUnit.SECONDS));
          }
          // QueuePilot refuses an event older than one it accepted (409).
          for (long accepted : serverOrder)
            if (sequence <= accepted) throw new IllegalStateException("409");
          serverOrder.add(sequence);
        };
    java.util.function.ToIntFunction<Exception> statusOf =
        error -> "409".equals(error.getMessage()) ? 409 : 0;
    // The sender of an activity being destroyed takes the paused report...
    Thread oldSender =
        new Thread(
            () -> {
              try {
                outbox.drain(server, statusOf);
              } catch (Exception error) {
                throw new AssertionError(error);
              }
            });
    oldSender.start();
    assertTrue(firstStarted.await(5, java.util.concurrent.TimeUnit.SECONDS));
    // ...a later tick replaces it, then also ends the item, and the new activity's sender starts.
    outbox.add(envelope("s1", "p", 0, "paused"), false);
    outbox.add(envelope("s1", "p", 0, "ended"), true);
    final PendingEvents.Drained[] newDrain = new PendingEvents.Drained[1];
    Thread newSender =
        new Thread(
            () -> {
              try {
                newDrain[0] = outbox.drain(server, statusOf);
              } catch (Exception error) {
                throw new AssertionError(error);
              }
            });
    newSender.start();
    Thread.sleep(200);
    assertEquals("the second sender waits for the first", 0, serverOrder.size());
    releaseFirst.countDown();
    oldSender.join(5000);
    newSender.join(5000);
    assertEquals(3, serverOrder.size());
    List<Long> sorted = new ArrayList<>(serverOrder);
    java.util.Collections.sort(sorted);
    assertEquals(sorted, new ArrayList<>(serverOrder));
    assertFalse(newDrain[0].isRejected);
    assertEquals(0, outbox.failed().length());
    assertNull(outbox.next());
  }

  @Test
  public void theOutboxSurvivesARestartAndResumesInOrder() throws Exception {
    PendingEvents before = outbox();
    before.add(envelope("s1", "p", 0, "playing"), false);
    before.add(envelope("s1", "p", 0, "ended"), true);
    before.add(envelope("s1", "p", 1, "paused"), false);
    JSONObject head = before.next();
    before.retryLater(head, 500, "Progress could not be saved");

    PendingEvents after = outbox();
    assertEquals(3, after.pendingCount());
    assertEquals(id(head), id(after.next()));
    assertEquals(1, after.next().getInt("serverErrors"));
    // A tick after the restart still merges with the paused event saved before it.
    after.add(envelope("s1", "p", 1, "paused"), false);
    assertEquals(List.of("playing", "ended", "paused"), drain(after));
    assertEquals(0, outbox().pendingCount());
  }

  @Test
  public void anOutboxThatGrewWithoutBoundIsCompactedOnLoad() throws Exception {
    JSONArray legacy = new JSONArray();
    legacy.put(new JSONObject().put("broken", true));
    for (int i = 0; i < 3000; i++) legacy.put(envelope("s1", "p", 0, "paused"));
    for (int i = 0; i < 40; i++) legacy.put(envelope("dead", "p", 0, "paused").put("failed", "x"));
    legacy.put(envelope("s1", "p", 0, "stopped"));
    storage.json = legacy.toString();

    PendingEvents outbox = outbox();
    assertEquals(2, outbox.pendingCount());
    assertEquals(1, outbox.failed().length());
    JSONArray saved = new JSONArray(storage.json);
    assertEquals(3, saved.length());
    assertEquals(now, saved.getJSONObject(0).getLong("queuedAt"));
    assertEquals(List.of("paused", "stopped"), drain(outbox));
  }

  @Test
  public void eachProfilesEventsKeepTheirOwnCredentialsAndNeverMerge() throws Exception {
    PendingEvents outbox = outbox();
    outbox.add(envelope("ada-session", "proof-ada", 0, "paused"), false);
    outbox.add(envelope("grace-session", "proof-grace", 0, "paused"), false);
    outbox.add(envelope("ada-session", "proof-ada", 0, "paused"), false);
    assertEquals(3, outbox.pendingCount());

    JSONObject ada = outbox.next();
    assertEquals("proof-ada", ada.getString("profileProof"));
    assertEquals("key-ada-session", ada.getString("sessionKey"));
    // Rejecting one profile's session leaves the other's waiting, with its own proof.
    outbox.rejected("ada-session", "Invalid session authorization");
    JSONObject grace = outbox.next();
    assertEquals("grace-session", grace.getString("sessionId"));
    assertEquals("proof-grace", grace.getString("profileProof"));
    assertEquals("key-grace-session", grace.getString("sessionKey"));
    outbox.sent(id(grace));
    assertNull(outbox.next());
    assertEquals("ada-session", outbox.failed().getJSONObject(0).getString("sessionId"));
  }
}
