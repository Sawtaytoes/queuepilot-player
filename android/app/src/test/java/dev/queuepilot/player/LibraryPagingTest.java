package dev.queuepilot.player;

import static org.junit.Assert.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class LibraryPagingTest {
  private static JSONObject item(String key) throws Exception {
    return new JSONObject().put("key", key);
  }

  @Test
  public void aGridKeepsTheReadItStartedWithAfterItGoesStale() {
    long stale = AllLibrariesSource.FRESH_MS + 1;
    // A grid's later pages keep the read, however old, so offsets and letters stay valid.
    assertTrue(AllLibrariesSource.isUsable("ada|srv", "ada|srv", 0, stale, false));
    // A grid that starts replaces a stale read.
    assertFalse(AllLibrariesSource.isUsable("ada|srv", "ada|srv", 0, stale, true));
    assertTrue(AllLibrariesSource.isUsable("ada|srv", "ada|srv", 0, 1000, true));
  }

  @Test
  public void onlyASessionsFirstReadMayRefresh() {
    AllLibrariesSource source = new AllLibrariesSource(() -> 0);
    long first = source.open();
    // The first page or the letters, whichever comes first, may refresh; the other never may.
    assertTrue(source.takeRefresh(first));
    assertFalse(source.takeRefresh(first));
    // Letter jumps and later pages stay in the session, so a jump to the first page reuses it.
    assertFalse(source.takeRefresh(first));
    long again = source.open();
    // A late request from the screen that was left never refreshes under the new one.
    assertFalse(source.takeRefresh(first));
    assertTrue(source.takeRefresh(again));
  }

  @Test
  public void openingNeverWaitsForARequestInProgress() throws Exception {
    AllLibrariesSource source = new AllLibrariesSource(() -> 0);
    CountDownLatch held = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    // A worker inside a read holds the source's lock for the length of its request.
    Thread worker =
        new Thread(
            () -> {
              synchronized (source) {
                held.countDown();
                try {
                  release.await();
                } catch (InterruptedException ignored) {
                  Thread.currentThread().interrupt();
                }
              }
            });
    worker.start();
    assertTrue(held.await(5, TimeUnit.SECONDS));
    FutureTask<Long> opened = new FutureTask<>(source::open);
    new Thread(opened).start();
    try {
      assertEquals(Long.valueOf(1), opened.get(2, TimeUnit.SECONDS));
    } finally {
      release.countDown();
      worker.join();
    }
  }

  @Test
  public void aRequestForAScreenThatWasLeftStopsBeforeItTouchesTheRead() throws Exception {
    AllLibrariesSource source = new AllLibrariesSource(() -> 0);
    long session = source.open();
    // The address is never reached: the request stops on taking the lock, before any read.
    String nowhere = "http://127.0.0.1:9";
    assertThrows(
        java.util.concurrent.CancellationException.class,
        () -> source.page(nowhere, "", "ada", "queuepilot:all", session, key -> true, () -> false));
    assertThrows(
        java.util.concurrent.CancellationException.class,
        () -> source.letters(nowhere, "", "ada", session, key -> true, () -> false));
    assertThrows(
        java.util.concurrent.CancellationException.class,
        () -> source.search(nowhere, "", "ada", "orbit", key -> true, () -> false));
    // A stopped request does not use up the session's one refresh.
    assertTrue(source.takeRefresh(session));
  }

  @Test
  public void aScreenLeftDuringTheLastResponseNeverKeepsWhatItRead() throws Exception {
    java.util.concurrent.atomic.AtomicBoolean wanted =
        new java.util.concurrent.atomic.AtomicBoolean(true);
    java.util.concurrent.atomic.AtomicInteger reads =
        new java.util.concurrent.atomic.AtomicInteger();
    AllLibrariesSource.Reads server =
        new AllLibrariesSource.Reads() {
          @Override
          public JSONArray sections(String origin, String secret) throws Exception {
            JSONObject films =
                new JSONObject().put("key", "1").put("libraryType", "movie").put("title", "Films");
            return new JSONArray().put(films);
          }

          @Override
          public AllLibrariesSource.Titles titles(
              String origin, String secret, String section, int start) throws Exception {
            // The viewer leaves while the library's last response is on its way.
            if (reads.incrementAndGet() == 1) wanted.set(false);
            JSONArray items =
                new JSONArray()
                    .put(item("/library/metadata/41").put("title", "Orbit").put("type", "movie"));
            return new AllLibrariesSource.Titles(items, 1);
          }
        };
    AllLibrariesSource source = new AllLibrariesSource(() -> 0, server);
    long session = source.open();
    String path = "/library/sections/all?X-Plex-Container-Start=0&X-Plex-Container-Size=50";
    String origin = "http://127.0.0.1:9";
    assertThrows(
        java.util.concurrent.CancellationException.class,
        () -> source.page(origin, "", "ada", path, session, key -> true, wanted::get));
    // Nothing from the abandoned read was kept: the next request reads the library again.
    wanted.set(true);
    JSONObject page = source.page(origin, "", "ada", path, session, key -> true, wanted::get);
    assertEquals(2, reads.get());
    assertEquals(1, page.getInt("total"));
  }

  @Test
  public void anotherProfileOrServerNeverUsesTheRead() {
    assertFalse(AllLibrariesSource.isUsable("grace|srv", "ada|srv", 0, 1000, false));
    assertFalse(AllLibrariesSource.isUsable("ada|other", "ada|srv", 0, 1000, false));
    assertFalse(AllLibrariesSource.isUsable("ada|srv", "", 0, 1000, false));
  }

  @Test
  public void aPageThatStartsWithTheFirstItemAgainIsARepeat() throws Exception {
    JSONObject first = item("/library/metadata/41");
    JSONArray again = new JSONArray().put(item("/library/metadata/41")).put(item("x"));
    JSONArray next = new JSONArray().put(item("/library/metadata/42"));
    assertTrue(LibraryBrowse.isRepeat(first, again));
    assertFalse(LibraryBrowse.isRepeat(first, next));
    assertFalse(LibraryBrowse.isRepeat(first, new JSONArray()));
    assertFalse(LibraryBrowse.isRepeat(null, again));
    assertFalse(LibraryBrowse.isRepeat(new JSONObject(), new JSONArray().put(new JSONObject())));
  }
}
