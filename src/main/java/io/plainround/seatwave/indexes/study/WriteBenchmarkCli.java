package io.plainround.seatwave.indexes.study;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.plainround.seatwave.indexes.catalog.WriteStep;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Measures single-row writes per second as indexes are added. For each {@link WriteStep} it builds
 * {@code bench_<table>} as a copy of the study table (columns and checks, no indexes), adds the
 * step's indexes, prefills it, then lets the configured writers insert one row per transaction for
 * the configured time, and for the hold steps confirm those holds the same way. It writes one line
 * per step into {@code build/study/writes.txt} and drops the copy.
 *
 * <p>Usage: {@code WriteBenchmarkCli [STEP ...]}; no argument runs every step. It writes only to
 * its own {@code bench_} tables in the study schema.
 */
public final class WriteBenchmarkCli {

  private static final Path SETTINGS = Path.of("config/study.properties");
  private static final Path REPORT = Path.of("build/study/writes.txt");
  private static final String SCHEMA = "seatwave_study";
  // Timed rows start here; the prefill uses 1 to prefillRows (WriteStep's HOLD_INSERT relies on
  // it).
  static final long FIRST_TIMED_ROW = 1_000_000_000L;
  // Bounds memory: each writer keeps at most this many timings per phase.
  private static final int MOST_TIMINGS_PER_WRITER = 2_000_000;
  private static final double NANOS_PER_SECOND = 1_000_000_000.0;
  private static final double BYTES_PER_MB = 1_048_576.0;
  // How long to wait for the statistics views to count every timed update, and how often to look.
  private static final Duration STATISTICS_PATIENCE = Duration.ofSeconds(20);
  private static final Duration STATISTICS_POLL = Duration.ofMillis(250);
  static final String ONE_ROW = "(SELECT ?::bigint AS n) AS source";
  static final String SERIES = "generate_series(1, ?) AS source(n)";

  private WriteBenchmarkCli() {}

  /** One timed phase: how many writes completed, per second, and their latency. */
  record Phase(long writes, double perSecond, LatencySummary latency) {}

  /** Runs the chosen steps and writes the report. */
  public static void main(String[] arguments) throws SQLException, InterruptedException {
    StudySettings settings = StudySettings.load(SETTINGS);
    List<WriteStep> steps =
        arguments.length == 0
            ? List.of(WriteStep.values())
            : Arrays.stream(arguments).map(WriteStep::valueOf).toList();
    List<String> report = new ArrayList<>();
    report.add(
        "step             indexes  inserts/s   insert latency                        updates/s   update latency                        index MB  HOT updates");
    try (HikariDataSource pool = pool(settings)) {
      for (WriteStep step : steps) {
        report.add(measure(pool, step, settings));
        System.out.println(report.getLast());
      }
    }
    try {
      Files.createDirectories(REPORT.getParent());
      Files.write(REPORT, report, StandardCharsets.UTF_8);
    } catch (IOException writeFailure) {
      throw new UncheckedIOException("cannot write " + REPORT.toAbsolutePath(), writeFailure);
    }
    System.out.println("written " + REPORT);
  }

  private static HikariDataSource pool(StudySettings settings) {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(settings.jdbcUrl());
    config.setUsername(settings.user());
    config.setPassword(settings.password());
    // One connection per writer and one for the set-up statements: no writer ever waits for a
    // connection, so the timings measure the database, not the pool.
    config.setMaximumPoolSize(settings.writerThreads() + 1);
    config.setConnectionInitSql("SET search_path TO " + SCHEMA);
    // Autocommit: every write is its own transaction, as SeatWave's holds are.
    config.setAutoCommit(true);
    return new HikariDataSource(config);
  }

  private static String measure(HikariDataSource pool, WriteStep step, StudySettings settings)
      throws SQLException, InterruptedException {
    String table = "bench_" + step.table();
    try (Connection setup = pool.getConnection();
        Statement statement = setup.createStatement()) {
      // The table names come from WriteStep's fixed list, never from input.
      statement.execute("DROP TABLE IF EXISTS " + table);
      statement.execute(
          "CREATE TABLE "
              + table
              + " (LIKE "
              + SCHEMA
              + "."
              + step.table()
              + " INCLUDING DEFAULTS INCLUDING CONSTRAINTS)");
      for (String ddl : step.indexDdl()) {
        statement.execute(String.format(Locale.ROOT, ddl, table));
      }
      try (PreparedStatement prefill =
          setup.prepareStatement(String.format(Locale.ROOT, step.insertSql(), table, SERIES))) {
        prefill.setLong(1, settings.prefillRows());
        prefill.execute();
      }
      statement.execute("VACUUM (ANALYZE) " + table);
    }
    String insertSql = String.format(Locale.ROOT, step.insertSql(), table, ONE_ROW);
    AtomicLong nextInsert = new AtomicLong(FIRST_TIMED_ROW);
    Phase inserts = timePhase(pool, insertSql, nextInsert, Long.MAX_VALUE, settings);
    String updates = "-";
    String hotUpdates = "-";
    if (step.updateSql().isPresent()) {
      String updateSql = String.format(Locale.ROOT, step.updateSql().get(), table);
      Phase confirms =
          timePhase(pool, updateSql, new AtomicLong(FIRST_TIMED_ROW), nextInsert.get(), settings);
      updates =
          String.format(
              Locale.ROOT, "%,9.0f   %s", confirms.perSecond(), confirms.latency().describe());
      hotUpdates = hotUpdates(pool, table, confirms.writes());
    }
    double indexMb = indexBytes(pool, table) / BYTES_PER_MB;
    try (Connection cleanup = pool.getConnection();
        Statement statement = cleanup.createStatement()) {
      statement.execute("DROP TABLE " + table);
    }
    return String.format(
        Locale.ROOT,
        "%-16s %7d %,10.0f   %-37s %-49s %,8.1f  %s",
        step.name(),
        step.indexDdl().size(),
        inserts.perSecond(),
        inserts.latency().describe(),
        updates,
        indexMb,
        hotUpdates);
  }

  private static Phase timePhase(
      HikariDataSource pool, String sql, AtomicLong nextRow, long endRow, StudySettings settings)
      throws InterruptedException, SQLException {
    long deadline = System.nanoTime() + (long) (settings.benchmarkSeconds() * NANOS_PER_SECOND);
    long started = System.nanoTime();
    List<Future<long[]>> writers = new ArrayList<>();
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int writer = 0; writer < settings.writerThreads(); writer++) {
        writers.add(executor.submit(() -> write(pool, sql, nextRow, endRow, deadline)));
      }
    }
    double elapsedSeconds = (System.nanoTime() - started) / NANOS_PER_SECOND;
    List<long[]> timings = new ArrayList<>();
    for (Future<long[]> writer : writers) {
      try {
        timings.add(writer.get());
      } catch (ExecutionException writerFailed) {
        throw new SQLException(
            "a benchmark writer failed: " + writerFailed.getCause().getMessage(),
            writerFailed.getCause());
      }
    }
    long[] all = timings.stream().flatMapToLong(Arrays::stream).toArray();
    return new Phase(all.length, all.length / elapsedSeconds, LatencySummary.of(all));
  }

  private static long[] write(
      HikariDataSource pool, String sql, AtomicLong nextRow, long endRow, long deadline)
      throws SQLException {
    long[] timings = new long[MOST_TIMINGS_PER_WRITER];
    int done = 0;
    try (Connection connection = pool.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      while (System.nanoTime() < deadline && done < MOST_TIMINGS_PER_WRITER) {
        long row = nextRow.getAndIncrement();
        if (row >= endRow) {
          break;
        }
        statement.setLong(1, row);
        long started = System.nanoTime();
        statement.executeUpdate();
        timings[done++] = System.nanoTime() - started;
      }
    }
    return Arrays.copyOf(timings, done);
  }

  // The statistics views lag: each backend sends its counts at most once a second, and an idle
  // one after about ten. So the count is read again until it covers every timed update.
  private static String hotUpdates(HikariDataSource pool, String table, long expectedUpdates)
      throws SQLException, InterruptedException {
    long deadline = System.nanoTime() + STATISTICS_PATIENCE.toNanos();
    long updated = 0;
    long hot = 0;
    try (Connection connection = pool.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT n_tup_upd, n_tup_hot_upd FROM pg_stat_user_tables WHERE relid = ?::regclass")) {
      statement.setString(1, SCHEMA + "." + table);
      while (System.nanoTime() < deadline) {
        try (ResultSet counts = statement.executeQuery()) {
          counts.next();
          updated = counts.getLong(1);
          hot = counts.getLong(2);
        }
        if (updated >= expectedUpdates) {
          return String.format(Locale.ROOT, "%,d of %,d", hot, updated);
        }
        Thread.sleep(STATISTICS_POLL);
      }
    }
    return String.format(
        Locale.ROOT, "%,d of %,d counted so far (statistics incomplete)", hot, updated);
  }

  private static long indexBytes(HikariDataSource pool, String table) throws SQLException {
    try (Connection connection = pool.getConnection();
        PreparedStatement statement =
            connection.prepareStatement("SELECT pg_indexes_size(?::regclass)")) {
      statement.setString(1, SCHEMA + "." + table);
      try (ResultSet size = statement.executeQuery()) {
        size.next();
        return size.getLong(1);
      }
    }
  }
}
