package io.plainround.seatwave.indexes.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class IndexLedgerTest {

  private static final String SCHEMA =
      """
      CREATE TABLE holds (hold_id UUID, CONSTRAINT holds_pkey PRIMARY KEY (hold_id));
      -- CREATE INDEX commented_out ON holds (state);
      CREATE INDEX CONCURRENTLY active_holds_by_customer ON holds (customer_id) WHERE state = 'ACTIVE';
      """;

  @Test
  void theFinalSchemaHasNoIndexWithoutAListedUserAndAMeasuredWriteCost() throws IOException {
    Path study = Path.of("src/main/resources/db/study");
    String schema =
        Files.readString(study.resolve("bare-schema.sql"), StandardCharsets.UTF_8)
            + Files.readString(study.resolve("final-indexes.sql"), StandardCharsets.UTF_8);

    var problems =
        IndexLedger.check(
            schema, Files.readString(study.resolve("index-ledger.txt"), StandardCharsets.UTF_8));

    assertThat(problems).isEmpty();
  }

  @Test
  void failsAnIndexThatHasNoLedgerRow() {
    String ledger = "holds_pkey | R2 | HOLDS_FINAL | find a hold by id";

    var problems = IndexLedger.check(SCHEMA, ledger);

    assertThat(problems)
        .containsExactly(new LedgerProblem.UnrecordedIndex("active_holds_by_customer"));
  }

  @Test
  void failsARowWithNoUser() {
    String ledger =
        """
        holds_pkey | R2 | HOLDS_FINAL | find a hold by id
        active_holds_by_customer |  | HOLDS_FINAL | nobody reads it
        """;

    var problems = IndexLedger.check(SCHEMA, ledger);

    assertThat(problems)
        .containsExactly(new LedgerProblem.IndexWithoutUser("active_holds_by_customer"));
  }

  @Test
  void failsAUserThatIsNotAListedQuery() {
    String ledger =
        """
        holds_pkey | R2 | HOLDS_FINAL | find a hold by id
        active_holds_by_customer | Q9 | HOLDS_FINAL | a query nobody listed
        """;

    var problems = IndexLedger.check(SCHEMA, ledger);

    assertThat(problems)
        .containsExactly(new LedgerProblem.UnknownUser("active_holds_by_customer", "Q9"));
  }

  @Test
  void failsAWriteCostThatNoBenchmarkStepMeasured() {
    String ledger =
        """
        holds_pkey | R2 | HOLDS_FINAL | find a hold by id
        active_holds_by_customer | Q2 | GUESSED | never measured
        """;

    var problems = IndexLedger.check(SCHEMA, ledger);

    assertThat(problems)
        .containsExactly(
            new LedgerProblem.UnmeasuredWriteCost("active_holds_by_customer", "GUESSED"));
  }

  @Test
  void failsAWriteCostMeasuredOnAnotherTable() {
    String ledger =
        """
        holds_pkey | R2 | HOLDS_FINAL | find a hold by id
        active_holds_by_customer | Q2 | EVENT_SETUP | a step that writes seats, not holds
        """;

    var problems = IndexLedger.check(SCHEMA, ledger);

    assertThat(problems)
        .containsExactly(
            new LedgerProblem.WriteCostOfAnotherTable(
                "active_holds_by_customer", "holds", "EVENT_SETUP", "seats"));
  }

  @Test
  void findsTheTableOfEveryIndexInTheRealSchema() throws IOException {
    Path study = Path.of("src/main/resources/db/study");
    String schema =
        Files.readString(study.resolve("bare-schema.sql"), StandardCharsets.UTF_8)
            + Files.readString(study.resolve("final-indexes.sql"), StandardCharsets.UTF_8);

    var tablesByIndex = IndexLedger.indexTablesIn(schema);

    assertThat(tablesByIndex)
        .hasSize(17)
        .containsEntry("venues_pkey", "venues")
        .containsEntry("sections_pkey", "sections")
        .containsEntry("one_seat_per_number", "seats")
        .containsEntry("hold_identity", "holds")
        .containsEntry("one_booking_per_seat", "bookings")
        .containsEntry("events_by_venue_date", "events")
        .containsEntry("one_customer_per_ref", "customers");
  }

  @Test
  void findsAUniqueConstraintDeclaredInsideItsTable() {
    String schema =
        """
        CREATE TABLE customers (
          customer_id BIGINT NOT NULL,
          customer_ref VARCHAR(64) NOT NULL,
          CONSTRAINT customers_pkey PRIMARY KEY (customer_id),
          CONSTRAINT one_customer_per_ref UNIQUE (customer_ref)
        );
        """;

    var tablesByIndex = IndexLedger.indexTablesIn(schema);

    assertThat(tablesByIndex)
        .containsExactly(
            entry("customers_pkey", "customers"), entry("one_customer_per_ref", "customers"));
  }

  @Test
  void namesTheIndexInEveryProblemItDescribes() {
    List<LedgerProblem> problems =
        List.of(
            new LedgerProblem.UnrecordedIndex("one_claim_per_seat"),
            new LedgerProblem.IndexWithoutUser("one_claim_per_seat"),
            new LedgerProblem.UnknownUser("one_claim_per_seat", "Q9"),
            new LedgerProblem.UnmeasuredWriteCost("one_claim_per_seat", "GUESSED"),
            new LedgerProblem.WriteCostOfAnotherTable(
                "one_claim_per_seat", "holds", "EVENT_SETUP", "seats"),
            new LedgerProblem.StaleRow("one_claim_per_seat"),
            new LedgerProblem.DuplicateRow("one_claim_per_seat"));

    var descriptions = problems.stream().map(LedgerProblem::describe).toList();

    assertThat(descriptions)
        .allSatisfy(description -> assertThat(description).contains("one_claim_per_seat"));
    assertThat(new LedgerProblem.MalformedLine(3, "one_claim_per_seat | Q1").describe())
        .contains("line 3", "one_claim_per_seat | Q1");
  }

  @Test
  void failsARowForAnIndexTheSchemaNoLongerHas() {
    String ledger =
        """
        holds_pkey | R2 | HOLDS_FINAL | find a hold by id
        active_holds_by_customer | Q2 | HOLDS_FINAL | the basket
        commented_out | Q2 | HOLDS_FINAL | only in a comment
        """;

    var problems = IndexLedger.check(SCHEMA, ledger);

    assertThat(problems).containsExactly(new LedgerProblem.StaleRow("commented_out"));
  }

  @Test
  void failsAMalformedLineAndADuplicateRow() {
    String ledger =
        """
        holds_pkey | R2 | HOLDS_FINAL | find a hold by id
        holds_pkey | R2 | HOLDS_FINAL | again
        active_holds_by_customer | Q2
        """;

    var problems = IndexLedger.check(SCHEMA, ledger);

    assertThat(problems)
        .containsExactly(
            new LedgerProblem.DuplicateRow("holds_pkey"),
            new LedgerProblem.MalformedLine(3, "active_holds_by_customer | Q2"),
            new LedgerProblem.UnrecordedIndex("active_holds_by_customer"));
  }
}
