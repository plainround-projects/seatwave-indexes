package io.plainround.seatwave.indexes.ledger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Runs the index ledger check from the command line and exits 1 when it finds a problem, so a build
 * step can fail on it.
 *
 * <p>Usage: {@code IndexLedgerCli <ledger file> <schema file> [<schema file> ...]}
 */
public final class IndexLedgerCli {

  private static final int EXIT_PROBLEMS = 1;
  private static final int EXIT_USAGE = 2;
  private static final int SMALLEST_ARGUMENT_COUNT = 2;

  private IndexLedgerCli() {}

  /** Prints each index with its verdict, or each problem, and sets the exit status. */
  public static void main(String[] arguments) {
    if (arguments.length < SMALLEST_ARGUMENT_COUNT) {
      System.err.println("usage: IndexLedgerCli <ledger file> <schema file> [<schema file> ...]");
      System.exit(EXIT_USAGE);
    }
    String ledgerText = read(Path.of(arguments[0]));
    String schemaSql =
        Arrays.stream(arguments, 1, arguments.length)
            .map(path -> read(Path.of(path)))
            .collect(Collectors.joining("\n"));
    List<LedgerProblem> problems = IndexLedger.check(schemaSql, ledgerText);
    System.out.println("indexes in the schema: " + IndexLedger.indexNamesIn(schemaSql).size());
    if (problems.isEmpty()) {
      System.out.println("index ledger: every index has a listed user and a measured write cost");
      return;
    }
    problems.forEach(problem -> System.out.println("PROBLEM: " + problem.describe()));
    System.out.println("index ledger: " + problems.size() + " problem(s); the build fails");
    System.exit(EXIT_PROBLEMS);
  }

  private static String read(Path path) {
    try {
      return Files.readString(path, StandardCharsets.UTF_8);
    } catch (IOException readFailure) {
      throw new UncheckedIOException("cannot read " + path.toAbsolutePath(), readFailure);
    }
  }
}
