-- The PRODUCTION index set, chosen from the listed queries (catalog/ListedQuery.java) and
-- justified row by row in index-ledger.txt. Run with autocommit, one statement at a time:
-- CREATE INDEX CONCURRENTLY builds without blocking writes, and cannot run inside a transaction.
-- If a concurrent build fails it leaves an INVALID index that still costs every write: drop it
-- and run the statement again (Section 9).
SET search_path TO seatwave_study;

-- Q1, seats half. Module 13's rule one_seat_per_number, kept as a unique index under the same
-- name and widened with INCLUDE (seat_label) so the seat map is answered from the index alone.
-- seats is written once per event set-up and read 20,000 times a second at peak: a covering index
-- costs almost nothing in writes here.
CREATE UNIQUE INDEX CONCURRENTLY one_seat_per_number
  ON seats (event_id, section_code, seat_number) INCLUDE (seat_label);

-- Q1, claims half, and R1. Module 13's invariant index, unchanged. The seat map's join repeats
-- the predicate word for word, because PostgreSQL uses a partial index only for a query whose
-- WHERE clause visibly implies the index's WHERE clause.
CREATE UNIQUE INDEX CONCURRENTLY one_claim_per_seat
  ON holds (event_id, seat_label) WHERE state IN ('ACTIVE', 'CONFIRMED');

-- Q2 and the hold-limit trigger's count. Module 13's, unchanged: 4,000 entries, not 60 million.
CREATE INDEX CONCURRENTLY active_holds_by_customer
  ON holds (customer_id) WHERE state = 'ACTIVE';

-- R3. The target of Module 13's composite foreign key booking_matches_its_hold. The largest
-- index of the final set (Section 8), kept because it guards Module 13's rule.
CREATE UNIQUE INDEX CONCURRENTLY hold_identity
  ON holds (hold_id, event_id, seat_label, customer_id);

-- Q5, new in Module 14. The expiry sweeper reads the oldest expired active holds; only active
-- holds are indexed, and expires_at arrives roughly in order, so inserts touch the right edge.
CREATE INDEX CONCURRENTLY active_holds_by_expiry
  ON holds (expires_at) WHERE state = 'ACTIVE';

-- R4. Module 13's two booking rules.
CREATE UNIQUE INDEX CONCURRENTLY one_booking_per_hold ON bookings (hold_id);
CREATE UNIQUE INDEX CONCURRENTLY one_booking_per_seat ON bookings (event_id, seat_label);

-- Q3, new. Equality on customer_id first, then the range on confirmed_at, which also gives the
-- ORDER BY for free (read backwards). Not covering: see index-ledger.txt.
CREATE INDEX CONCURRENTLY bookings_by_customer_date ON bookings (customer_id, confirmed_at);

-- Q4, new. Equality (venue_id) before range (starts_at).
CREATE INDEX CONCURRENTLY events_by_venue_date ON events (venue_id, starts_at);

-- R5. Module 13's rule one_customer_per_ref.
CREATE UNIQUE INDEX CONCURRENTLY one_customer_per_ref ON customers (customer_ref);

-- Module 13 declared these four as constraints; attaching each finished index keeps the
-- constraint names without a second build. This takes a brief lock, not a scan.
ALTER TABLE holds ADD CONSTRAINT hold_identity UNIQUE USING INDEX hold_identity;
ALTER TABLE bookings ADD CONSTRAINT one_booking_per_hold UNIQUE USING INDEX one_booking_per_hold;
ALTER TABLE bookings ADD CONSTRAINT one_booking_per_seat UNIQUE USING INDEX one_booking_per_seat;
ALTER TABLE customers ADD CONSTRAINT one_customer_per_ref UNIQUE USING INDEX one_customer_per_ref;

-- Fresh statistics, so the planner costs the new indexes from today's data (failure mode 4).
ANALYZE seats;
ANALYZE holds;
ANALYZE bookings;
ANALYZE events;
ANALYZE customers;
