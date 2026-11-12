package io.plainround.seatwave.indexes.study;

import io.plainround.seatwave.indexes.catalog.ListedQuery;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Explains and times the five study queries, and lists every index with its size and use count,
 * into {@code build/study/plans-<label>.txt}. Run it once with the label {@code before} on the bare
 * schema and once with {@code after} when final-indexes.sql has been applied.
 *
 * <p>Usage: {@code PlanRunnerCli before|after}. Read-only: it changes no row and no index.
 */
public final class PlanRunnerCli {

  private static final Path SETTINGS = Path.of("config/study.properties");
  private static final Path REPORT_FOLDER = Path.of("build/study");
  private static final Set<String> LABELS = Set.of("before", "after");
  // The sample arguments: event 1 section A (Module 13's arena); customer 42, who holds four
  // seats of event 1; venue 1's next four weeks; the sweeper's "now", 100 seconds into expiries.
  private static final long EVENT_ID = 1;
  private static final String SECTION_CODE = "A";
  private static final long CUSTOMER_ID = 42;
  private static final long VENUE_ID = 1;
  private static final OffsetDateTime BOOKINGS_FROM = OffsetDateTime.parse("2026-11-01T00:00:00Z");
  private static final OffsetDateTime BOOKINGS_UNTIL = OffsetDateTime.parse("2027-11-01T00:00:00Z");
  private static final OffsetDateTime EVENTS_FROM = OffsetDateTime.parse("2026-12-08T00:00:00Z");
  private static final OffsetDateTime EVENTS_UNTIL = OffsetDateTime.parse("2027-01-05T00:00:00Z");
  private static final OffsetDateTime SWEEP_NOW = OffsetDateTime.parse("2026-11-11T10:06:40Z");
  private static final int EXIT_USAGE = 2;
  private static final double BYTES_PER_MB = 1_048_576.0;

  private PlanRunnerCli() {}

  /** Writes the report for the given label and prints it. */
  public static void main(String[] arguments) throws SQLException {
    if (arguments.length != 1 || !LABELS.contains(arguments[0])) {
      System.err.println("usage: PlanRunnerCli before|after");
      System.exit(EXIT_USAGE);
    }
    StudySettings settings = StudySettings.load(SETTINGS);
    List<String> report = new ArrayList<>();
    try (Connection connection =
        DriverManager.getConnection(settings.jdbcUrl(), settings.user(), settings.password())) {
      try (Statement statement = connection.createStatement()) {
        statement.execute("SET search_path TO seatwave_study");
      }
      for (ListedQuery query : ListedQuery.values()) {
        if (query.sql().isPresent()) {
          report.addAll(explainAndTime(connection, query, settings.planRepetitions()));
        }
      }
      report.addAll(indexSizes(connection));
    }
    Path reportFile = REPORT_FOLDER.resolve("plans-" + arguments[0] + ".txt");
    write(reportFile, report);
    report.forEach(System.out::println);
    System.out.println("written " + reportFile);
  }

  private static List<String> explainAndTime(
      Connection connection, ListedQuery query, int repetitions) throws SQLException {
    String sql = query.sql().orElseThrow();
    List<String> lines = new ArrayList<>();
    lines.add("== " + query.name() + ": " + query.purpose());
    // COSTS OFF keeps the planner's internal cost units out of the report: rows, buffers and times
    // are what the study compares. BUFFERS is on by default with ANALYZE since PostgreSQL 18.
    try (PreparedStatement explain =
        connection.prepareStatement("EXPLAIN (ANALYZE, BUFFERS, COSTS OFF) " + sql)) {
      bind(explain, query);
      try (ResultSet plan = explain.executeQuery()) {
        while (plan.next()) {
          lines.add("  " + plan.getString(1));
        }
      }
    }
    long[] timings = new long[repetitions];
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      bind(statement, query);
      // One untimed run first, so the first timed run does not pay to load pages into memory.
      consume(statement);
      for (int run = 0; run < repetitions; run++) {
        long started = System.nanoTime();
        consume(statement);
        timings[run] = System.nanoTime() - started;
      }
    }
    lines.add("  timed from Java, round trip included: " + LatencySummary.of(timings).describe());
    return lines;
  }

  /** Binds the sample arguments of one study query; the stack tests explain the same calls. */
  static void bind(PreparedStatement statement, ListedQuery query) throws SQLException {
    switch (query) {
      case Q1 -> {
        statement.setLong(1, EVENT_ID);
        statement.setString(2, SECTION_CODE);
      }
      case Q2 -> statement.setLong(1, CUSTOMER_ID);
      case Q3 -> {
        statement.setLong(1, CUSTOMER_ID);
        statement.setObject(2, BOOKINGS_FROM);
        statement.setObject(3, BOOKINGS_UNTIL);
      }
      case Q4 -> {
        statement.setLong(1, VENUE_ID);
        statement.setObject(2, EVENTS_FROM);
        statement.setObject(3, EVENTS_UNTIL);
      }
      case Q5 -> statement.setObject(1, SWEEP_NOW);
      case R1, R2, R3, R4, R5, R6 ->
          throw new IllegalArgumentException(query + " is a rule check with no study SQL to bind");
    }
  }

  private static void consume(PreparedStatement statement) throws SQLException {
    try (ResultSet rows = statement.executeQuery()) {
      while (rows.next()) {
        // Reading every row is part of the query's cost; the values themselves are not needed.
        rows.getObject(1);
      }
    }
  }

  private static List<String> indexSizes(Connection connection) throws SQLException {
    List<String> lines = new ArrayList<>();
    lines.add("== indexes: size and scans since the statistics were reset");
    try (Statement flush = connection.createStatement()) {
      // This session's own scans reach the shared counters at most once a second; without this,
      // the indexes the runs above just used would still show the old count.
      flush.execute("SELECT pg_stat_force_next_flush()");
    }
    String sql =
        """
        SELECT relname, indexrelname, pg_relation_size(indexrelid), idx_scan
        FROM pg_stat_user_indexes
        WHERE schemaname = 'seatwave_study'
        ORDER BY relname, indexrelname
        """;
    try (Statement statement = connection.createStatement();
        ResultSet indexes = statement.executeQuery(sql)) {
      while (indexes.next()) {
        lines.add(
            String.format(
                Locale.ROOT,
                "  %-10s %-28s %,10.1f MB  scans %,d",
                indexes.getString(1),
                indexes.getString(2),
                indexes.getLong(3) / BYTES_PER_MB,
                indexes.getLong(4)));
      }
    }
    return lines;
  }

  private static void write(Path reportFile, List<String> lines) {
    try {
      Files.createDirectories(reportFile.getParent());
      Files.write(reportFile, lines, StandardCharsets.UTF_8);
    } catch (IOException writeFailure) {
      throw new UncheckedIOException("cannot write " + reportFile.toAbsolutePath(), writeFailure);
    }
  }
}
