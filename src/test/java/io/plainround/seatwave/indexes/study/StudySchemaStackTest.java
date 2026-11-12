package io.plainround.seatwave.indexes.study;

import static org.assertj.core.api.Assertions.assertThat;

import io.plainround.seatwave.indexes.catalog.ListedQuery;
import io.plainround.seatwave.indexes.catalog.WriteStep;
import io.plainround.seatwave.indexes.ledger.IndexLedger;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The study on a real PostgreSQL 18.6: a small volume loaded by the seeder, the final index set
 * applied the way Section 8 applies it, and the claims the module makes about both checked against
 * the database itself. Needs the fresh database that scripts/stack.sh test starts; it refuses a
 * database that already holds the study, and drops the schema when it is done.
 */
@Tag("stack")
class StudySchemaStackTest {

  private static final Path SETTINGS = Path.of("src/test/resources/stack-study.properties");
  private static final Path STUDY = Path.of("src/main/resources/db/study");
  private static final String SCHEMA = "seatwave_study";
  // Rows the write steps prefill before the one timed insert and update each test makes.
  private static final long PREFILL_ROWS = 1_000;

  private static Connection connection;
  private static StudySettings settings;

  @BeforeAll
  static void loadTheStudyAndItsFinalIndexes() throws SQLException, IOException {
    settings = StudySettings.load(SETTINGS);
    connection =
        DriverManager.getConnection(settings.jdbcUrl(), settings.user(), settings.password());
    try (Statement statement = connection.createStatement();
        ResultSet found =
            statement.executeQuery("SELECT to_regnamespace('" + SCHEMA + "') IS NOT NULL")) {
      found.next();
      if (found.getBoolean(1)) {
        throw new IllegalStateException(
            "the database already holds "
                + SCHEMA
                + "; the stack tests need the fresh one scripts/stack.sh test starts");
      }
    }
    SeedCli.createSchemaIfMissing(connection);
    SeedCli.load(connection, settings);
    try (Statement statement = connection.createStatement()) {
      // One statement at a time in autocommit, as psql runs the file: CREATE INDEX CONCURRENTLY
      // cannot run inside a transaction block, which a multi-statement string would be.
      for (String ddl : statements(read("final-indexes.sql"))) {
        statement.execute(ddl);
      }
      statement.execute("SET search_path TO " + SCHEMA);
    }
  }

  @AfterAll
  static void dropTheStudy() throws SQLException {
    if (connection == null) {
      return;
    }
    try (Connection study = connection;
        Statement statement = study.createStatement()) {
      statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
    }
  }

  @Test
  void loadsExactlyTheRowsTheSeedPlans() throws SQLException {
    Map<String, Long> expected = SeedCli.expectedCounts(settings);
    Map<String, Long> loaded = new LinkedHashMap<>();
    for (String table : expected.keySet()) {
      loaded.put(table, SeedCli.count(connection, table));
    }

    assertThat(loaded).isEqualTo(expected);
  }

  @Test
  void holdsExactlyTheIndexesTheLedgerChecksOnTheirTables() throws SQLException, IOException {
    Map<String, String> fromTheSchemaFiles =
        IndexLedger.indexTablesIn(read("bare-schema.sql") + read("final-indexes.sql"));
    Map<String, String> inTheDatabase = new LinkedHashMap<>();
    try (Statement statement = connection.createStatement();
        ResultSet indexes =
            statement.executeQuery(
                "SELECT indexname, tablename FROM pg_indexes WHERE schemaname = '"
                    + SCHEMA
                    + "'")) {
      while (indexes.next()) {
        inTheDatabase.put(indexes.getString(1), indexes.getString(2));
      }
    }

    assertThat(inTheDatabase).containsExactlyInAnyOrderEntriesOf(fromTheSchemaFiles);
  }

  @Test
  void leavesNoIndexInvalidAfterTheConcurrentBuilds() throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet invalid =
            statement.executeQuery("SELECT count(*) FROM pg_index WHERE NOT indisvalid")) {
      invalid.next();

      assertThat(invalid.getLong(1)).isZero();
    }
  }

  @Test
  void answersTheSeatMapFromTheCoveringIndexAndTheClaimIndex() throws SQLException {
    String plan = explain(ListedQuery.Q1);

    // Whether the claims are probed one seat at a time or read for the whole event in one bitmap
    // is the planner's choice by size; either way, no table is scanned.
    assertThat(plan)
        .contains("Index Only Scan using one_seat_per_number on seats")
        .contains("one_claim_per_seat")
        .doesNotContain("Seq Scan");
  }

  @Test
  void usesThePartialIndexOnlyWhenTheQueryRepeatsItsPredicate() throws SQLException {
    // Q1 without "AND h.state IN ('ACTIVE', 'CONFIRMED')": nothing in it implies the index's WHERE.
    String withoutPredicate =
        ListedQuery.Q1.sql().orElseThrow().replaceAll("(?m)^.*h\\.state IN.*\\n", "");
    String plan;
    try (PreparedStatement explain =
        connection.prepareStatement("EXPLAIN (COSTS OFF) " + withoutPredicate)) {
      PlanRunnerCli.bind(explain, ListedQuery.Q1);
      plan = lines(explain);
    }

    assertThat(withoutPredicate).doesNotContain("h.state IN");
    assertThat(plan).doesNotContain("one_claim_per_seat");
  }

  @Test
  void servesTheBasketTheTicketsPageAndTheSweeperFromTheirIndexes() throws SQLException {
    // Q4 is left out: at this test's 20 events, events is one page and a scan is the right plan.
    assertThat(explain(ListedQuery.Q2))
        .contains("active_holds_by_customer")
        .doesNotContain("Seq Scan");
    assertThat(explain(ListedQuery.Q3))
        .contains("bookings_by_customer_date")
        .doesNotContain("Seq Scan");
    assertThat(explain(ListedQuery.Q5))
        .contains("active_holds_by_expiry")
        .doesNotContain("Seq Scan");
  }

  @ParameterizedTest
  @EnumSource(WriteStep.class)
  void buildsEachBenchmarkStepsIndexesAndWritesItsRows(WriteStep step) throws SQLException {
    String table = "bench_" + step.table();
    long indexes;
    long updated = 0;
    try (Statement statement = connection.createStatement()) {
      statement.execute("DROP TABLE IF EXISTS " + table);
      statement.execute(
          "CREATE TABLE "
              + table
              + " (LIKE "
              + step.table()
              + " INCLUDING DEFAULTS INCLUDING CONSTRAINTS)");
      for (String ddl : step.indexDdl()) {
        statement.execute(String.format(Locale.ROOT, ddl, table));
      }
      writeOne(
          String.format(Locale.ROOT, step.insertSql(), table, WriteBenchmarkCli.SERIES),
          PREFILL_ROWS);
      writeOne(
          String.format(Locale.ROOT, step.insertSql(), table, WriteBenchmarkCli.ONE_ROW),
          WriteBenchmarkCli.FIRST_TIMED_ROW);
      if (step.updateSql().isPresent()) {
        updated =
            writeOne(
                String.format(Locale.ROOT, step.updateSql().get(), table),
                WriteBenchmarkCli.FIRST_TIMED_ROW);
      }
      try (ResultSet counted =
          statement.executeQuery(
              "SELECT count(*) FROM pg_indexes WHERE tablename = '" + table + "'")) {
        counted.next();
        indexes = counted.getLong(1);
      }
      statement.execute("DROP TABLE " + table);
    }

    assertThat(indexes).isEqualTo(step.indexDdl().size());
    assertThat(updated).isEqualTo(step.updateSql().isPresent() ? 1 : 0);
  }

  private static long writeOne(String sql, long rowNumber) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, rowNumber);
      return statement.executeUpdate();
    }
  }

  private static String explain(ListedQuery query) throws SQLException {
    try (PreparedStatement explain =
        connection.prepareStatement("EXPLAIN (COSTS OFF) " + query.sql().orElseThrow())) {
      PlanRunnerCli.bind(explain, query);
      return lines(explain);
    }
  }

  private static String lines(PreparedStatement explain) throws SQLException {
    List<String> plan = new ArrayList<>();
    try (ResultSet rows = explain.executeQuery()) {
      while (rows.next()) {
        plan.add(rows.getString(1));
      }
    }
    return String.join("\n", plan);
  }

  private static String read(String fileName) throws IOException {
    return Files.readString(STUDY.resolve(fileName), StandardCharsets.UTF_8);
  }

  private static List<String> statements(String sqlFile) {
    String withoutComments = sqlFile.replaceAll("--[^\\n]*", "");
    return Arrays.stream(withoutComments.split(";"))
        .map(String::strip)
        .filter(ddl -> !ddl.isEmpty())
        .toList();
  }
}
