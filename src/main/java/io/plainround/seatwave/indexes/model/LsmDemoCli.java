package io.plainround.seatwave.indexes.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;

/**
 * Drives an {@link LsmStore} with SeatWave's hold writes for one event (20,000 seats, each write a
 * new state for a random seat) and prints, after each phase, what is on disk, how many SSTables a
 * read searches and the write amplification. The second phase pauses compaction, so the output
 * shows what "compaction falling behind" does to reads and disk; the third lets it catch up.
 */
public final class LsmDemoCli {

  // Module 13's arena event: 20 sections, A to T, of 1,000 seats.
  private static final int SECTIONS = 20;
  private static final int SEATS_PER_SECTION = 1_000;
  // A small memtable so the demo produces many files; RocksDB's default is 64 MB.
  private static final int MEMTABLE_LIMIT = 2_000;
  // RocksDB's default level0_file_num_compaction_trigger, checked in its options.h.
  private static final int LEVEL0_COMPACTION_TRIGGER = 4;
  private static final int WRITES_WITH_COMPACTION = 100_000;
  private static final int WRITES_WHILE_BEHIND = 50_000;
  private static final int SAMPLE_READS = 2_000;
  private static final int CUSTOMERS = 200_000;
  private static final long SEED = 14L;

  private LsmDemoCli() {}

  /** Runs the three phases and prints one block per phase. */
  public static void main(String[] arguments) {
    List<String> seatKeys = arenaSeatKeys();
    LsmStore store = new LsmStore(MEMTABLE_LIMIT, LEVEL0_COMPACTION_TRIGGER);
    SplittableRandom random = new SplittableRandom(SEED);
    writeHolds(store, seatKeys, random, WRITES_WITH_COMPACTION);
    print("phase 1: 100,000 hold writes, compaction keeping up", store, seatKeys, random);
    store.pauseCompaction();
    writeHolds(store, seatKeys, random, WRITES_WHILE_BEHIND);
    print(
        "phase 2: 50,000 more writes, compaction paused (falling behind)", store, seatKeys, random);
    store.resumeCompaction();
    print("phase 3: compaction resumed and caught up", store, seatKeys, random);
  }

  private static List<String> arenaSeatKeys() {
    List<String> keys = new ArrayList<>();
    for (int sectionIndex = 0; sectionIndex < SECTIONS; sectionIndex++) {
      char sectionCode = (char) ('A' + sectionIndex);
      for (int seatNumber = 1; seatNumber <= SEATS_PER_SECTION; seatNumber++) {
        keys.add("1:" + sectionCode + "-" + seatNumber);
      }
    }
    return List.copyOf(keys);
  }

  private static void writeHolds(
      LsmStore store, List<String> seatKeys, SplittableRandom random, int writes) {
    for (int writeIndex = 0; writeIndex < writes; writeIndex++) {
      String seatKey = seatKeys.get(random.nextInt(seatKeys.size()));
      store.put(seatKey, "HELD by fan_" + random.nextInt(CUSTOMERS));
    }
  }

  private static void print(
      String phase, LsmStore store, List<String> seatKeys, SplittableRandom random) {
    long searchedTotal = 0;
    int mostSearched = 0;
    for (int readIndex = 0; readIndex < SAMPLE_READS; readIndex++) {
      int searched = store.get(seatKeys.get(random.nextInt(seatKeys.size()))).sstablesSearched();
      searchedTotal += searched;
      mostSearched = Math.max(mostSearched, searched);
    }
    LsmStore.Stats stats = store.stats();
    System.out.printf(
        Locale.ROOT,
        "== %s%n"
            + "  memtable %,d entries | level 0: %d files | level 1: %,d entries%n"
            + "  entries on disk %,d for %,d live keys (space amplification %.1f)%n"
            + "  SSTables searched per read: average %.1f, worst %d%n"
            + "  write amplification so far: %.1f entries written to disk per user write%n",
        phase,
        stats.memtableEntries(),
        stats.level0Files(),
        stats.level1Entries(),
        stats.entriesOnDisk(),
        stats.liveKeys(),
        (double) stats.entriesOnDisk() / stats.liveKeys(),
        (double) searchedTotal / SAMPLE_READS,
        mostSearched,
        stats.writeAmplification());
  }
}
