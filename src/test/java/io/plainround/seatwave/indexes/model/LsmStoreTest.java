package io.plainround.seatwave.indexes.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LsmStoreTest {

  private static final int MEMTABLE_LIMIT = 10;
  private static final int LEVEL0_TRIGGER = 4;

  @Test
  void theNewestWriteOfAKeyWinsAcrossFlushesAndCompactions() {
    LsmStore store = new LsmStore(MEMTABLE_LIMIT, LEVEL0_TRIGGER);
    for (int round = 0; round < 50; round++) {
      for (int seat = 0; seat < MEMTABLE_LIMIT; seat++) {
        store.put("seat-" + seat, "round-" + round);
      }
    }

    assertThat(store.get("seat-3").value()).contains("round-49");
  }

  @Test
  void aReadSearchesOneMoreFileForEveryLevel0FileWhileCompactionIsBehind() {
    LsmStore store = new LsmStore(MEMTABLE_LIMIT, LEVEL0_TRIGGER);
    store.put("seat-0", "first");
    store.pauseCompaction();
    for (int write = 0; write < MEMTABLE_LIMIT * 9; write++) {
      store.put("other-" + write, "x");
    }
    // Seat-0's only copy is in the oldest of nine level-0 files; every newer file covers its key.
    assertThat(store.stats().level0Files()).isEqualTo(9);

    store.resumeCompaction();

    assertThat(store.stats().level0Files()).isZero();
    assertThat(store.get("seat-0").sstablesSearched()).isEqualTo(1);
  }

  @Test
  void compactionDropsStaleCopiesSoDiskHoldsOneEntryPerLiveKey() {
    LsmStore store = new LsmStore(MEMTABLE_LIMIT, LEVEL0_TRIGGER);
    for (int round = 0; round < LEVEL0_TRIGGER; round++) {
      for (int seat = 0; seat < MEMTABLE_LIMIT; seat++) {
        store.put("seat-" + seat, "round-" + round);
      }
    }

    LsmStore.Stats stats = store.stats();

    assertThat(stats.entriesOnDisk()).isEqualTo(stats.liveKeys());
    assertThat(stats.writeAmplification()).isGreaterThan(1.0);
  }
}
