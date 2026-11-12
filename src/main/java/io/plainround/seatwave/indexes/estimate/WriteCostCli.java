package io.plainround.seatwave.indexes.estimate;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Counts the index entries each hold write adds under four index sets on {@code holds}, by
 * PostgreSQL's two rules: an insert adds an entry to every index whose predicate the new row
 * matches; an update that changes a column any index uses (as a key, an included column or in its
 * predicate) is not a heap-only tuple (HOT) update, so it adds a new entry to every index whose
 * predicate the new row version matches. It then turns the counts into estimated writes per second
 * from two assumed costs. The counts follow from the rules; the rates are estimates that Section
 * 8's write benchmark replaces with measurements.
 */
public final class WriteCostCli {

  // Assumed database CPU per write transaction and per index entry (Section 13 lists both).
  private static final double BASE_MICROS_PER_WRITE = 100.0;
  private static final double MICROS_PER_INDEX_ENTRY = 15.0;
  // Module 1's reference machine: 4 vCPU; and the module's peak of 13,000 hold writes a second.
  private static final int VCPUS = 4;
  private static final double MICROS_PER_SECOND = 1_000_000.0;
  private static final int PEAK_HOLD_WRITES_PER_SECOND = 13_000;
  private static final String STATE_COLUMN = "state";

  private WriteCostCli() {}

  enum HoldState {
    ACTIVE,
    RELEASED,
    EXPIRED,
    CONFIRMED
  }

  /** One index on holds: the columns it stores and the states its WHERE clause admits. */
  record HoldIndex(String name, Set<String> columns, Set<HoldState> admittedStates) {
    HoldIndex {
      columns = Set.copyOf(columns);
      admittedStates =
          admittedStates.isEmpty()
              ? EnumSet.allOf(HoldState.class)
              : EnumSet.copyOf(admittedStates);
    }

    boolean isPartial() {
      return admittedStates.size() < HoldState.values().length;
    }

    boolean admits(HoldState state) {
      return admittedStates.contains(state);
    }

    boolean uses(Set<String> changedColumns) {
      // A predicate on state means the index uses state, even if state is not one of its keys.
      return changedColumns.stream()
          .anyMatch(
              column -> columns.contains(column) || (isPartial() && column.equals(STATE_COLUMN)));
    }
  }

  /** One kind of hold write: the state the new row version has, and the columns it changes. */
  enum HoldWrite {
    INSERT_ACTIVE(HoldState.ACTIVE, Set.of()),
    CONFIRM(HoldState.CONFIRMED, Set.of(STATE_COLUMN, "ended_at")),
    RELEASE(HoldState.RELEASED, Set.of(STATE_COLUMN, "ended_at")),
    EXPIRE(HoldState.EXPIRED, Set.of(STATE_COLUMN, "ended_at"));

    private final HoldState newState;
    private final Set<String> changedColumns;

    HoldWrite(HoldState newState, Set<String> changedColumns) {
      this.newState = newState;
      this.changedColumns = changedColumns;
    }

    boolean isInsert() {
      return changedColumns.isEmpty();
    }
  }

  private static final HoldIndex HOLDS_PKEY =
      new HoldIndex("holds_pkey", Set.of("hold_id"), Set.of());
  private static final HoldIndex ONE_CLAIM_PER_SEAT =
      new HoldIndex(
          "one_claim_per_seat",
          Set.of("event_id", "seat_label"),
          Set.of(HoldState.ACTIVE, HoldState.CONFIRMED));
  private static final HoldIndex ACTIVE_BY_CUSTOMER =
      new HoldIndex("active_holds_by_customer", Set.of("customer_id"), Set.of(HoldState.ACTIVE));
  private static final HoldIndex HOLD_IDENTITY =
      new HoldIndex(
          "hold_identity", Set.of("hold_id", "event_id", "seat_label", "customer_id"), Set.of());
  private static final HoldIndex ACTIVE_BY_EXPIRY =
      new HoldIndex("active_holds_by_expiry", Set.of("expires_at"), Set.of(HoldState.ACTIVE));
  private static final HoldIndex BY_CUSTOMER_ALL =
      new HoldIndex("holds_by_customer_all", Set.of("customer_id", "created_at"), Set.of());

  private record IndexSet(String label, List<HoldIndex> indexes) {}

  private static final List<IndexSet> INDEX_SETS =
      List.of(
          new IndexSet("0 secondary (primary key only)", List.of(HOLDS_PKEY)),
          new IndexSet(
              "2 secondary (Module 13's two)",
              List.of(HOLDS_PKEY, ONE_CLAIM_PER_SEAT, ACTIVE_BY_CUSTOMER)),
          new IndexSet(
              "5 secondary (every candidate)",
              List.of(
                  HOLDS_PKEY,
                  ONE_CLAIM_PER_SEAT,
                  ACTIVE_BY_CUSTOMER,
                  HOLD_IDENTITY,
                  ACTIVE_BY_EXPIRY,
                  BY_CUSTOMER_ALL)),
          new IndexSet(
              "final (4 secondary)",
              List.of(
                  HOLDS_PKEY,
                  ONE_CLAIM_PER_SEAT,
                  ACTIVE_BY_CUSTOMER,
                  HOLD_IDENTITY,
                  ACTIVE_BY_EXPIRY)));

  /** Prints, per index set, the entries each write adds and the estimated rates. */
  public static void main(String[] arguments) {
    System.out.println("== index entries added per hold write (counted from PostgreSQL's rules)");
    System.out.println(
        "  index set                         insert  confirm  release  expire  updates HOT?");
    for (IndexSet indexSet : INDEX_SETS) {
      System.out.printf(
          Locale.ROOT,
          "  %-33s %6d %8d %8d %7d  %s%n",
          indexSet.label(),
          entriesAdded(HoldWrite.INSERT_ACTIVE, indexSet),
          entriesAdded(HoldWrite.CONFIRM, indexSet),
          entriesAdded(HoldWrite.RELEASE, indexSet),
          entriesAdded(HoldWrite.EXPIRE, indexSet),
          isHot(HoldWrite.CONFIRM, indexSet) ? "yes, if the page has room" : "no");
    }
    System.out.printf(
        Locale.ROOT,
        "== estimated rates on %d vCPU (assumed %.0f us per write + %.0f us per index entry)%n",
        VCPUS,
        BASE_MICROS_PER_WRITE,
        MICROS_PER_INDEX_ENTRY);
    System.out.println(
        "  index set                         inserts/s  confirms/s  cores for 13,000 inserts/s");
    for (IndexSet indexSet : INDEX_SETS) {
      double insertMicros = microsPerWrite(HoldWrite.INSERT_ACTIVE, indexSet);
      System.out.printf(
          Locale.ROOT,
          "  %-33s %,9.0f %,11.0f %10.2f%n",
          indexSet.label(),
          VCPUS * MICROS_PER_SECOND / insertMicros,
          VCPUS * MICROS_PER_SECOND / microsPerWrite(HoldWrite.CONFIRM, indexSet),
          PEAK_HOLD_WRITES_PER_SECOND * insertMicros / MICROS_PER_SECOND);
    }
  }

  private static boolean isHot(HoldWrite write, IndexSet indexSet) {
    return !write.isInsert()
        && indexSet.indexes().stream().noneMatch(index -> index.uses(write.changedColumns));
  }

  private static long entriesAdded(HoldWrite write, IndexSet indexSet) {
    if (isHot(write, indexSet)) {
      // A HOT update writes the new version on the same page and adds no index entry at all.
      return 0;
    }
    return indexSet.indexes().stream().filter(index -> index.admits(write.newState)).count();
  }

  private static double microsPerWrite(HoldWrite write, IndexSet indexSet) {
    return BASE_MICROS_PER_WRITE + entriesAdded(write, indexSet) * MICROS_PER_INDEX_ENTRY;
  }
}
