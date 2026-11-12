package io.plainround.seatwave.indexes.lsm;

import io.plainround.seatwave.indexes.study.LatencySummary;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;

/**
 * The hold-write workload against RocksDB, an LSM store, for Lab 3's comparison with the B-tree
 * benchmark. Each hold is one key ({@code hold/<id>}); each "index" is one more key written in the
 * same atomic batch ({@code idx<k>/<index key>/<id>}), the way LSM-based databases such as MyRocks
 * store secondary indexes. Every batch is synced to disk before it returns, like a PostgreSQL
 * commit. After the run it prints RocksDB's own counters, level-0 files and pending compaction,
 * then keeps the store open while compaction pays that debt off, and prints how long it took.
 *
 * <p>Usage: {@code RocksHoldWritesCli <empty folder> <extra index keys, 0 to 5> <seconds>}. It
 * writes only inside the folder it is given, which must be empty or absent.
 */
public final class RocksHoldWritesCli {

  // The same 64 writers as the PostgreSQL benchmark (config/study.properties), so both engines
  // see the same concurrency.
  private static final int WRITERS = 64;
  private static final int MOST_INDEX_KEYS = 5;
  private static final int MOST_SECONDS = 600;
  private static final int CUSTOMERS = 5_000_000;
  private static final int SEATS_PER_EVENT = 20_000;
  private static final double NANOS_PER_SECOND = 1_000_000_000.0;
  private static final int EXIT_USAGE = 2;
  // How long to wait for the background compaction after the timed writes, and how often to look.
  private static final Duration MOST_SETTLE_TIME = Duration.ofSeconds(120);
  private static final Duration SETTLE_POLL = Duration.ofMillis(250);
  // Point reads of holds written by the run, timed before and after compaction catches up.
  private static final int READ_SAMPLES = 10_000;
  private static final long READ_SEED = 14;

  private RocksHoldWritesCli() {}

  /** Runs the workload and prints holds per second and RocksDB's compaction counters. */
  public static void main(String[] arguments)
      throws RocksDBException, InterruptedException, ExecutionException {
    if (arguments.length != 3) {
      System.err.println(
          "usage: RocksHoldWritesCli <empty folder> <extra index keys, 0 to 5> <seconds>");
      System.exit(EXIT_USAGE);
    }
    Path folder = Path.of(arguments[0]);
    int indexKeys = wholeNumber(arguments[1]);
    int seconds = wholeNumber(arguments[2]);
    if (indexKeys < 0
        || indexKeys > MOST_INDEX_KEYS
        || seconds < 1
        || seconds > MOST_SECONDS
        || !isEmptyOrAbsent(folder)) {
      System.err.println(
          "index keys must be 0 to 5, seconds 1 to 600, and the folder empty or absent");
      System.exit(EXIT_USAGE);
    }
    RocksDB.loadLibrary();
    try (Options options = new Options().setCreateIfMissing(true);
        RocksDB database = RocksDB.open(options, folder.toString());
        WriteOptions synced = new WriteOptions().setSync(true)) {
      AtomicLong nextHold = new AtomicLong();
      long deadline = System.nanoTime() + (long) (seconds * NANOS_PER_SECOND);
      long started = System.nanoTime();
      List<Future<Long>> writers = new ArrayList<>();
      try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
        for (int writer = 0; writer < WRITERS; writer++) {
          writers.add(
              executor.submit(() -> writeHolds(database, synced, indexKeys, nextHold, deadline)));
        }
      }
      long holds = 0;
      for (Future<Long> writer : writers) {
        holds += writer.get();
      }
      double elapsed = (System.nanoTime() - started) / NANOS_PER_SECOND;
      System.out.printf(
          Locale.ROOT,
          "rocksdb, %d extra index keys: %,.0f holds/s (%,d holds in %.1f s)%n",
          indexKeys,
          holds / elapsed,
          holds,
          elapsed);
      System.out.println("right after the writes: " + counters(database));
      System.out.println(
          "  reads of " + READ_SAMPLES + " random holds: " + timeReads(database, holds));
      long settleStarted = System.nanoTime();
      boolean isSettled = waitForCompaction(database);
      System.out.printf(
          Locale.ROOT,
          "%s after %.1f s: %s%n",
          isSettled ? "compaction finished" : "compaction still running",
          (System.nanoTime() - settleStarted) / NANOS_PER_SECOND,
          counters(database));
      System.out.println(
          "  reads of " + READ_SAMPLES + " random holds: " + timeReads(database, holds));
    }
  }

  // The same sample of keys each time (fixed seed), so the two read timings differ only by what
  // compaction changed in between.
  private static String timeReads(RocksDB database, long holds) throws RocksDBException {
    SplittableRandom random = new SplittableRandom(READ_SEED);
    long[] nanos = new long[READ_SAMPLES];
    int found = 0;
    for (int sample = 0; sample < READ_SAMPLES; sample++) {
      byte[] key = bytes("hold/" + random.nextLong(holds));
      long started = System.nanoTime();
      byte[] value = database.get(key);
      nanos[sample] = System.nanoTime() - started;
      found += value == null ? 0 : 1;
    }
    return LatencySummary.of(nanos).describe() + ", " + found + " found";
  }

  private static String counters(RocksDB database) throws RocksDBException {
    return "level-0 files "
        + database.getProperty("rocksdb.num-files-at-level0")
        + ", pending compaction "
        + database.getProperty("rocksdb.estimate-pending-compaction-bytes")
        + " bytes, compactions running "
        + database.getProperty("rocksdb.num-running-compactions");
  }

  private static boolean waitForCompaction(RocksDB database)
      throws RocksDBException, InterruptedException {
    long deadline = System.nanoTime() + MOST_SETTLE_TIME.toNanos();
    while (System.nanoTime() < deadline) {
      // "compaction-pending" is 1 while the store still owes a compaction by its own rules.
      if ("0".equals(database.getProperty("rocksdb.compaction-pending"))
          && "0".equals(database.getProperty("rocksdb.num-running-compactions"))) {
        return true;
      }
      Thread.sleep(SETTLE_POLL);
    }
    return false;
  }

  private static long writeHolds(
      RocksDB database, WriteOptions synced, int indexKeys, AtomicLong nextHold, long deadline)
      throws RocksDBException {
    long written = 0;
    while (System.nanoTime() < deadline) {
      long hold = nextHold.getAndIncrement();
      String holdKey = "hold/" + hold;
      String value =
          "event=" + (hold / SEATS_PER_EVENT) + ";customer=" + (hold % CUSTOMERS) + ";state=ACTIVE";
      // One batch is atomic: the hold and its index keys are written together or not at all.
      try (WriteBatch batch = new WriteBatch()) {
        batch.put(bytes(holdKey), bytes(value));
        for (int index = 0; index < indexKeys; index++) {
          // An LSM write never reads the index first: it appends a key, so a random-looking key
          // costs the same as an ordered one now, and compaction sorts it out later.
          batch.put(
              bytes(
                  "idx" + index + "/" + Long.toHexString(Long.reverse(hold) ^ index) + "/" + hold),
              new byte[0]);
        }
        database.write(synced, batch);
      }
      written++;
    }
    return written;
  }

  // A value that is not a whole number counts as out of range, so it gets the usage message.
  private static int wholeNumber(String text) {
    try {
      return Integer.parseInt(text.strip());
    } catch (NumberFormatException notANumber) {
      return -1;
    }
  }

  private static byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  private static boolean isEmptyOrAbsent(Path folder) {
    if (!Files.exists(folder)) {
      return true;
    }
    try (var entries = Files.list(folder)) {
      return entries.findAny().isEmpty();
    } catch (IOException listFailure) {
      throw new UncheckedIOException("cannot list " + folder.toAbsolutePath(), listFailure);
    }
  }
}
