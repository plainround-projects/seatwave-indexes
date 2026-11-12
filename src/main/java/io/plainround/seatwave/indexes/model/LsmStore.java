package io.plainround.seatwave.indexes.model;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * A log-structured merge tree of string keys and values, reduced to what a demo needs to show:
 * every write goes to a log and a sorted in-memory table (the memtable); a full memtable is written
 * out as an immutable sorted file (an SSTable) in level 0; when level 0 holds enough files they are
 * merged with level 1 into one new sorted run (compaction). A read looks in the memtable, then in
 * every level-0 file newest first, then in level 1, and counts the files it searched.
 *
 * <p>Not thread-safe: one thread owns a store. Real engines such as RocksDB add more levels, Bloom
 * filters that let a read skip most files (Module 21), deletes, and background compaction threads.
 */
public final class LsmStore {

  private final int memtableLimit;
  private final int level0CompactionTrigger;
  private final Deque<SortedRun> level0 = new ArrayDeque<>();
  private NavigableMap<String, String> memtable = new TreeMap<>();
  private SortedRun level1 = new SortedRun(Collections.emptyNavigableMap());
  private boolean isCompactionPaused;
  private long userWrites;
  private long entriesWrittenToLog;
  private long entriesFlushed;
  private long entriesCompacted;

  /**
   * Creates an empty store.
   *
   * @param memtableLimit entries the memtable holds before it is flushed; at least 1
   * @param level0CompactionTrigger level-0 files that start a compaction; at least 1
   * @throws IllegalArgumentException if either value is below 1
   */
  public LsmStore(int memtableLimit, int level0CompactionTrigger) {
    if (memtableLimit < 1 || level0CompactionTrigger < 1) {
      throw new IllegalArgumentException(
          "memtableLimit and level0CompactionTrigger must be at least 1, were "
              + memtableLimit
              + " and "
              + level0CompactionTrigger);
    }
    this.memtableLimit = memtableLimit;
    this.level0CompactionTrigger = level0CompactionTrigger;
  }

  /** The result of one read: the value, if any, and how many SSTables the read searched. */
  public record Read(Optional<String> value, int sstablesSearched) {}

  /** A snapshot of the store's shape and of what it has written so far. */
  public record Stats(
      int memtableEntries,
      int level0Files,
      int level1Entries,
      long entriesOnDisk,
      int liveKeys,
      double writeAmplification) {}

  /** Writes {@code value} under {@code key}; the newest write of a key wins on every read. */
  public void put(String key, String value) {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(value, "value");
    userWrites++;
    // Durability first: the log append is what lets a crash replay a memtable that was never
    // flushed (Module 26). It is sequential, which is why an LSM write is cheap.
    entriesWrittenToLog++;
    memtable.put(key, value);
    if (memtable.size() >= memtableLimit) {
      flushMemtable();
    }
  }

  /** Reads {@code key}: memtable first, then level-0 files newest first, then level 1. */
  public Read get(String key) {
    Objects.requireNonNull(key, "key");
    String inMemory = memtable.get(key);
    if (inMemory != null) {
      return new Read(Optional.of(inMemory), 0);
    }
    int searched = 0;
    for (SortedRun file : level0) {
      // Without a Bloom filter, every file whose key range covers the key must be searched.
      if (file.covers(key)) {
        searched++;
        String found = file.entries().get(key);
        if (found != null) {
          return new Read(Optional.of(found), searched);
        }
      }
    }
    if (level1.covers(key)) {
      searched++;
      return new Read(Optional.ofNullable(level1.entries().get(key)), searched);
    }
    return new Read(Optional.empty(), searched);
  }

  /** Stops compaction, the way a busy or misconfigured engine falls behind its writes. */
  public void pauseCompaction() {
    isCompactionPaused = true;
  }

  /** Lets compaction run again, and runs it at once if level 0 is over its trigger. */
  public void resumeCompaction() {
    isCompactionPaused = false;
    compactIfDue();
  }

  /** Counts what is in memory and on disk; walks every file, so it is meant for demos. */
  public Stats stats() {
    long onDisk = level1.entries().size();
    Set<String> distinctKeys = new HashSet<>(memtable.keySet());
    distinctKeys.addAll(level1.entries().keySet());
    for (SortedRun file : level0) {
      onDisk += file.entries().size();
      distinctKeys.addAll(file.entries().keySet());
    }
    long diskWrites = entriesWrittenToLog + entriesFlushed + entriesCompacted;
    return new Stats(
        memtable.size(),
        level0.size(),
        level1.entries().size(),
        onDisk,
        distinctKeys.size(),
        userWrites == 0 ? 0 : (double) diskWrites / userWrites);
  }

  private void flushMemtable() {
    level0.addFirst(new SortedRun(Collections.unmodifiableNavigableMap(memtable)));
    entriesFlushed += memtable.size();
    memtable = new TreeMap<>();
    compactIfDue();
  }

  private void compactIfDue() {
    if (isCompactionPaused || level0.size() < level0CompactionTrigger) {
      return;
    }
    NavigableMap<String, String> merged = new TreeMap<>(level1.entries());
    // Oldest file first, so a newer value overwrites an older one and the stale copy is dropped.
    Iterator<SortedRun> oldestFirst = level0.descendingIterator();
    while (oldestFirst.hasNext()) {
      merged.putAll(oldestFirst.next().entries());
    }
    level0.clear();
    level1 = new SortedRun(Collections.unmodifiableNavigableMap(merged));
    // The whole of level 1 is rewritten: this is the write amplification an LSM pays later.
    entriesCompacted += merged.size();
  }

  private record SortedRun(NavigableMap<String, String> entries) {
    boolean covers(String key) {
      return !entries.isEmpty()
          && key.compareTo(entries.firstKey()) >= 0
          && key.compareTo(entries.lastKey()) <= 0;
    }
  }
}
