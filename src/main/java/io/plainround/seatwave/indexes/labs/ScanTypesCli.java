package io.plainround.seatwave.indexes.labs;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lab 1's solution: reads a plan report written by PlanRunnerCli and prints, per query, every scan
 * node with the table it reads and the index it uses, plus the rows its filters threw away. Needs
 * nothing but the JDK.
 *
 * <p>Usage: {@code ScanTypesCli build/study/plans-before.txt}
 */
public final class ScanTypesCli {

  // The scan node names EXPLAIN prints in PostgreSQL 18, with or without an index name.
  private static final Pattern SCAN_NODE =
      Pattern.compile(
          "(Parallel )?(Seq Scan|Index Only Scan|Index Scan|Bitmap Heap Scan|Bitmap Index Scan)"
              + "(?: Backward)?(?: using (\\w+))? on (\\w+)");
  private static final Pattern REMOVED = Pattern.compile("Rows Removed by Filter: (\\d+)");
  private static final Pattern QUERY_HEADER = Pattern.compile("^== (Q\\d):");
  private static final int EXIT_USAGE = 2;

  private ScanTypesCli() {}

  /** Prints one line per query. */
  public static void main(String[] arguments) {
    if (arguments.length != 1) {
      System.err.println("usage: ScanTypesCli <plans file>");
      System.exit(EXIT_USAGE);
    }
    Map<String, List<String>> scansByQuery = new LinkedHashMap<>();
    Map<String, Long> removedByQuery = new LinkedHashMap<>();
    String currentQuery = "";
    for (String line : read(Path.of(arguments[0]))) {
      Matcher header = QUERY_HEADER.matcher(line);
      if (header.find()) {
        currentQuery = header.group(1);
        scansByQuery.put(currentQuery, new ArrayList<>());
        removedByQuery.put(currentQuery, 0L);
        continue;
      }
      if (currentQuery.isEmpty()) {
        continue;
      }
      Matcher scan = SCAN_NODE.matcher(line);
      if (scan.find()) {
        String index = scan.group(3) == null ? "" : " using " + scan.group(3);
        String parallel = scan.group(1) == null ? "" : "Parallel ";
        scansByQuery
            .get(currentQuery)
            .add(parallel + scan.group(2) + index + " on " + scan.group(4));
      }
      Matcher removed = REMOVED.matcher(line);
      if (removed.find()) {
        removedByQuery.merge(currentQuery, Long.parseLong(removed.group(1)), Long::sum);
      }
    }
    scansByQuery.forEach((query, scans) ->
        System.out.println(
            query
                + ": "
                + String.join("; ", scans)
                + " | rows removed by filters: "
                + removedByQuery.get(query)));
  }

  private static List<String> read(Path plansFile) {
    try {
      return Files.readAllLines(plansFile, StandardCharsets.UTF_8);
    } catch (IOException readFailure) {
      throw new UncheckedIOException("cannot read " + plansFile.toAbsolutePath(), readFailure);
    }
  }
}
