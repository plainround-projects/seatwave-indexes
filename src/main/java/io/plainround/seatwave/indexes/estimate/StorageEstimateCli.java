package io.plainround.seatwave.indexes.estimate;

import java.util.List;
import java.util.Locale;

/**
 * Prints the estimates this module uses, from stated inputs, so no number in the text is typed by
 * hand: the pages and megabytes of every SeatWave table at study volume, the size and height of
 * every index, and the pages, rows and time of the five study queries with and without indexes.
 * These are estimates from PostgreSQL's page layout and two assumed costs, not measurements; the
 * study in Section 8 measures the real values with {@code EXPLAIN (ANALYZE, BUFFERS)}.
 */
public final class StorageEstimateCli {

  // PostgreSQL's page: 8,192 bytes, a 24-byte page header, a 4-byte line pointer per item, and a
  // 16-byte B-tree special area on index pages (documentation, Database Page Layout).
  private static final int PAGE_BYTES = 8_192;
  private static final int PAGE_HEADER_BYTES = 24;
  private static final int LINE_POINTER_BYTES = 4;
  private static final int BTREE_SPECIAL_BYTES = 16;
  // B-tree leaves are built to fillfactor 90 (documented default); internal pages to 70, the
  // value in PostgreSQL's source (an assumption here, logged in Section 13).
  private static final double LEAF_FILL = 0.90;
  private static final double INTERNAL_FILL = 0.70;
  private static final double BYTES_PER_MB = 1_048_576.0;
  // The two assumed costs behind every time below (Section 13 lists them): touching a page that
  // is already in memory, and examining one row or index entry.
  private static final double MICROS_PER_CACHED_PAGE = 0.5;
  private static final double MICROS_PER_ROW = 0.05;
  // Module 1's volume: 3,000 IOPS; an assumed 125 MB/s for large sequential reads.
  private static final int VOLUME_IOPS = 3_000;
  private static final double SEQUENTIAL_MB_PER_SECOND = 125.0;
  private static final double MICROS_PER_SECOND = 1_000_000.0;
  private static final double MICROS_PER_MILLI = 1_000.0;
  // Module 1's disk price, about $0.08 per GiB-month (marked uncertain in Module 1).
  private static final double DISK_USD_PER_GB_MONTH = 0.08;

  // Study volume: 2,500 arena events (20 sections of 1,000 seats) and 2,500 club nights (one
  // section of 200 seats), 50 venues of 100 events each, one event a week per venue.
  private static final long ARENA_EVENTS = 2_500;
  private static final long CLUB_EVENTS = 2_500;
  private static final long ARENA_SEATS = 20_000;
  private static final long CLUB_SEATS = 200;
  private static final long SEATS_PER_SECTION = 1_000;
  private static final long EVENTS = ARENA_EVENTS + CLUB_EVENTS;
  private static final long SEATS = ARENA_EVENTS * ARENA_SEATS + CLUB_EVENTS * CLUB_SEATS;
  private static final long SECTION_A_SEATS_ALL_EVENTS =
      ARENA_EVENTS * SEATS_PER_SECTION + CLUB_EVENTS * CLUB_SEATS;
  private static final long HOLDS = 60_000_000;
  // 800 of each arena section's 1,000 seats sold: 2,500 x 20 x 800.
  private static final long CONFIRMED_HOLDS = 40_000_000;
  // Event 1 is on sale now: its remaining 200 seats per section are held.
  private static final long ACTIVE_HOLDS = 4_000;
  private static final long CLAIMS = CONFIRMED_HOLDS + ACTIVE_HOLDS;
  private static final long BOOKINGS = CONFIRMED_HOLDS;
  private static final long CUSTOMERS = 5_000_000;
  private static final long ARENA_SECTIONS = 20;
  private static final long SECTIONS = ARENA_EVENTS * ARENA_SECTIONS + CLUB_EVENTS;
  private static final long VENUES = 50;
  private static final long EVENTS_PER_WEEK = 50;
  private static final long WINDOW_WEEKS = 4;
  // What one call of each query returns.
  private static final long CUSTOMER_ACTIVE_HOLDS = 4;
  private static final long CUSTOMER_BOOKINGS = BOOKINGS / CUSTOMERS;
  private static final long VENUE_EVENTS_IN_WINDOW = WINDOW_WEEKS;
  private static final long SWEEP_BATCH = 500;

  private StorageEstimateCli() {}

  /** One table: its rows and the bytes of one row on the page, header and padding included. */
  record Table(String name, long rows, int tupleBytes) {
    long rowsPerPage() {
      return (PAGE_BYTES - PAGE_HEADER_BYTES) / (tupleBytes + LINE_POINTER_BYTES);
    }

    long pages() {
      return ceilDiv(rows, rowsPerPage());
    }

    double megabytes() {
      return pages() * PAGE_BYTES / BYTES_PER_MB;
    }
  }

  /** One B-tree: its entries and the bytes of one entry (8-byte header plus padded key). */
  record Index(String name, long entries, int entryBytes) {
    long entriesPerLeaf() {
      return (long) (usableIndexBytes() * LEAF_FILL / (entryBytes + LINE_POINTER_BYTES));
    }

    long fanOut() {
      return (long) (usableIndexBytes() * INTERNAL_FILL / (entryBytes + LINE_POINTER_BYTES));
    }

    long leaves() {
      return Math.max(1, ceilDiv(entries, entriesPerLeaf()));
    }

    int height() {
      int levels = 1;
      for (long pagesOnLevel = leaves();
          pagesOnLevel > 1;
          pagesOnLevel = ceilDiv(pagesOnLevel, fanOut())) {
        levels++;
      }
      return levels;
    }

    long pages() {
      long total = 1; // the metapage
      for (long pagesOnLevel = leaves(); ; pagesOnLevel = ceilDiv(pagesOnLevel, fanOut())) {
        total += pagesOnLevel;
        if (pagesOnLevel == 1) {
          return total;
        }
      }
    }

    double megabytes() {
      return pages() * PAGE_BYTES / BYTES_PER_MB;
    }

    /** Pages of one descent plus the further leaves a range of {@code matching} entries spans. */
    long rangePages(long matching) {
      return height() - 1 + Math.max(1, ceilDiv(matching, entriesPerLeaf()));
    }
  }

  /** One way of running one query: the pages it touches and the rows it examines. */
  record Plan(String query, String plan, long pages, long rows) {
    double millis() {
      return (pages * MICROS_PER_CACHED_PAGE + rows * MICROS_PER_ROW) / MICROS_PER_MILLI;
    }
  }

  // Bytes of one row on its page: a 24-byte header, then each column at its alignment, rounded
  // up to 8 (Section 5a works out seats; the others follow the same rule).
  private static final int SEAT_ROW_BYTES = 48;
  private static final int HOLD_ROW_BYTES = 104;
  private static final int BOOKING_ROW_BYTES = 104;
  private static final int EVENT_ROW_BYTES = 72;
  private static final int CUSTOMER_ROW_BYTES = 56;
  private static final int SECTION_ROW_BYTES = 48;
  private static final int VENUE_ROW_BYTES = 40;
  // Bytes of one index entry: an 8-byte index tuple header plus the key columns, rounded up to 8.
  private static final int ONE_BIGINT_ENTRY_BYTES = 16;
  private static final int TWO_COLUMN_ENTRY_BYTES = 24;
  private static final int COVERING_SEAT_ENTRY_BYTES = 32;
  // hold_identity: header 8, hold_id 16, event_id 8, seat_label about 6, customer_id 8, aligned.
  private static final int WIDE_ENTRY_BYTES = 48;
  // The rejected covering bookings index carries all five columns Q3 returns: header 8,
  // customer_id 8, confirmed_at 8, booking_id 16, event_id 8, seat_label about 6, price about 5,
  // 59 bytes rounded up to 64.
  private static final int COVERING_BOOKING_ENTRY_BYTES = 64;
  private static final double MB_PER_GB = 1_024.0;

  static final Table SEATS_TABLE = new Table("seats", SEATS, SEAT_ROW_BYTES);
  static final Table HOLDS_TABLE = new Table("holds", HOLDS, HOLD_ROW_BYTES);
  static final Table BOOKINGS_TABLE = new Table("bookings", BOOKINGS, BOOKING_ROW_BYTES);
  static final Table EVENTS_TABLE = new Table("events", EVENTS, EVENT_ROW_BYTES);
  static final Table CUSTOMERS_TABLE = new Table("customers", CUSTOMERS, CUSTOMER_ROW_BYTES);
  static final Table SECTIONS_TABLE = new Table("sections", SECTIONS, SECTION_ROW_BYTES);
  static final Table VENUES_TABLE = new Table("venues", VENUES, VENUE_ROW_BYTES);

  static final Index SEATS_PKEY = new Index("seats_pkey", SEATS, TWO_COLUMN_ENTRY_BYTES);
  static final Index ONE_SEAT_PER_NUMBER =
      new Index("one_seat_per_number (covering)", SEATS, COVERING_SEAT_ENTRY_BYTES);
  static final Index SEATS_WRONG_ORDER =
      new Index("seats (section_code, seat_number, event_id)", SEATS, TWO_COLUMN_ENTRY_BYTES);
  static final Index HOLDS_PKEY = new Index("holds_pkey", HOLDS, TWO_COLUMN_ENTRY_BYTES);
  static final Index HOLD_IDENTITY = new Index("hold_identity", HOLDS, WIDE_ENTRY_BYTES);
  static final Index ONE_CLAIM_PER_SEAT =
      new Index("one_claim_per_seat (partial)", CLAIMS, TWO_COLUMN_ENTRY_BYTES);
  static final Index ACTIVE_BY_CUSTOMER =
      new Index("active_holds_by_customer (partial)", ACTIVE_HOLDS, ONE_BIGINT_ENTRY_BYTES);
  static final Index ACTIVE_BY_EXPIRY =
      new Index("active_holds_by_expiry (partial)", ACTIVE_HOLDS, ONE_BIGINT_ENTRY_BYTES);
  static final Index HOLDS_BY_CUSTOMER_ALL =
      new Index("holds_by_customer_all (rejected)", HOLDS, TWO_COLUMN_ENTRY_BYTES);
  static final Index EXPIRY_NOT_PARTIAL =
      new Index("holds (expires_at), not partial (rejected)", HOLDS, ONE_BIGINT_ENTRY_BYTES);
  static final Index BOOKINGS_PKEY = new Index("bookings_pkey", BOOKINGS, TWO_COLUMN_ENTRY_BYTES);
  static final Index ONE_BOOKING_PER_HOLD =
      new Index("one_booking_per_hold", BOOKINGS, TWO_COLUMN_ENTRY_BYTES);
  static final Index ONE_BOOKING_PER_SEAT =
      new Index("one_booking_per_seat", BOOKINGS, TWO_COLUMN_ENTRY_BYTES);
  static final Index BOOKINGS_BY_CUSTOMER =
      new Index("bookings_by_customer_date", BOOKINGS, TWO_COLUMN_ENTRY_BYTES);
  static final Index BOOKINGS_COVERING =
      new Index(
          "bookings_by_customer_date covering (rejected)", BOOKINGS, COVERING_BOOKING_ENTRY_BYTES);
  static final Index EVENTS_PKEY = new Index("events_pkey", EVENTS, ONE_BIGINT_ENTRY_BYTES);
  static final Index EVENTS_BY_VENUE_DATE =
      new Index("events_by_venue_date", EVENTS, TWO_COLUMN_ENTRY_BYTES);
  static final Index CUSTOMERS_PKEY =
      new Index("customers_pkey", CUSTOMERS, ONE_BIGINT_ENTRY_BYTES);
  static final Index ONE_CUSTOMER_PER_REF =
      new Index("one_customer_per_ref", CUSTOMERS, TWO_COLUMN_ENTRY_BYTES);
  static final Index SECTIONS_PKEY = new Index("sections_pkey", SECTIONS, TWO_COLUMN_ENTRY_BYTES);
  static final Index VENUES_PKEY = new Index("venues_pkey", VENUES, ONE_BIGINT_ENTRY_BYTES);

  /** Prints the tables, the indexes and the query plans. */
  public static void main(String[] arguments) {
    printTables();
    printIndexes();
    printPlans();
    printDiskFacts();
  }

  private static void printTables() {
    System.out.println("== tables at study volume (estimated; 1 MB = 1,048,576 bytes)");
    System.out.println(
        "  table        rows          bytes/row rows/page  pages      MB       scan cached  scan from SSD");
    for (Table table :
        List.of(
            SEATS_TABLE,
            HOLDS_TABLE,
            BOOKINGS_TABLE,
            CUSTOMERS_TABLE,
            SECTIONS_TABLE,
            EVENTS_TABLE,
            VENUES_TABLE)) {
      Plan scan = seqScan("scan", table);
      System.out.printf(
          Locale.ROOT,
          "  %-12s %,13d %9d %9d %,10d %,8.0f %10.1f ms %10.1f s%n",
          table.name(),
          table.rows(),
          table.tupleBytes(),
          table.rowsPerPage(),
          table.pages(),
          table.megabytes(),
          scan.millis(),
          table.megabytes() / SEQUENTIAL_MB_PER_SECOND);
    }
  }

  private static void printIndexes() {
    System.out.println("== B-tree indexes (estimated)");
    System.out.println(
        "  index                                              entries     bytes/entry per leaf fan-out leaves    height MB");
    for (Index index :
        List.of(
            SEATS_PKEY,
            ONE_SEAT_PER_NUMBER,
            SEATS_WRONG_ORDER,
            HOLDS_PKEY,
            HOLD_IDENTITY,
            ONE_CLAIM_PER_SEAT,
            ACTIVE_BY_CUSTOMER,
            ACTIVE_BY_EXPIRY,
            HOLDS_BY_CUSTOMER_ALL,
            EXPIRY_NOT_PARTIAL,
            BOOKINGS_PKEY,
            ONE_BOOKING_PER_HOLD,
            ONE_BOOKING_PER_SEAT,
            BOOKINGS_BY_CUSTOMER,
            BOOKINGS_COVERING,
            EVENTS_PKEY,
            EVENTS_BY_VENUE_DATE,
            CUSTOMERS_PKEY,
            ONE_CUSTOMER_PER_REF,
            SECTIONS_PKEY,
            VENUES_PKEY)) {
      System.out.printf(
          Locale.ROOT,
          "  %-50s %,12d %6d %8d %7d %,9d %3d %,9.1f%n",
          index.name(),
          index.entries(),
          index.entryBytes() + LINE_POINTER_BYTES,
          index.entriesPerLeaf(),
          index.fanOut(),
          index.leaves(),
          index.height(),
          index.megabytes());
    }
    double finalSecondaryMb =
        ONE_SEAT_PER_NUMBER.megabytes()
            + HOLD_IDENTITY.megabytes()
            + ONE_CLAIM_PER_SEAT.megabytes()
            + ACTIVE_BY_CUSTOMER.megabytes()
            + ACTIVE_BY_EXPIRY.megabytes()
            + ONE_BOOKING_PER_HOLD.megabytes()
            + ONE_BOOKING_PER_SEAT.megabytes()
            + BOOKINGS_BY_CUSTOMER.megabytes()
            + EVENTS_BY_VENUE_DATE.megabytes()
            + ONE_CUSTOMER_PER_REF.megabytes();
    System.out.printf(
        Locale.ROOT,
        "  final secondary indexes: %,.0f MB, about $%.2f a month of disk at $%.2f per GB-month%n",
        finalSecondaryMb,
        finalSecondaryMb / MB_PER_GB * DISK_USD_PER_GB_MONTH,
        DISK_USD_PER_GB_MONTH);
  }

  private static void printPlans() {
    System.out.println(
        "== the five queries: pages touched, rows examined, estimated time with a warm cache");
    Plan seatsByPrimaryKey =
        new Plan(
            "",
            "",
            SEATS_PKEY.rangePages(ARENA_SEATS) + ceilDiv(ARENA_SEATS, SEATS_TABLE.rowsPerPage()),
            ARENA_SEATS);
    Plan seatsCovering =
        new Plan("", "", ONE_SEAT_PER_NUMBER.rangePages(SEATS_PER_SECTION), SEATS_PER_SECTION);
    Plan claimProbes =
        new Plan("", "", SEATS_PER_SECTION * (ONE_CLAIM_PER_SEAT.height() + 1), SEATS_PER_SECTION);
    Plan holdsScan = seqScan("", HOLDS_TABLE);
    List<Plan> plans =
        List.of(
            sum(
                "Q1 seat map",
                "before: seats_pkey on event_id + seq scan of holds",
                seatsByPrimaryKey,
                holdsScan),
            sum(
                "Q1 seat map",
                "step 1: + one_claim_per_seat (seats still by seats_pkey)",
                seatsByPrimaryKey,
                claimProbes),
            sum(
                "Q1 seat map",
                "after: one_seat_per_number index-only + claim probes",
                seatsCovering,
                claimProbes),
            new Plan(
                "Q1 seats part",
                "wrong order (section_code, seat_number, event_id), range read",
                SEATS_WRONG_ORDER.rangePages(SECTION_A_SEATS_ALL_EVENTS),
                SECTION_A_SEATS_ALL_EVENTS),
            new Plan(
                "Q1 seats part",
                "wrong order, PostgreSQL 18 skip scan over seat_number",
                SEATS_PER_SECTION * SEATS_WRONG_ORDER.height(),
                SEATS_PER_SECTION),
            new Plan(
                "Q1 seats part",
                "right order (event_id, section_code, seat_number)",
                seatsCovering.pages(),
                seatsCovering.rows()),
            relabel(holdsScan, "Q2 active holds", "before: seq scan of holds"),
            new Plan(
                "Q2 active holds",
                "after: active_holds_by_customer",
                ACTIVE_BY_CUSTOMER.height() + CUSTOMER_ACTIVE_HOLDS,
                CUSTOMER_ACTIVE_HOLDS),
            relabel(seqScan("", BOOKINGS_TABLE), "Q3 bookings", "before: seq scan of bookings"),
            new Plan(
                "Q3 bookings",
                "after: bookings_by_customer_date",
                BOOKINGS_BY_CUSTOMER.height() + CUSTOMER_BOOKINGS,
                CUSTOMER_BOOKINGS),
            relabel(seqScan("", EVENTS_TABLE), "Q4 events", "before: seq scan of events"),
            new Plan(
                "Q4 events",
                "after: events_by_venue_date (equality, then range)",
                EVENTS_BY_VENUE_DATE.rangePages(VENUE_EVENTS_IN_WINDOW) + VENUE_EVENTS_IN_WINDOW,
                VENUE_EVENTS_IN_WINDOW),
            new Plan(
                "Q4 events",
                "wrong order (starts_at, venue_id): reads the whole window",
                EVENTS_BY_VENUE_DATE.rangePages(EVENTS_PER_WEEK * WINDOW_WEEKS)
                    + VENUE_EVENTS_IN_WINDOW,
                EVENTS_PER_WEEK * WINDOW_WEEKS),
            relabel(holdsScan, "Q5 expired holds", "before: seq scan of holds"),
            new Plan(
                "Q5 expired holds",
                "after: active_holds_by_expiry, first 500",
                ACTIVE_BY_EXPIRY.rangePages(SWEEP_BATCH) + SWEEP_BATCH,
                SWEEP_BATCH));
    for (Plan plan : plans) {
      System.out.printf(
          Locale.ROOT,
          "  %-16s %-62s pages %,9d  rows %,12d  %,10.3f ms%n",
          plan.query(),
          plan.plan(),
          plan.pages(),
          plan.rows(),
          plan.millis());
    }
  }

  private static void printDiskFacts() {
    double randomMbPerSecond = VOLUME_IOPS * (double) PAGE_BYTES / BYTES_PER_MB;
    System.out.printf(
        Locale.ROOT,
        "== the disk (Module 1's volume)%n"
            + "  random page reads: %,d IOPS x 8 kB = %.1f MB/s; sequential (assumed): %.0f MB/s%n"
            + "  reading all of holds page by page at random: %,.0f s; as one sequential scan: %.0f s%n",
        VOLUME_IOPS,
        randomMbPerSecond,
        SEQUENTIAL_MB_PER_SECOND,
        HOLDS_TABLE.pages() / (double) VOLUME_IOPS,
        HOLDS_TABLE.megabytes() / SEQUENTIAL_MB_PER_SECOND);
    System.out.printf(
        Locale.ROOT,
        "  one cached page %.1f us, one row %.2f us (assumed): a core scans %,.0f rows/s%n",
        MICROS_PER_CACHED_PAGE,
        MICROS_PER_ROW,
        MICROS_PER_SECOND / MICROS_PER_ROW);
  }

  private static Plan seqScan(String query, Table table) {
    return new Plan(query, "seq scan of " + table.name(), table.pages(), table.rows());
  }

  private static Plan sum(String query, String label, Plan first, Plan second) {
    return new Plan(query, label, first.pages() + second.pages(), first.rows() + second.rows());
  }

  private static Plan relabel(Plan plan, String query, String label) {
    return new Plan(query, label, plan.pages(), plan.rows());
  }

  private static long ceilDiv(long dividend, long divisor) {
    return Math.ceilDiv(dividend, divisor);
  }

  private static double usableIndexBytes() {
    return PAGE_BYTES - PAGE_HEADER_BYTES - BTREE_SPECIAL_BYTES;
  }
}
