package io.plainround.seatwave.indexes.catalog;

import java.util.Optional;

/**
 * Every query and rule check SeatWave runs against its tables, by id. An index may exist only if at
 * least one of these uses it (the index ledger records which). Q1 to Q5 are the five study queries,
 * which the plan runner explains and times; R1 to R6 are the statements of Module 13's write path
 * and rules, which need an index to be correct or fast but are not timed here.
 */
public enum ListedQuery {
  Q1(
      "seat map: one section of one event, with each seat's live claim",
      """
      SELECT s.seat_label, s.seat_number, h.state, h.expires_at
      FROM seats s
      LEFT JOIN holds h
        ON h.event_id = s.event_id
       AND h.seat_label = s.seat_label
       AND h.state IN ('ACTIVE', 'CONFIRMED')
      WHERE s.event_id = ? AND s.section_code = ?
      ORDER BY s.seat_number
      """),
  Q2(
      "active holds of one customer (the basket, and the hold-limit trigger's count)",
      """
      SELECT hold_id, event_id, seat_label, expires_at
      FROM holds
      WHERE customer_id = ? AND state = 'ACTIVE'
      """),
  Q3(
      "one customer's bookings in a date range, newest first (the my-tickets page)",
      """
      SELECT booking_id, event_id, seat_label, price, confirmed_at
      FROM bookings
      WHERE customer_id = ? AND confirmed_at >= ? AND confirmed_at < ?
      ORDER BY confirmed_at DESC
      """),
  Q4(
      "one venue's events in a date window (the event list)",
      """
      SELECT event_id, name, starts_at
      FROM events
      WHERE venue_id = ? AND starts_at >= ? AND starts_at < ?
      ORDER BY starts_at
      """),
  Q5(
      "the expiry sweeper: the oldest 500 active holds that have expired",
      """
      SELECT hold_id, event_id, seat_label
      FROM holds
      WHERE state = 'ACTIVE' AND expires_at < ?
      ORDER BY expires_at
      LIMIT 500
      """),
  R1("hold insert: INSERT ... ON CONFLICT DO NOTHING against one live claim per seat"),
  R2("confirm and release: find one hold by hold_id"),
  R3("booking insert: the foreign key booking_matches_its_hold looks up the hold's identity"),
  R4("booking insert: one booking per hold and per seat; find a booking by booking_id"),
  R5("sign-in: find a customer by customer_ref, and keep the reference unique"),
  R6("foreign-key checks and lookups of a parent row by its primary key");

  private final String purpose;
  private final Optional<String> sql;

  ListedQuery(String purpose, String sql) {
    this.purpose = purpose;
    this.sql = Optional.of(sql.strip());
  }

  ListedQuery(String purpose) {
    this.purpose = purpose;
    this.sql = Optional.empty();
  }

  /** What the query is for, in one line. */
  public String purpose() {
    return purpose;
  }

  /** The SQL with JDBC placeholders, for the five study queries; empty for the rule checks. */
  public Optional<String> sql() {
    return sql;
  }
}
