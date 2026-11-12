package io.plainround.seatwave.indexes.catalog;

import java.util.List;
import java.util.Optional;

/**
 * The write benchmark's steps. Each step builds a fresh copy of one table (named {@code bench_} +
 * table) with one set of indexes, then times single-row writes into it. Every table of the final
 * schema has a step with all of its final indexes, so every row of the index ledger can name the
 * step that measured its write cost on its own table. Every SQL text takes the table name as {@code
 * %1$s}; the inserts compute every column from one row number, so the benchmark binds a single
 * {@code bigint}, cast to {@code int} where {@code chr} needs one. Hold ids are {@code md5(row
 * number)} cast to a UUID: random-looking like Module 13's, yet an update can find its row again by
 * primary key.
 */
public enum WriteStep {
  HOLDS_0("holds", List.of(Ddl.HOLDS_PKEY), Writes.HOLD_INSERT, Optional.of(Writes.HOLD_CONFIRM)),
  HOLDS_2(
      "holds",
      List.of(Ddl.HOLDS_PKEY, Ddl.ONE_CLAIM_PER_SEAT, Ddl.ACTIVE_BY_CUSTOMER),
      Writes.HOLD_INSERT,
      Optional.of(Writes.HOLD_CONFIRM)),
  HOLDS_5(
      "holds",
      List.of(
          Ddl.HOLDS_PKEY,
          Ddl.ONE_CLAIM_PER_SEAT,
          Ddl.ACTIVE_BY_CUSTOMER,
          Ddl.HOLD_IDENTITY,
          Ddl.ACTIVE_BY_EXPIRY,
          Ddl.HOLDS_BY_CUSTOMER_ALL),
      Writes.HOLD_INSERT,
      Optional.of(Writes.HOLD_CONFIRM)),
  HOLDS_FINAL(
      "holds",
      List.of(
          Ddl.HOLDS_PKEY,
          Ddl.ONE_CLAIM_PER_SEAT,
          Ddl.ACTIVE_BY_CUSTOMER,
          Ddl.HOLD_IDENTITY,
          Ddl.ACTIVE_BY_EXPIRY),
      Writes.HOLD_INSERT,
      Optional.of(Writes.HOLD_CONFIRM)),
  BOOKINGS_FINAL(
      "bookings",
      List.of(
          "ALTER TABLE %1$s ADD PRIMARY KEY (booking_id)",
          "CREATE UNIQUE INDEX ON %1$s (hold_id)",
          "CREATE UNIQUE INDEX ON %1$s (event_id, seat_label)",
          "CREATE INDEX ON %1$s (customer_id, confirmed_at)"),
      Writes.BOOKING_INSERT,
      Optional.empty()),
  EVENT_SETUP(
      "seats",
      List.of(
          "ALTER TABLE %1$s ADD PRIMARY KEY (event_id, seat_label)",
          "CREATE UNIQUE INDEX ON %1$s (event_id, section_code, seat_number) INCLUDE (seat_label)"),
      Writes.SEAT_INSERT,
      Optional.empty()),
  CUSTOMER_SIGNUP(
      "customers",
      List.of(
          "ALTER TABLE %1$s ADD PRIMARY KEY (customer_id)",
          "CREATE UNIQUE INDEX ON %1$s (customer_ref)"),
      Writes.CUSTOMER_INSERT,
      Optional.empty()),
  EVENT_CREATE(
      "events",
      List.of(
          "ALTER TABLE %1$s ADD PRIMARY KEY (event_id)",
          "CREATE INDEX ON %1$s (venue_id, starts_at)"),
      Writes.EVENT_INSERT,
      Optional.empty()),
  SECTION_CREATE(
      "sections",
      List.of("ALTER TABLE %1$s ADD PRIMARY KEY (event_id, section_code)"),
      Writes.SECTION_INSERT,
      Optional.empty()),
  VENUE_CREATE(
      "venues",
      List.of("ALTER TABLE %1$s ADD PRIMARY KEY (venue_id)"),
      Writes.VENUE_INSERT,
      Optional.empty());

  private final String table;
  private final List<String> indexDdl;
  private final String insertSql;
  private final Optional<String> updateSql;

  WriteStep(String table, List<String> indexDdl, String insertSql, Optional<String> updateSql) {
    this.table = table;
    this.indexDdl = List.copyOf(indexDdl);
    this.insertSql = insertSql;
    this.updateSql = updateSql;
  }

  /** The SeatWave table this step copies. */
  public String table() {
    return table;
  }

  /** The DDL that gives the copy this step's indexes, primary key included. */
  public List<String> indexDdl() {
    return indexDdl;
  }

  /** One insert; {@code %2$s} is replaced by the row source, one row or a series. */
  public String insertSql() {
    return insertSql;
  }

  /** One update of a row the insert phase wrote, for the steps that measure updates. */
  public Optional<String> updateSql() {
    return updateSql;
  }

  private static final class Ddl {
    static final String HOLDS_PKEY = "ALTER TABLE %1$s ADD PRIMARY KEY (hold_id)";
    static final String ONE_CLAIM_PER_SEAT =
        "CREATE UNIQUE INDEX ON %1$s (event_id, seat_label) WHERE state IN ('ACTIVE', 'CONFIRMED')";
    static final String ACTIVE_BY_CUSTOMER =
        "CREATE INDEX ON %1$s (customer_id) WHERE state = 'ACTIVE'";
    static final String HOLD_IDENTITY =
        "CREATE UNIQUE INDEX ON %1$s (hold_id, event_id, seat_label, customer_id)";
    static final String ACTIVE_BY_EXPIRY =
        "CREATE INDEX ON %1$s (expires_at) WHERE state = 'ACTIVE'";
    static final String HOLDS_BY_CUSTOMER_ALL = "CREATE INDEX ON %1$s (customer_id, created_at)";

    private Ddl() {}
  }

  private static final class Writes {
    // Row n is a hold on a seat of a benchmark-only event (ids from 1,000,000 up, 20,000 seats
    // each), for one of 5,000,000 customers, created at a fixed instant plus n milliseconds. The
    // prefill uses rows below FIRST_TIMED_ROW and writes them as CONFIRMED history, so the partial
    // indexes stay as small as production's; the timed rows are ACTIVE, like a new hold.
    static final String HOLD_INSERT =
        """
        INSERT INTO %1$s (hold_id, event_id, seat_label, customer_id, state, created_at, expires_at, ended_at)
        SELECT md5(n::text)::uuid, 1000000 + n / 20000,
               chr(65 + ((n %% 20000) / 1000)::int) || '-' || ((n %% 1000) + 1),
               1 + n %% 5000000,
               CASE WHEN n < 1000000000 THEN 'CONFIRMED' ELSE 'ACTIVE' END,
               TIMESTAMPTZ '2026-11-11 10:00:00+00' + n * INTERVAL '1 millisecond',
               TIMESTAMPTZ '2026-11-11 10:05:00+00' + n * INTERVAL '1 millisecond',
               CASE WHEN n < 1000000000
                    THEN TIMESTAMPTZ '2026-11-11 10:01:00+00' + n * INTERVAL '1 millisecond' END
        FROM %2$s
        """;
    static final String HOLD_CONFIRM =
        """
        UPDATE %1$s SET state = 'CONFIRMED', ended_at = created_at + INTERVAL '60 seconds'
        WHERE hold_id = md5(?::text)::uuid AND state = 'ACTIVE'
        """;
    static final String BOOKING_INSERT =
        """
        INSERT INTO %1$s (booking_id, hold_id, event_id, seat_label, customer_id, price, currency, confirmed_at)
        SELECT md5('booking' || n)::uuid, md5(n::text)::uuid, 1000000 + n / 20000,
               chr(65 + ((n %% 20000) / 1000)::int) || '-' || ((n %% 1000) + 1),
               1 + n %% 5000000, 80.00, 'USD',
               TIMESTAMPTZ '2026-11-11 10:01:00+00' + n * INTERVAL '1 millisecond'
        FROM %2$s
        """;
    static final String SEAT_INSERT =
        """
        INSERT INTO %1$s (event_id, seat_label, section_code, seat_number)
        SELECT 1000000 + n / 20000, chr(65 + ((n %% 20000) / 1000)::int) || '-' || ((n %% 1000) + 1),
               chr(65 + ((n %% 20000) / 1000)::int), (n %% 1000) + 1
        FROM %2$s
        """;
    static final String CUSTOMER_INSERT =
        """
        INSERT INTO %1$s (customer_id, customer_ref, created_at)
        SELECT 10000000 + n, 'bench_' || n, TIMESTAMPTZ '2026-11-11 10:00:00+00'
        FROM %2$s
        """;
    // A new event every hour at one of 50 venues, so each venue's starts_at arrives in order.
    static final String EVENT_INSERT =
        """
        INSERT INTO %1$s (event_id, venue_id, name, starts_at)
        SELECT 1000000 + n, 1 + n %% 50, 'Bench event ' || n,
               TIMESTAMPTZ '2026-12-08 20:00:00+00' + n * INTERVAL '1 hour'
        FROM %2$s
        """;
    // Sections A to T of one benchmark event after another, as an event's set-up writes them.
    static final String SECTION_INSERT =
        """
        INSERT INTO %1$s (event_id, section_code, price, currency)
        SELECT 1000000 + n / 20, chr(65 + (n %% 20)::int), 80.00, 'USD'
        FROM %2$s
        """;
    static final String VENUE_INSERT =
        """
        INSERT INTO %1$s (venue_id, name)
        SELECT 1000000 + n, 'Bench venue ' || n
        FROM %2$s
        """;

    private Writes() {}
  }
}
