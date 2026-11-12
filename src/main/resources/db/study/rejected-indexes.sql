-- Indexes the study builds, measures and rejects (Section 8's report and Lab 3). None of them
-- belongs in the final schema; each is dropped again after its measurement.
SET search_path TO seatwave_study;

-- Lab 2: the seat-map index in the wrong column order. Leading with section_code means the
-- equality on section_code = 'A' matches section A of every event (3,000,000 entries).
CREATE INDEX CONCURRENTLY seats_wrong_order ON seats (section_code, seat_number, event_id);

-- Lab 2: range before equality on events.
CREATE INDEX CONCURRENTLY events_wrong_order ON events (starts_at, venue_id);

-- Rejected: a hold-history page per customer. Every hold write adds an entry (60 million
-- entries, about 1,800 MB) for a page nobody listed.
CREATE INDEX CONCURRENTLY holds_by_customer_all ON holds (customer_id, created_at);

-- Rejected: expires_at for every hold, when the sweeper reads only active ones.
CREATE INDEX CONCURRENTLY holds_by_expiry_all ON holds (expires_at);

-- Rejected: Q3 made covering. It carries every column Q3 returns, booking_id among them, so Q3
-- could be an index-only scan; it saves about 8 heap visits on a page read rarely, and makes the
-- index more than twice as large and its entries wider on a table written at every confirm.
CREATE INDEX CONCURRENTLY bookings_by_customer_date_covering
  ON bookings (customer_id, confirmed_at) INCLUDE (booking_id, event_id, seat_label, price);
