package dev.queuepilot.player;

/**
 * The most recent samples of one series, oldest first, for a small live graph. An unknown
 * sample is {@code NaN}: a graph leaves a gap, and the mean and maximum skip it. Adding a sample
 * never allocates.
 */
final class StatsRing {
  private final float[] values;
  private int next, size;

  StatsRing(int capacity) {
    if (capacity <= 0) throw new IllegalArgumentException("capacity");
    values = new float[capacity];
  }

  void add(double value) {
    values[next] = (float) value;
    next = (next + 1) % values.length;
    if (size < values.length) size++;
  }

  int size() {
    return size;
  }

  int capacity() {
    return values.length;
  }

  /** Sample {@code index}, where 0 is the oldest kept and {@code size() - 1} the latest. */
  float get(int index) {
    if (index < 0 || index >= size) throw new IndexOutOfBoundsException("index " + index);
    return values[(next - size + index + values.length) % values.length];
  }

  /** The latest sample, or {@code NaN} when there is none. */
  float latest() {
    return size == 0 ? Float.NaN : get(size - 1);
  }

  /** The sample {@code back} places before the latest, or {@code NaN} when there is none. */
  float ago(int back) {
    return back < 0 || back >= size ? Float.NaN : get(size - 1 - back);
  }

  /** The mean of the known samples among the latest {@code count}, or {@code NaN}. */
  float mean(int count) {
    double sum = 0;
    int known = 0;
    for (int index = Math.max(0, size - count); index < size; index++) {
      float value = get(index);
      if (Float.isNaN(value)) continue;
      sum += value;
      known++;
    }
    return known == 0 ? Float.NaN : (float) (sum / known);
  }

  /** The largest known sample, or {@code NaN}: the top of a graph's scale. */
  float max() {
    float max = Float.NaN;
    for (int index = 0; index < size; index++) {
      float value = get(index);
      if (!Float.isNaN(value) && (Float.isNaN(max) || value > max)) max = value;
    }
    return max;
  }

  void clear() {
    next = 0;
    size = 0;
  }
}
