package dev.queuepilot.player;

import java.util.HashSet;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.ToIntFunction;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The outbox of native playback events that QueuePilot has not accepted yet, oldest first.
 *
 * <p>Each record is the envelope the player sends: {@code origin}, {@code sessionId}, {@code
 * sessionKey}, {@code profileProof}, and the {@code event} itself. A record always travels with its
 * own session's credentials, so one profile's events are never sent with another's. The outbox adds
 * {@code queuedAt}, {@code serverErrors}, and, once QueuePilot will not take it, {@code failed} and
 * {@code failedAt}.
 *
 * <p>The outbox is bounded:
 *
 * <ul>
 *   <li>A progress tick ({@code playing} or {@code paused}) replaces the last waiting record when
 *       that record is the same state of the same item in the same session. A paused TV reports
 *       every 10 seconds; the outbox keeps only the latest position. The replacement keeps the
 *       replaced record's age and server errors, so merging never restarts the retry limits.
 *   <li>At most {@link #MAX_PENDING} records wait to be sent. The oldest is dropped first.
 *   <li>A rejected session (a 4xx answer other than 408 and 429) is kept for review as its latest
 *       event only, for at most {@link #MAX_FAILED_SESSIONS} sessions, oldest dropped first. It is
 *       never sent again and never sent with other credentials.
 *   <li>A record that QueuePilot answers with a server error {@link #MAX_SERVER_ERRORS} times, or
 *       that still fails after {@link #MAX_AGE_MS} (a session's lifetime), is kept for review the
 *       same way. A record that cannot reach QueuePilot at all waits until then.
 * </ul>
 *
 * <p>Every method is synchronized and may read or write the encrypted store, so call them off the
 * main thread. {@link #drain} sends one record at a time for the whole process: a second sender
 * waits, so a record is never overtaken by the later one that replaced it.
 */
final class PendingEvents {
  /** The encrypted backing store: the whole outbox as one JSON array. */
  interface Storage {
    String load() throws Exception;

    /**
     * Saves the outbox. {@code isDurable} asks for the write to reach the disk before this returns;
     * otherwise it may finish in the background.
     */
    void save(String json, boolean isDurable) throws Exception;
  }

  /** The most records that wait to be sent. About 400 bytes each, so about 80 KB in all. */
  static final int MAX_PENDING = 200;

  /** The most rejected sessions kept for review, one record each. */
  static final int MAX_FAILED_SESSIONS = 20;

  /** Server errors (5xx, 408, 429) for one record before it stops being retried. */
  static final int MAX_SERVER_ERRORS = 20;

  /** QueuePilot's native session lifetime; an older event can no longer be accepted. */
  static final long MAX_AGE_MS = 24L * 60 * 60 * 1000;

  /** Sends one outbox record; throws on failure. */
  interface Transport {
    void send(JSONObject record) throws Exception;
  }

  /** What one {@link #drain} did. */
  static final class Drained {
    /** QueuePilot refused at least one session, whose events are now kept for review. */
    boolean isRejected;

    /** Records still wait: QueuePilot could not take the oldest one now. */
    boolean isWaiting;
  }

  private final Storage storage;
  private final LongSupplier clock;
  private final Object sendLock = new Object();
  private JSONArray records;

  PendingEvents(Storage storage, LongSupplier clock) {
    this.storage = storage;
    this.clock = clock;
  }

  /** The periodic states that a later report of the same state supersedes. */
  static boolean isProgressTick(String state) {
    return "playing".equals(state) || "paused".equals(state);
  }

  /** QueuePilot refused the session itself: it will never accept this event, however often. */
  static boolean isRejection(int status) {
    return status >= 400 && status < 500 && status != 408 && status != 429;
  }

  /** Queues an envelope after the events before it. */
  synchronized void add(JSONObject envelope, boolean isDurable) throws Exception {
    load();
    JSONObject record = new JSONObject(envelope.toString());
    record.put("queuedAt", clock.getAsLong());
    record.put("serverErrors", 0);
    record.put("lineage", lineage(record));
    int last = lastPending();
    if (last >= 0 && isSameTick(records.getJSONObject(last), record)) {
      inherit(records.getJSONObject(last), record);
      records.remove(last);
    }
    records.put(record);
    trimPending();
    save(isDurable);
  }

  /** A copy of the oldest record still to send, or null. */
  synchronized JSONObject next() throws Exception {
    load();
    for (int i = 0; i < records.length(); i++) {
      JSONObject record = records.getJSONObject(i);
      if (!record.has("failed")) return new JSONObject(record.toString());
    }
    return null;
  }

  /** QueuePilot accepted the event; it leaves the outbox. */
  synchronized void sent(String eventId) throws Exception {
    load();
    int index = indexOf(eventId);
    if (index < 0) return; // A later tick already replaced it.
    records.remove(index);
    // A lost write only sends the event again, and QueuePilot ignores a repeated event ID.
    save(false);
  }

  /** QueuePilot refused the session: every waiting event of it is kept for review only. */
  synchronized void rejected(String sessionId, String reason) throws Exception {
    load();
    long now = clock.getAsLong();
    for (int i = 0; i < records.length(); i++) {
      JSONObject record = records.getJSONObject(i);
      if (!record.has("failed") && record.optString("sessionId").equals(sessionId))
        record.put("failed", reason).put("failedAt", now);
    }
    trimFailed();
    save(false);
  }

  /**
   * The record could not be delivered now: {@code status} is the HTTP status of a server error, or
   * 0 when QueuePilot could not be reached. When a later tick replaced the record while it was
   * being sent, the failure counts against that tick. Returns true when it stops being retried.
   */
  synchronized boolean retryLater(JSONObject sentRecord, int status, String reason)
      throws Exception {
    load();
    JSONObject sentEvent = sentRecord.optJSONObject("event");
    int index = indexOf(sentEvent == null ? "" : sentEvent.optString("id"));
    // Replaced while it was being sent: the tick that inherited its history takes the failure.
    for (int i = 0; index < 0 && i < records.length(); i++) {
      JSONObject candidate = records.getJSONObject(i);
      if (!candidate.has("failed") && lineage(candidate).equals(lineage(sentRecord))) index = i;
    }
    if (index < 0) return false;
    JSONObject record = records.getJSONObject(index);
    if (record.has("failed")) return true;
    long now = clock.getAsLong();
    int serverErrors = record.optInt("serverErrors") + (status > 0 ? 1 : 0);
    record.put("serverErrors", serverErrors);
    boolean isExpired = now - record.optLong("queuedAt", now) > MAX_AGE_MS;
    boolean isGivenUp = serverErrors >= MAX_SERVER_ERRORS || isExpired;
    if (isGivenUp) {
      record
          .put("failed", isExpired ? "Expired before QueuePilot accepted it: " + reason : reason)
          .put("failedAt", now);
      trimFailed();
    }
    save(false);
    return isGivenUp;
  }

  /**
   * Sends the waiting records in order until the outbox is empty or QueuePilot cannot take the
   * oldest one now. One drain runs at a time in the process; a second waits for the first. {@code
   * statusOf} gives a failure's HTTP status, or 0 when there was no answer.
   */
  Drained drain(Transport transport, ToIntFunction<Exception> statusOf) throws Exception {
    Drained drained = new Drained();
    synchronized (sendLock) {
      // Bounded, so a fault in the rules can never spin forever.
      for (int attempt = 0; attempt < 4000; attempt++) {
        JSONObject record = next();
        if (record == null) return drained;
        JSONObject event = record.optJSONObject("event");
        try {
          transport.send(record);
          sent(event == null ? "" : event.optString("id"));
        } catch (Exception error) {
          int status = statusOf.applyAsInt(error);
          String reason = String.valueOf(error.getMessage());
          if (isRejection(status)) {
            // The session is no longer valid. Its events are kept for review and never sent
            // with other credentials.
            rejected(record.optString("sessionId"), reason);
            drained.isRejected = true;
            continue;
          }
          // A record that is no longer retried does not hold the others back.
          if (retryLater(record, status, reason)) continue;
          drained.isWaiting = true;
          return drained;
        }
      }
      drained.isWaiting = next() != null;
      return drained;
    }
  }

  synchronized int pendingCount() throws Exception {
    load();
    return pendingCountUnlocked();
  }

  /** A copy of the records kept for review, oldest first. */
  synchronized JSONArray failed() throws Exception {
    load();
    JSONArray failed = new JSONArray();
    for (int i = 0; i < records.length(); i++) {
      JSONObject record = records.getJSONObject(i);
      if (record.has("failed")) failed.put(new JSONObject(record.toString()));
    }
    return failed;
  }

  /**
   * Reads the outbox once. An outbox saved by an earlier version is compacted with the same rules,
   * so a queue that grew without bound shrinks on the first read.
   */
  private void load() throws Exception {
    if (records != null) return;
    JSONArray stored = new JSONArray(storage.load());
    long now = clock.getAsLong();
    JSONArray loaded = new JSONArray();
    int lastPending = -1;
    boolean isChanged = false;
    for (int i = 0; i < stored.length(); i++) {
      JSONObject record = stored.optJSONObject(i);
      JSONObject event = record == null ? null : record.optJSONObject("event");
      if (event == null || event.optString("id").isEmpty()) {
        isChanged = true;
        continue;
      }
      if (!record.has("queuedAt")) {
        record.put("queuedAt", now);
        isChanged = true;
      }
      if (!record.has("failed")) {
        if (lastPending >= 0 && isSameTick(loaded.getJSONObject(lastPending), record)) {
          inherit(loaded.getJSONObject(lastPending), record);
          loaded.remove(lastPending);
          isChanged = true;
        }
        lastPending = loaded.length();
      }
      loaded.put(record);
    }
    records = loaded;
    isChanged |= trimPending();
    isChanged |= trimFailed();
    if (isChanged) save(false);
  }

  private void save(boolean isDurable) throws Exception {
    storage.save(records.toString(), isDurable);
  }

  /**
   * The first event ID of a chain of merged ticks. A tick that replaces another keeps it, so a
   * failure of the replaced tick, still being sent, is found again.
   */
  private static String lineage(JSONObject record) {
    String lineage = record.optString("lineage");
    if (!lineage.isEmpty()) return lineage;
    JSONObject event = record.optJSONObject("event");
    return event == null ? "" : event.optString("id");
  }

  /** The merged tick is as old, has failed as often, and descends from the one it replaces. */
  private static void inherit(JSONObject replaced, JSONObject record) throws Exception {
    record.put("queuedAt", replaced.optLong("queuedAt", record.optLong("queuedAt")));
    record.put("serverErrors", replaced.optInt("serverErrors"));
    record.put("lineage", lineage(replaced));
  }

  private static boolean isSameTick(JSONObject earlier, JSONObject later) {
    JSONObject a = earlier.optJSONObject("event"), b = later.optJSONObject("event");
    if (a == null || b == null || earlier.has("failed") || later.has("failed")) return false;
    String state = b.optString("state");
    return isProgressTick(state)
        && state.equals(a.optString("state"))
        && a.optInt("index", -1) == b.optInt("index", -2)
        && earlier.optString("sessionId").equals(later.optString("sessionId"))
        && !later.optString("sessionId").isEmpty();
  }

  private int lastPending() throws Exception {
    for (int i = records.length() - 1; i >= 0; i--)
      if (!records.getJSONObject(i).has("failed")) return i;
    return -1;
  }

  private int indexOf(String eventId) throws Exception {
    for (int i = 0; i < records.length(); i++) {
      JSONObject event = records.getJSONObject(i).optJSONObject("event");
      if (event != null && event.optString("id").equals(eventId)) return i;
    }
    return -1;
  }

  /** Drops the oldest waiting records beyond {@link #MAX_PENDING}. */
  private boolean trimPending() throws Exception {
    int excess = pendingCountUnlocked() - MAX_PENDING;
    if (excess <= 0) return false;
    JSONArray kept = new JSONArray();
    for (int i = 0; i < records.length(); i++) {
      JSONObject record = records.getJSONObject(i);
      if (!record.has("failed") && excess > 0) excess--;
      else kept.put(record);
    }
    records = kept;
    return true;
  }

  /**
   * Keeps one record per failed session, its latest, and at most {@link #MAX_FAILED_SESSIONS} of
   * them, dropping the oldest first.
   */
  private boolean trimFailed() throws Exception {
    Set<String> sessions = new HashSet<>();
    boolean[] isDropped = new boolean[records.length()];
    boolean isChanged = false;
    for (int i = records.length() - 1; i >= 0; i--) {
      JSONObject record = records.getJSONObject(i);
      if (!record.has("failed")) continue;
      String sessionId = record.optString("sessionId");
      if (sessions.contains(sessionId) || sessions.size() >= MAX_FAILED_SESSIONS) {
        isDropped[i] = true;
        isChanged = true;
      } else sessions.add(sessionId);
    }
    if (!isChanged) return false;
    JSONArray kept = new JSONArray();
    for (int i = 0; i < records.length(); i++) if (!isDropped[i]) kept.put(records.get(i));
    records = kept;
    return true;
  }

  private int pendingCountUnlocked() throws Exception {
    int count = 0;
    for (int i = 0; i < records.length(); i++) if (!records.getJSONObject(i).has("failed")) count++;
    return count;
  }
}
