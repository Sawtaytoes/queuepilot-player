package dev.queuepilot.player;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.StrictMode;

/**
 * StrictMode for performance checks: it logs disk and network work on the main thread, and
 * leaked closeables, registrations and activities, under the {@code StrictMode} log tag.
 *
 * <p>It is on only for a debuggable build on the Android emulator that was launched with the
 * {@code strict-mode} extra ({@code tools/perf_probe.py} does this). The preview APKs that TVs
 * run are debuggable too, so being debuggable alone is not enough: a TV never turns it on.
 */
final class DebugChecks {
  private DebugChecks() {}

  static void install(Activity activity) {
    boolean isDebuggable =
        (activity.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    boolean isEmulator = Build.HARDWARE.equals("ranchu") || Build.HARDWARE.equals("goldfish");
    if (!isDebuggable
        || !isEmulator
        || !activity.getIntent().getBooleanExtra("strict-mode", false)) return;
    StrictMode.setThreadPolicy(
        new StrictMode.ThreadPolicy.Builder()
            .detectDiskReads()
            .detectDiskWrites()
            .detectNetwork()
            .detectCustomSlowCalls()
            .detectResourceMismatches()
            .detectUnbufferedIo()
            .penaltyLog()
            .build());
    StrictMode.setVmPolicy(
        new StrictMode.VmPolicy.Builder()
            .detectActivityLeaks()
            .detectLeakedClosableObjects()
            .detectLeakedRegistrationObjects()
            .detectLeakedSqlLiteObjects()
            .penaltyLog()
            .build());
  }
}
