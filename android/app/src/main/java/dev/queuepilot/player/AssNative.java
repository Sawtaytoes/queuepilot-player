package dev.queuepilot.player;

import android.graphics.Bitmap;

final class AssNative {
  static { System.loadLibrary("queuepilot_ass"); }
  static native long create();
  static native void destroy(long handle);
  static native void font(long handle, String name, byte[] bytes);
  static native void header(long handle, byte[] bytes);
  static native void packet(long handle, byte[] bytes, long startMs, long durationMs);
  static native Bitmap render(long handle, long timeMs, int width, int height, int[] rectangle);
}
