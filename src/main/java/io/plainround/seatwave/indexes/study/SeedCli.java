package io.plainround.seatwave.indexes.study;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Loads the study's volume into the schema {@code seatwave_study}: at the module's full volume
 * (2,500 arena events and 2,500 club nights) 50 venues, 5,000 events, 50,500,000 seats, 5,000,000
 * customers, 60,000,000 holds and 40,000,000 bookings; config/study.properties loads a quarter of
 * it. The database generates the rows itself ({@code generate_series}), one batch of events per
 * statement, so the load needs no client-side loop over fifty million rows.
 *
 * <p>Guardrails: a dry run by default; {@code --run} asks for the schema name before it writes
 * ({@code --yes} skips the question); it refuses a schema that already holds seats; it checks every
 * count afterwards and exits 1 if one differs.
 */
public final class SeedCli {

  private static final Path SETTINGS = Path.of("config/study.properties");
  private static final String SCHEMA = "seatwave_study";
  private static final int EVENTS_PER_BATCH = 100;
  // Fixed by the study's design (Section 8): 50 venues, 20 sections of 1,000 seats per arena,
  // 800 of each arena section's seats sold, one club section of 200, and 2,000 customers per
  // arena event (5,000,000 at the full 2,500), so a customer has 8 bookings at every volume.
  private static final long VENUES = 50;
  private static final long CUSTOMERS_PER_ARENA_EVENT = 2_000;
  private static final long ARENA_SECTIONS = 20;
  private static final long ARENA_SEATS = 20_000;
  private static final long CLUB_SEATS = 200;
  private static final long SOLD_PER_ARENA = 16_000;
  private static final long UNSOLD_PER_ARENA = ARENA_SEATS - SOLD_PER_ARENA;
  private static final int EXIT_FAILED = 1;
  private static final double NANOS_PER_SECOND = 1_000_000_000.0;

  private SeedCli() {}

  /** Prints the plan; with {@code --run}, loads it. */
  public static void main(String[] arguments) throws SQLException {
    List<String> options = List.of(arguments);
    StudySettings settings = StudySettings.load(SETTINGS);
    Map<String, Long> expected = expectedCounts(settings);
    System.out.println("plan: create schema " + SCHEMA + " (bare-schema.sql) and load " + expected);
    if (!options.contains("--run")) {
      System.out.println("dry run: nothing changed. Add --run to load.");
      return;
    }
    if (!options.contains("--yes") && !isConfirmed()) {
      System.out.println("not confirmed: nothing changed.");
      return;
    }
    try (Connection connection =
        DriverManager.getConnection(settings.jdbcUrl(), settings.user(), settings.password())) {
      createSchemaIfMissing(connection);
      if (count(connection, "seats") > 0) {
        System.out.println(
            "refused: " + SCHEMA + ".seats already holds rows; drop the schema to load again.");
        System.exit(EXIT_FAILED);
      }
      load(connection, settings);
      if (!verify(connection, expected)) {
        System.exit(EXIT_FAILED);
      }
    }
  }

  private static boolean isConfirmed() {
    System.out.print("type " + SCHEMA + " to load it: ");
    try {
      String answer =
          new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
      return SCHEMA.equals(answer == null ? "" : answer.strip());
    } catch (IOException readFailure) {
      throw new UncheckedIOException("cannot read the confirmation", readFailure);
    }
  }

  /** The row count of every table that {@link #load} must produce for these settings. */
  static Map<String, Long> expectedCounts(StudySettings settings) {
    long arenas = settings.arenaEvents();
    long clubs = settings.clubEvents();
    Map<String, Long> counts = new LinkedHashMap<>();
    counts.put("venues", VENUES);
    counts.put("events", arenas + clubs);
    counts.put("sections", arenas * ARENA_SECTIONS + clubs);
    counts.put("seats", arenas * ARENA_SEATS + clubs * CLUB_SEATS);
    counts.put("customers", customers(settings));
    // Sold seats once each; each unsold arena seat twice (expired, then released), except event
    // 1, which is on sale: one expired attempt and one ACTIVE hold per unsold seat.
    counts.put(
        "holds",
        arenas * SOLD_PER_ARENA + (arenas - 1) * 2 * UNSOLD_PER_ARENA + 2 * UNSOLD_PER_ARENA);
    counts.put("bookings", arenas * SOLD_PER_ARENA);
    return counts;
  }

  /** Creates the bare schema from bare-schema.sql unless the schema already exists. */
  static void createSchemaIfMissing(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet found =
            statement.executeQuery("SELECT to_regnamespace('" + SCHEMA + "') IS NOT NULL")) {
      found.next();
      if (found.getBoolean(1)) {
        return;
      }
    }
    try (Statement statement = connection.createStatement()) {
      // A multi-statement script: the PostgreSQL driver sends it as one simple query.
      statement.execute(resource("/db/study/bare-schema.sql"));
    }
  }

  /** Loads every table, then vacuums and analyzes each; the connection must be in autocommit. */
  static void load(Connection connection, StudySettings settings) throws SQLException {
    long arenas = settings.arenaEvents();
    long clubs = settings.clubEvents();
    long customers = customers(settings);
    run(connection, "venues", SeedSql.VENUES);
    run(connection, "arena events", SeedSql.ARENA_EVENTS, arenas);
    run(connection, "club events", SeedSql.CLUB_EVENTS, arenas, clubs);
    run(connection, "arena sections", SeedSql.ARENA_SECTIONS, arenas);
    run(connection, "club sections", SeedSql.CLUB_SECTIONS, arenas + 1, arenas + clubs);
    for (long first = 1; first <= arenas; first += EVENTS_PER_BATCH) {
      long last = Math.min(arenas, first + EVENTS_PER_BATCH - 1);
      run(connection, "arena seats " + first + "-" + last, SeedSql.ARENA_SEATS, first, last);
      run(
          connection,
          "sold holds " + first + "-" + last,
          SeedSql.SOLD_HOLDS,
          customers,
          first,
          last);
      run(
          connection,
          "ended holds " + first + "-" + last,
          SeedSql.ENDED_HOLDS,
          customers,
          first,
          last);
    }
    run(connection, "club seats", SeedSql.CLUB_SEATS, arenas + 1, arenas + clubs);
    run(connection, "customers", SeedSql.CUSTOMERS, customers);
    run(connection, "active holds of event 1", SeedSql.ACTIVE_HOLDS);
    run(connection, "bookings", SeedSql.BOOKINGS);
    for (String table :
        List.of("venues", "events", "sections", "seats", "customers", "holds", "bookings")) {
      // VACUUM sets the visibility map, which index-only scans need; ANALYZE gives the planner
      // statistics. VACUUM cannot run in a transaction; the connection is in autocommit.
      run(connection, "vacuum analyze " + table, "VACUUM (ANALYZE) " + SCHEMA + "." + table);
    }
  }

  private static void run(Connection connection, String step, String sql, long... parameters)
      throws SQLException {
    long started = System.nanoTime();
    try (Statement searchPath = connection.createStatement()) {
      searchPath.execute("SET search_path TO " + SCHEMA);
    }
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int position = 0; position < parameters.length; position++) {
        statement.setLong(position + 1, parameters[position]);
      }
      statement.execute();
    } catch (SQLException failure) {
      throw new SQLException(
          "seed step '" + step + "' failed: " + failure.getMessage(),
          failure.getSQLState(),
          failure);
    }
    System.out.printf(
        Locale.ROOT, "  %-28s %,8.1f s%n", step, (System.nanoTime() - started) / NANOS_PER_SECOND);
  }

  private static boolean verify(Connection connection, Map<String, Long> expected)
      throws SQLException {
    boolean isEveryCountRight = true;
    for (Map.Entry<String, Long> table : expected.entrySet()) {
      long actual = count(connection, table.getKey());
      boolean isRight = actual == table.getValue();
      isEveryCountRight &= isRight;
      System.out.printf(
          Locale.ROOT,
          "  check %-10s expected %,12d, found %,12d %s%n",
          table.getKey(),
          table.getValue(),
          actual,
          isRight ? "ok" : "WRONG");
    }
    System.out.println(
        "audit: loaded schema " + SCHEMA + " as " + connection.getMetaData().getUserName());
    return isEveryCountRight;
  }

  private static long customers(StudySettings settings) {
    return settings.arenaEvents() * CUSTOMERS_PER_ARENA_EVENT;
  }

  /** The number of rows in one study table; the name must be one of this class's own. */
  static long count(Connection connection, String table) throws SQLException {
    // The table name comes from this class's own fixed list, never from input.
    try (Statement statement = connection.createStatement();
        ResultSet counted =
            statement.executeQuery("SELECT count(*) FROM " + SCHEMA + "." + table)) {
      counted.next();
      return counted.getLong(1);
    }
  }

  private static String resource(String name) {
    try (InputStream stream =
        Objects.requireNonNull(SeedCli.class.getResourceAsStream(name), name)) {
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException readFailure) {
      throw new UncheckedIOException("cannot read resource " + name, readFailure);
    }
  }
}
