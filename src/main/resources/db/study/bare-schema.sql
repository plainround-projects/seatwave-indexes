-- The SIMPLE starting point of Module 14's study: Module 13's seven tables after its three
-- migrations (V1 to V3, so sections has its version column), with the same names, columns and
-- check constraints, but with only their primary keys. Module 13's unique constraints, its two
-- rule indexes, its foreign keys and its hold-limit trigger are left out on purpose, so the study
-- can add each index back and measure what it buys and what it costs. final-indexes.sql puts
-- back every one the listed queries need.
CREATE SCHEMA seatwave_study;
SET search_path TO seatwave_study;

CREATE TABLE venues (
  venue_id BIGINT NOT NULL,
  name VARCHAR(120) NOT NULL,
  CONSTRAINT venues_pkey PRIMARY KEY (venue_id),
  CONSTRAINT venue_name_not_blank CHECK (btrim(name) <> '')
);

CREATE TABLE events (
  event_id BIGINT NOT NULL,
  venue_id BIGINT NOT NULL,
  name VARCHAR(120) NOT NULL,
  starts_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT events_pkey PRIMARY KEY (event_id),
  CONSTRAINT event_name_not_blank CHECK (btrim(name) <> '')
);

CREATE TABLE sections (
  event_id BIGINT NOT NULL,
  section_code VARCHAR(4) NOT NULL,
  price NUMERIC(12, 2) NOT NULL,
  currency CHAR(3) NOT NULL,
  -- Module 13's V3: the version of optimistic price edits; no study query reads it.
  version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT sections_pkey PRIMARY KEY (event_id, section_code),
  CONSTRAINT section_code_format CHECK (section_code ~ '^[A-Z]{1,4}$'),
  CONSTRAINT price_not_negative CHECK (price >= 0),
  CONSTRAINT currency_format CHECK (currency ~ '^[A-Z]{3}$'),
  CONSTRAINT version_not_negative CHECK (version >= 0)
);

-- The primary key's first column is event_id, so even here a seat query for one event reads
-- only that event's part of the key (Section 5a): about 20,000 entries for an arena, not 50.5 million.
CREATE TABLE seats (
  event_id BIGINT NOT NULL,
  seat_label VARCHAR(16) NOT NULL,
  section_code VARCHAR(4) NOT NULL,
  seat_number INT NOT NULL,
  CONSTRAINT seats_pkey PRIMARY KEY (event_id, seat_label),
  CONSTRAINT seat_number_positive CHECK (seat_number > 0),
  CONSTRAINT seat_label_matches CHECK (seat_label = section_code || '-' || seat_number)
);

CREATE TABLE customers (
  customer_id BIGINT GENERATED ALWAYS AS IDENTITY,
  customer_ref VARCHAR(64) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT customers_pkey PRIMARY KEY (customer_id),
  CONSTRAINT customer_ref_format CHECK (customer_ref ~ '^[A-Za-z0-9_-]{1,64}$')
);

CREATE TABLE holds (
  hold_id UUID NOT NULL,
  event_id BIGINT NOT NULL,
  seat_label VARCHAR(16) NOT NULL,
  customer_id BIGINT NOT NULL,
  state VARCHAR(10) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  ended_at TIMESTAMPTZ,
  CONSTRAINT holds_pkey PRIMARY KEY (hold_id),
  CONSTRAINT known_hold_state CHECK (state IN ('ACTIVE', 'RELEASED', 'EXPIRED', 'CONFIRMED')),
  CONSTRAINT hold_expires_after_it_starts CHECK (expires_at > created_at),
  CONSTRAINT only_active_holds_are_open CHECK ((state = 'ACTIVE') = (ended_at IS NULL)),
  CONSTRAINT hold_ends_after_it_starts CHECK (ended_at IS NULL OR ended_at >= created_at)
);

CREATE TABLE bookings (
  booking_id UUID NOT NULL,
  hold_id UUID NOT NULL,
  event_id BIGINT NOT NULL,
  seat_label VARCHAR(16) NOT NULL,
  customer_id BIGINT NOT NULL,
  price NUMERIC(12, 2) NOT NULL,
  currency CHAR(3) NOT NULL,
  confirmed_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT bookings_pkey PRIMARY KEY (booking_id),
  CONSTRAINT paid_price_not_negative CHECK (price >= 0),
  CONSTRAINT paid_currency_format CHECK (currency ~ '^[A-Z]{3}$')
);
