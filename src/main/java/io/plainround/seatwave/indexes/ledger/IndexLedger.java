package io.plainround.seatwave.indexes.ledger;

import io.plainround.seatwave.indexes.catalog.ListedQuery;
import io.plainround.seatwave.indexes.catalog.WriteStep;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Checks the module's invariant: every index in the final schema serves at least one listed query
 * and names the benchmark step that measured its write cost on the index's own table. It reads the
 * schema's SQL text and the ledger, so an index added to a migration without a ledger row fails the
 * build before it reaches a database. Stateless and thread-safe.
 */
public final class IndexLedger {

  // CREATE [UNIQUE] INDEX [CONCURRENTLY] [IF NOT EXISTS] name ON [ONLY] table, and the key
  // constraints that build an index of their own: CONSTRAINT name PRIMARY KEY | UNIQUE, inside a
  // CREATE TABLE or an ALTER TABLE, whose table they belong to.
  private static final Pattern CREATE_INDEX =
      Pattern.compile(
          "CREATE\\s+(?:UNIQUE\\s+)?INDEX\\s+(?:CONCURRENTLY\\s+)?(?:IF\\s+NOT\\s+EXISTS\\s+)?(\\w+)"
              + "\\s+ON\\s+(?:ONLY\\s+)?(\\w+)",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern TABLE_STATEMENT =
      Pattern.compile(
          "^\\s*(?:CREATE|ALTER)\\s+TABLE\\s+(?:IF\\s+(?:NOT\\s+)?EXISTS\\s+)?(?:ONLY\\s+)?(\\w+)",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern KEY_CONSTRAINT =
      Pattern.compile(
          "CONSTRAINT\\s+(\\w+)\\s+(?:PRIMARY\\s+KEY|UNIQUE)\\b", Pattern.CASE_INSENSITIVE);
  private static final Pattern SQL_COMMENT = Pattern.compile("--[^\\n]*");
  private static final String STATEMENT_END = ";";
  private static final String FIELD_SEPARATOR = "\\|";
  private static final int FIELDS_PER_ROW = 4;
  private static final int USERS_FIELD = 1;
  private static final int STEP_FIELD = 2;

  private IndexLedger() {}

  /**
   * Compares the indexes the schema creates with the ledger's rows.
   *
   * @param schemaSql the final schema, all files concatenated
   * @param ledgerText the ledger, one row per index
   * @return every problem found, in schema order; empty when the two agree
   */
  public static List<LedgerProblem> check(String schemaSql, String ledgerText) {
    List<LedgerProblem> problems = new ArrayList<>();
    Map<String, String[]> rows = readRows(ledgerText, problems);
    Map<String, String> tablesByIndex = indexTablesIn(schemaSql);
    tablesByIndex.forEach((indexName, table) -> {
      String[] row = rows.get(indexName);
      if (row == null) {
        problems.add(new LedgerProblem.UnrecordedIndex(indexName));
        return;
      }
      checkUsers(indexName, row[USERS_FIELD], problems);
      checkWriteStep(indexName, table, row[STEP_FIELD], problems);
    });
    rows.keySet().stream()
        .filter(rowName -> !tablesByIndex.containsKey(rowName))
        .forEach(rowName -> problems.add(new LedgerProblem.StaleRow(rowName)));
    return List.copyOf(problems);
  }

  /** The names of the indexes the SQL creates, explicit or through a key constraint, in order. */
  public static Set<String> indexNamesIn(String schemaSql) {
    return Collections.unmodifiableSet(new LinkedHashSet<>(indexTablesIn(schemaSql).keySet()));
  }

  /**
   * Each index the SQL creates, explicit or through a key constraint, with the table it is on, in
   * the order the SQL creates them. A constraint attached later with {@code ADD CONSTRAINT ...
   * USING INDEX} names an index that already exists, so it adds nothing new.
   */
  public static Map<String, String> indexTablesIn(String schemaSql) {
    String withoutComments = SQL_COMMENT.matcher(schemaSql).replaceAll("");
    Map<String, String> tablesByIndex = new LinkedHashMap<>();
    for (String statement : withoutComments.split(STATEMENT_END)) {
      Matcher createIndex = CREATE_INDEX.matcher(statement);
      while (createIndex.find()) {
        tablesByIndex.putIfAbsent(lower(createIndex.group(1)), lower(createIndex.group(2)));
      }
      Matcher table = TABLE_STATEMENT.matcher(statement);
      if (!table.find()) {
        continue;
      }
      Matcher constraint = KEY_CONSTRAINT.matcher(statement);
      while (constraint.find()) {
        tablesByIndex.putIfAbsent(lower(constraint.group(1)), lower(table.group(1)));
      }
    }
    return Collections.unmodifiableMap(tablesByIndex);
  }

  private static Map<String, String[]> readRows(String ledgerText, List<LedgerProblem> problems) {
    Map<String, String[]> rows = new LinkedHashMap<>();
    List<String> lines = ledgerText.lines().toList();
    for (int lineIndex = 0; lineIndex < lines.size(); lineIndex++) {
      String line = lines.get(lineIndex).strip();
      if (line.isEmpty() || line.startsWith("#")) {
        continue;
      }
      String[] fields =
          Arrays.stream(line.split(FIELD_SEPARATOR, -1)).map(String::strip).toArray(String[]::new);
      if (fields.length != FIELDS_PER_ROW || fields[0].isEmpty()) {
        problems.add(new LedgerProblem.MalformedLine(lineIndex + 1, line));
        continue;
      }
      String indexName = fields[0].toLowerCase(Locale.ROOT);
      if (rows.putIfAbsent(indexName, fields) != null) {
        problems.add(new LedgerProblem.DuplicateRow(indexName));
      }
    }
    return rows;
  }

  private static void checkUsers(
      String indexName, String usersField, List<LedgerProblem> problems) {
    Set<String> knownUsers =
        Arrays.stream(ListedQuery.values()).map(Enum::name).collect(Collectors.toSet());
    List<String> users =
        Arrays.stream(usersField.split("\\s+")).filter(user -> !user.isEmpty()).toList();
    if (users.isEmpty()) {
      problems.add(new LedgerProblem.IndexWithoutUser(indexName));
    }
    users.stream()
        .filter(user -> !knownUsers.contains(user))
        .forEach(user -> problems.add(new LedgerProblem.UnknownUser(indexName, user)));
  }

  private static void checkWriteStep(
      String indexName, String table, String step, List<LedgerProblem> problems) {
    Optional<WriteStep> known =
        Arrays.stream(WriteStep.values())
            .filter(candidate -> candidate.name().equals(step))
            .findFirst();
    if (known.isEmpty()) {
      problems.add(new LedgerProblem.UnmeasuredWriteCost(indexName, step));
      return;
    }
    // A step that benchmarks another table measured nothing about this index.
    if (!known.get().table().equals(table)) {
      problems.add(
          new LedgerProblem.WriteCostOfAnotherTable(indexName, table, step, known.get().table()));
    }
  }

  private static String lower(String name) {
    return name.toLowerCase(Locale.ROOT);
  }
}
