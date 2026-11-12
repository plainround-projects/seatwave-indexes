package io.plainround.seatwave.indexes.ledger;

/**
 * One reason the index ledger and the schema disagree. A closed set: the check switches over it
 * exhaustively, so a new kind of problem is a compile error wherever problems are reported.
 */
public sealed interface LedgerProblem {

  /** The line that explains the problem to a reader of the build output. */
  String describe();

  /** The schema creates an index that has no ledger row: nobody said which query it serves. */
  record UnrecordedIndex(String indexName) implements LedgerProblem {
    @Override
    public String describe() {
      return "index "
          + indexName
          + " is in the schema but has no ledger row naming the query it serves";
    }
  }

  /** A ledger row lists no user at all. */
  record IndexWithoutUser(String indexName) implements LedgerProblem {
    @Override
    public String describe() {
      return "index "
          + indexName
          + " has a ledger row with no user; an index nobody reads is pure write cost";
    }
  }

  /** A ledger row names a user that is not a listed query. */
  record UnknownUser(String indexName, String user) implements LedgerProblem {
    @Override
    public String describe() {
      return "index "
          + indexName
          + " names user '"
          + user
          + "', which is not a listed query (Q1 to Q5, R1 to R6)";
    }
  }

  /** A ledger row names a write-benchmark step that benchmarks a different table. */
  record WriteCostOfAnotherTable(String indexName, String table, String step, String stepTable)
      implements LedgerProblem {
    @Override
    public String describe() {
      return "index "
          + indexName
          + " is on "
          + table
          + " but names the benchmark step "
          + step
          + ", which measures writes to "
          + stepTable;
    }
  }

  /** A ledger row names a write-benchmark step that does not exist. */
  record UnmeasuredWriteCost(String indexName, String step) implements LedgerProblem {
    @Override
    public String describe() {
      return "index "
          + indexName
          + " says its write cost was measured by '"
          + step
          + "', which is not a benchmark step";
    }
  }

  /** A ledger row names an index the schema no longer has. */
  record StaleRow(String indexName) implements LedgerProblem {
    @Override
    public String describe() {
      return "ledger row "
          + indexName
          + " names an index that is not in the schema; delete the row";
    }
  }

  /** Two ledger rows for one index. */
  record DuplicateRow(String indexName) implements LedgerProblem {
    @Override
    public String describe() {
      return "index " + indexName + " has more than one ledger row";
    }
  }

  /** A line that is not "name | users | write step | reason". */
  record MalformedLine(int lineNumber, String line) implements LedgerProblem {
    @Override
    public String describe() {
      return "ledger line " + lineNumber + " is not 'name | users | write step | reason': " + line;
    }
  }
}
