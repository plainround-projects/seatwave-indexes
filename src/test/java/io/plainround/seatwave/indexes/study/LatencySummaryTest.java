package io.plainround.seatwave.indexes.study;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

class LatencySummaryTest {

  private static final long NANOS_PER_MILLI = 1_000_000;
  private static final int TIMINGS = 100;

  @Test
  void takesTheNearestRankForTheMedianAndTheNinetyNinthPercentile() {
    // 100 timings of 1 ms to 100 ms, given in reverse order so the summary must sort them.
    long[] nanos =
        LongStream.rangeClosed(1, TIMINGS)
            .map(millis -> (TIMINGS + 1 - millis) * NANOS_PER_MILLI)
            .toArray();

    LatencySummary summary = LatencySummary.of(nanos);

    assertThat(summary).isEqualTo(new LatencySummary(TIMINGS, 50.0, 99.0));
  }

  @Test
  void leavesTheCallersTimingsInTheirOrder() {
    long[] nanos = {3 * NANOS_PER_MILLI, NANOS_PER_MILLI, 2 * NANOS_PER_MILLI};

    LatencySummary.of(nanos);

    assertThat(nanos).containsExactly(3 * NANOS_PER_MILLI, NANOS_PER_MILLI, 2 * NANOS_PER_MILLI);
  }

  @Test
  void summarizesOneTimingAsBothPercentiles() {
    LatencySummary summary = LatencySummary.of(new long[] {2_604_000});

    assertThat(summary.describe()).isEqualTo("p50 2.604 ms, p99 2.604 ms (1 runs)");
  }

  @Test
  void refusesToSummarizeNoTimings() {
    assertThatThrownBy(() -> LatencySummary.of(new long[0]))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("no timings to summarize");
  }
}
