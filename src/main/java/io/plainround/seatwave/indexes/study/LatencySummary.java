package io.plainround.seatwave.indexes.study;

import java.util.Arrays;
import java.util.Locale;

/**
 * The median and the 99th percentile of a set of timings, by the nearest-rank rule: the p-th
 * percentile is the smallest timing that at least p percent of the timings do not exceed.
 *
 * @param samples how many timings the summary covers
 * @param p50Millis the median
 * @param p99Millis the 99th percentile
 */
public record LatencySummary(int samples, double p50Millis, double p99Millis) {

  private static final double NANOS_PER_MILLI = 1_000_000.0;
  private static final double MEDIAN = 0.50;
  private static final double P99 = 0.99;

  /**
   * Summarizes timings in nanoseconds.
   *
   * @throws IllegalArgumentException if there are no timings: a percentile of nothing is undefined
   */
  public static LatencySummary of(long[] nanos) {
    if (nanos.length == 0) {
      throw new IllegalArgumentException("no timings to summarize");
    }
    long[] sorted = nanos.clone();
    Arrays.sort(sorted);
    return new LatencySummary(sorted.length, rank(sorted, MEDIAN), rank(sorted, P99));
  }

  /** One line for the report, for example {@code p50 2.604 ms, p99 9.100 ms (200 runs)}. */
  public String describe() {
    return String.format(
        Locale.ROOT, "p50 %.3f ms, p99 %.3f ms (%d runs)", p50Millis, p99Millis, samples);
  }

  private static double rank(long[] sorted, double fraction) {
    int index = (int) Math.ceil(fraction * sorted.length) - 1;
    return sorted[Math.max(0, index)] / NANOS_PER_MILLI;
  }
}
