package io.plainround.seatwave.indexes.study;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * The study's settings, read once at start-up from {@code config/study.properties}. Every key is
 * required and range-checked; all problems are reported together in one message, so a reader fixes
 * the file once. The password is read from the file the settings name, never from the settings file
 * itself, and only when a program connects, so a dry run needs no password. Immutable and
 * thread-safe.
 *
 * @param jdbcUrl the lab database, on the loopback address only
 * @param user the role the study logs in as (Module 13's seatwave_owner: it owns the schema)
 * @param passwordFile the file that holds the password, read by {@link #password()}
 * @param arenaEvents events with 20 sections of 1,000 seats
 * @param clubEvents events with one section of 200 seats
 * @param planRepetitions timed runs of each query after one warm-up run
 * @param benchmarkSeconds how long each write phase runs
 * @param writerThreads concurrent writers in the benchmark, one connection each
 * @param prefillRows rows each benchmark table holds before the timed writes
 */
public record StudySettings(
    String jdbcUrl,
    String user,
    Path passwordFile,
    int arenaEvents,
    int clubEvents,
    int planRepetitions,
    int benchmarkSeconds,
    int writerThreads,
    int prefillRows) {

  // The study's own limits: 2,500 events of each kind is the volume the lab was sized for.
  private static final int MOST_EVENTS = 2_500;
  private static final int MOST_REPETITIONS = 10_000;
  private static final int MOST_SECONDS = 600;
  // compose.yaml allows 200 connections; the benchmark keeps a margin for psql and the runners.
  private static final int MOST_WRITERS = 128;
  private static final int MOST_PREFILL_ROWS = 50_000_000;
  private static final String LOOPBACK_URL_PREFIX = "jdbc:postgresql://127.0.0.1:";

  /**
   * Reads the password from its file, at the moment a program connects.
   *
   * @throws UncheckedIOException if the file cannot be read, naming the file and how to make it
   */
  public String password() {
    return readPassword(passwordFile);
  }

  /**
   * Reads and validates the settings file.
   *
   * @throws IllegalArgumentException listing every missing or out-of-range key
   * @throws UncheckedIOException if the settings file cannot be read
   */
  public static StudySettings load(Path settingsFile) {
    Properties properties = new Properties();
    try (Reader reader = Files.newBufferedReader(settingsFile, StandardCharsets.UTF_8)) {
      properties.load(reader);
    } catch (IOException readFailure) {
      throw new UncheckedIOException(
          "cannot read settings file " + settingsFile.toAbsolutePath(), readFailure);
    }
    List<String> problems = new ArrayList<>();
    String jdbcUrl = text(properties, "db.url", problems);
    if (!jdbcUrl.isEmpty() && !jdbcUrl.startsWith(LOOPBACK_URL_PREFIX)) {
      problems.add("db.url must point at the lab database on 127.0.0.1, was " + jdbcUrl);
    }
    String user = text(properties, "db.user", problems);
    String passwordFile = text(properties, "db.passwordFile", problems);
    int arenaEvents = number(properties, "study.arenaEvents", 1, MOST_EVENTS, problems);
    int clubEvents = number(properties, "study.clubEvents", 0, MOST_EVENTS, problems);
    int repetitions = number(properties, "plan.repetitions", 1, MOST_REPETITIONS, problems);
    int seconds = number(properties, "bench.seconds", 1, MOST_SECONDS, problems);
    int writers = number(properties, "bench.writerThreads", 1, MOST_WRITERS, problems);
    int prefill = number(properties, "bench.prefillRows", 0, MOST_PREFILL_ROWS, problems);
    if (!problems.isEmpty()) {
      throw new IllegalArgumentException(
          "settings file "
              + settingsFile.toAbsolutePath()
              + " has problems: "
              + String.join("; ", problems));
    }
    return new StudySettings(
        jdbcUrl,
        user,
        Path.of(passwordFile),
        arenaEvents,
        clubEvents,
        repetitions,
        seconds,
        writers,
        prefill);
  }

  private static String text(Properties properties, String key, List<String> problems) {
    String value = properties.getProperty(key, "").strip();
    if (value.isEmpty()) {
      problems.add(key + " is missing");
    }
    return value;
  }

  private static int number(
      Properties properties, String key, int smallest, int largest, List<String> problems) {
    String value = text(properties, key, problems);
    if (value.isEmpty()) {
      return smallest;
    }
    try {
      int parsed = Integer.parseInt(value);
      if (parsed < smallest || parsed > largest) {
        problems.add(key + " must be from " + smallest + " to " + largest + ", was " + parsed);
      }
      return parsed;
    } catch (NumberFormatException notANumber) {
      problems.add(key + " must be a whole number, was '" + value + "'");
      return smallest;
    }
  }

  private static String readPassword(Path passwordFile) {
    try {
      return Files.readString(passwordFile, StandardCharsets.UTF_8).strip();
    } catch (IOException readFailure) {
      throw new UncheckedIOException(
          "cannot read the password file "
              + passwordFile.toAbsolutePath()
              + "; scripts/stack.sh up --run writes it",
          readFailure);
    }
  }
}
