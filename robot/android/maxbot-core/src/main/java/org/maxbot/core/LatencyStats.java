package org.maxbot.core;

import java.util.Arrays;

/** Bounded samples; snapshots are calculated off the capture/inference hot path. */
public final class LatencyStats {
  private final double[] values = new double[512];
  private int size, index;

  public synchronized void add(double millis) {
    if (Double.isFinite(millis) && millis >= 0) {
      values[index] = millis;
      index = (index + 1) % values.length;
      size = Math.min(values.length, size + 1);
    }
  }

  public synchronized int count(){return size;}

  public synchronized void clear() {size=index=0;}

  public synchronized double[] snapshot() {
    if (size == 0) return new double[] {Double.NaN, Double.NaN, Double.NaN};
    double[] sorted = Arrays.copyOf(values, size);
    Arrays.sort(sorted);
    return new double[] {
      sorted[(size - 1) / 2],
      sorted[(int) Math.ceil(size * .95) - 1],
      sorted[(int) Math.ceil(size * .99) - 1]
    };
  }
}
