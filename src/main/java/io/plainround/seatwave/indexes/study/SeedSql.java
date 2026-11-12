package io.plainround.seatwave.indexes.study;

/**
 * The set-based statements {@link SeedCli} runs, in order. Each generates its rows inside the
 * database. Event 1 is Module 13's "Arena Night" at venue 1, "Arena", starting at the instant
 * Module 1 printed; every venue holds one event a week. Arena events are 1 to N on venues 1 to 25;
 * club nights follow on venues 26 to 50.
 */
final class SeedSql {

  static final String VENUES =
      """
      INSERT INTO venues (venue_id, name)
      SELECT venue_id, CASE WHEN venue_id = 1 THEN 'Arena' ELSE 'Venue ' || venue_id END
      FROM generate_series(1, 50) AS venues(venue_id)
      """;

  // Arena event e is at venue 1 + (e - 1) % 25, in week (e - 1) / 25 after Arena Night.
  static final String ARENA_EVENTS =
      """
      INSERT INTO events (event_id, venue_id, name, starts_at)
      SELECT event_id, 1 + (event_id - 1) % 25,
             CASE WHEN event_id = 1 THEN 'Arena Night' ELSE 'Arena event ' || event_id END,
             TIMESTAMPTZ '2026-12-08 20:20:51.783+00' + ((event_id - 1) / 25) * INTERVAL '7 days'
      FROM generate_series(1, ?) AS arena(event_id)
      """;

  static final String CLUB_EVENTS =
      """
      INSERT INTO events (event_id, venue_id, name, starts_at)
      SELECT ? + club_index, 26 + (club_index - 1) % 25, 'Club night ' || club_index,
             TIMESTAMPTZ '2026-12-08 20:20:51.783+00' + ((club_index - 1) / 25) * INTERVAL '7 days'
      FROM generate_series(1, ?) AS club(club_index)
      """;

  // Every section at $80.00, Module 2's illustrative average ticket, as in Module 13's seed.
  static final String ARENA_SECTIONS =
      """
      INSERT INTO sections (event_id, section_code, price, currency)
      SELECT event_id, chr(64 + section_index), 80.00, 'USD'
      FROM generate_series(1, ?) AS arena(event_id), generate_series(1, 20) AS codes(section_index)
      """;

  static final String CLUB_SECTIONS =
      """
      INSERT INTO sections (event_id, section_code, price, currency)
      SELECT event_id, 'A', 80.00, 'USD' FROM generate_series(?, ?) AS club(event_id)
      """;

  // Seat index 0 to 19,999: section chr(65 + index / 1000), number index % 1000 + 1.
  static final String ARENA_SEATS =
      """
      INSERT INTO seats (event_id, seat_label, section_code, seat_number)
      SELECT event_id, chr(65 + seat_index / 1000) || '-' || (seat_index % 1000 + 1),
             chr(65 + seat_index / 1000), seat_index % 1000 + 1
      FROM generate_series(?, ?) AS arena(event_id), generate_series(0, 19999) AS seat(seat_index)
      """;

  static final String CLUB_SEATS =
      """
      INSERT INTO seats (event_id, seat_label, section_code, seat_number)
      SELECT event_id, 'A-' || seat_number, 'A', seat_number
      FROM generate_series(?, ?) AS club(event_id), generate_series(1, 200) AS seat(seat_number)
      """;

  static final String CUSTOMERS =
      """
      INSERT INTO customers (customer_id, customer_ref, created_at) OVERRIDING SYSTEM VALUE
      SELECT customer_index, 'fan_' || customer_index, TIMESTAMPTZ '2026-01-01 00:00:00+00'
      FROM generate_series(1, ?) AS fans(customer_index)
      """;

  // Seats 1 to 800 of every arena section were sold a month before the show. The customer formula
  // spreads the sales over the customers (the first parameter): 40,000,000 over 5,000,000 at the
  // full volume, 8 bookings each.
  static final String SOLD_HOLDS =
      """
      INSERT INTO holds (hold_id, event_id, seat_label, customer_id, state, created_at, expires_at, ended_at)
      SELECT gen_random_uuid(), e.event_id, chr(65 + seat_index / 1000) || '-' || (seat_index % 1000 + 1),
             1 + (e.event_id * 20000 + seat_index) % ?, 'CONFIRMED',
             e.starts_at - INTERVAL '30 days',
             e.starts_at - INTERVAL '30 days' + INTERVAL '300 seconds',
             e.starts_at - INTERVAL '30 days' + INTERVAL '60 seconds'
      FROM events e, generate_series(0, 19999) AS seat(seat_index)
      WHERE e.event_id BETWEEN ? AND ? AND seat_index % 1000 < 800
      """;

  // Seats 801 to 1,000 of every arena section: one hold that expired, then one released (event
  // 1, on sale now, only has the expired one). The first parameter is the number of customers.
  // 7,919 is a prime offset, so a seat's two attempts
  // belong to different fans. These rows are history: none is a live claim.
  static final String ENDED_HOLDS =
      """
      INSERT INTO holds (hold_id, event_id, seat_label, customer_id, state, created_at, expires_at, ended_at)
      SELECT gen_random_uuid(), e.event_id, chr(65 + seat_index / 1000) || '-' || (seat_index % 1000 + 1),
             1 + (e.event_id * 20000 + seat_index + attempt * 7919) % ?,
             CASE attempt WHEN 1 THEN 'EXPIRED' ELSE 'RELEASED' END,
             e.starts_at - INTERVAL '31 days' + attempt * INTERVAL '10 minutes',
             e.starts_at - INTERVAL '31 days' + attempt * INTERVAL '10 minutes' + INTERVAL '300 seconds',
             e.starts_at - INTERVAL '31 days' + attempt * INTERVAL '10 minutes'
               + CASE attempt WHEN 1 THEN INTERVAL '300 seconds' ELSE INTERVAL '30 seconds' END
      FROM events e, generate_series(0, 19999) AS seat(seat_index), generate_series(1, 2) AS tries(attempt)
      WHERE e.event_id BETWEEN ? AND ? AND seat_index % 1000 >= 800 AND (attempt = 1 OR e.event_id > 1)
      """;

  // Event 1's on-sale, now: its 4,000 unsold seats are held by customers 1 to 1,000 (four each,
  // the limit), created 50 ms apart from 10:00:00, each for Module 1's 300 seconds.
  static final String ACTIVE_HOLDS =
      """
      INSERT INTO holds (hold_id, event_id, seat_label, customer_id, state, created_at, expires_at, ended_at)
      SELECT gen_random_uuid(), 1, chr(65 + seat_index / 1000) || '-' || (seat_index % 1000 + 1),
             1 + seat_rank % 1000, 'ACTIVE',
             TIMESTAMPTZ '2026-11-11 10:00:00+00' + seat_rank * INTERVAL '50 milliseconds',
             TIMESTAMPTZ '2026-11-11 10:05:00+00' + seat_rank * INTERVAL '50 milliseconds',
             NULL
      FROM (SELECT seat_index, row_number() OVER (ORDER BY seat_index) - 1 AS seat_rank
            FROM generate_series(0, 19999) AS seat(seat_index)
            WHERE seat_index % 1000 >= 800) AS unsold
      """;

  // One booking per confirmed hold, in one pass over holds (there is no index to batch by yet).
  static final String BOOKINGS =
      """
      INSERT INTO bookings (booking_id, hold_id, event_id, seat_label, customer_id, price, currency, confirmed_at)
      SELECT gen_random_uuid(), hold_id, event_id, seat_label, customer_id, 80.00, 'USD', ended_at
      FROM holds WHERE state = 'CONFIRMED'
      """;

  private SeedSql() {}
}
