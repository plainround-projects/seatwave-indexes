package io.plainround.seatwave.indexes.labs;

import io.plainround.seatwave.indexes.study.StudySettings;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Lab 2's solution: proves that column order decides whether a composite index is used. On the bare
 * study schema (seats has only seats_pkey) it explains the seats half of the seat map three times:
 * with the primary key alone, with an index in the wrong order, and with the right one, and prints
 * each plan and how often each index was used. It creates and drops only its own two indexes, each
 * built concurrently so the lab never blocks writes.
 */
public final class ColumnOrderCli {

  private static final Path SETTINGS = Path.of("config/study.properties");
  private static final String SEAT_MAP_SEATS =
      """
      SELECT seat_label, seat_number FROM seats
      WHERE event_id = ? AND section_code = ?
      ORDER BY seat_number
      """;
  private static final long EVENT_ID = 1;
  private static final String SECTION_CODE = "A";

  private record Trial(String label, List<String> before, List<String> after) {}

  private static final List<Trial> TRIALS =
      List.of(
          new Trial("primary key only", List.of(), List.of()),
          new Trial(
              "wrong order (section_code, seat_number, event_id)",
              List.of(
                  "CREATE INDEX CONCURRENTLY lab_seats_wrong ON seats (section_code, seat_number, event_id)"),
              List.of("DROP INDEX CONCURRENTLY lab_seats_wrong")),
          new Trial(
              "right order (event_id, section_code, seat_number) INCLUDE (seat_label)",
              List.of(
                  "CREATE INDEX CONCURRENTLY lab_seats_right ON seats (event_id, section_code, seat_number)"
                      + " INCLUDE (seat_label)"),
              List.of("DROP INDEX CONCURRENTLY lab_seats_right")));

  private ColumnOrderCli() {}

  /** Runs the three trials in order and prints their plans. */
  public static void main(String[] arguments) throws SQLException {
    StudySettings settings = StudySettings.load(SETTINGS);
    try (Connection connection =
            DriverManager.getConnection(settings.jdbcUrl(), settings.user(), settings.password());
        Statement statement = connection.createStatement()) {
      statement.execute("SET search_path TO seatwave_study");
      for (Trial trial : TRIALS) {
        for (String ddl : trial.before()) {
          statement.execute(ddl);
        }
        // Fresh statistics, so the planner sees the new index's real size.
        statement.execute("ANALYZE seats");
        System.out.println("== " + trial.label());
        explain(connection);
        printIndexUse(connection);
        for (String ddl : trial.after()) {
          statement.execute(ddl);
        }
      }
    }
  }

  private static void explain(Connection connection) throws SQLException {
    try (PreparedStatement explain =
        connection.prepareStatement("EXPLAIN (ANALYZE, BUFFERS, COSTS OFF) " + SEAT_MAP_SEATS)) {
      explain.setLong(1, EVENT_ID);
      explain.setString(2, SECTION_CODE);
      try (ResultSet plan = explain.executeQuery()) {
        while (plan.next()) {
          System.out.println("  " + plan.getString(1));
        }
      }
    }
  }

  private static void printIndexUse(Connection connection) throws SQLException {
    String sql =
        """
        SELECT indexrelname, idx_scan FROM pg_stat_user_indexes
        WHERE schemaname = 'seatwave_study' AND relname = 'seats' ORDER BY indexrelname
        """;
    try (Statement flush = connection.createStatement()) {
      // A session sends its counts to the shared statistics at most once a second; without this,
      // the scan the explain just made is often not counted yet and an index looks unused.
      flush.execute("SELECT pg_stat_force_next_flush()");
    }
    try (Statement statement = connection.createStatement();
        ResultSet indexes = statement.executeQuery(sql)) {
      while (indexes.next()) {
        System.out.println(
            "  index " + indexes.getString(1) + ": " + indexes.getLong(2) + " scans so far");
      }
    }
  }
}
