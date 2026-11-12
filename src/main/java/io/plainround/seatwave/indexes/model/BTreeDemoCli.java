package io.plainround.seatwave.indexes.model;

import java.util.Locale;
import java.util.SplittableRandom;
import java.util.stream.LongStream;

/**
 * Prints what a B-tree lookup and a B-tree insert touch: a tiny tree drawn level by level, then a
 * tree of a million keys at a real page's fan-out, then how many pages ten thousand inserts dirty
 * when the keys arrive in order and when they arrive at random. Needs nothing but the JDK.
 */
public final class BTreeDemoCli {

  // Part 1: four keys per page keeps the picture small enough to print.
  private static final int TINY_KEYS_PER_PAGE = 4;
  private static final int TINY_SEAT_COUNT = 20;
  private static final long TINY_LOOKUP_SEAT = 13;
  // Part 2: an 8,152-byte usable B-tree page divided by a 28-byte entry (an 8-byte index tuple
  // header, 16 bytes of key padded to 8, a 4-byte line pointer) holds 291 entries.
  private static final int REAL_KEYS_PER_PAGE = 291;
  private static final int MILLION_KEYS = 1_000_000;
  private static final int EXTRA_INSERTS = 10_000;
  // A fixed seed: the same "random" keys on every run, so the printed counts repeat exactly.
  private static final long SEED = 14L;

  private BTreeDemoCli() {}

  /** Runs the three parts and prints their results. */
  public static void main(String[] arguments) {
    printTinyTree();
    BTree randomTree = loadRandomTree();
    printShape("1,000,000 random keys", randomTree);
    BTree orderedTree = new BTree(REAL_KEYS_PER_PAGE);
    LongStream.rangeClosed(1, MILLION_KEYS).forEach(orderedTree::insert);
    printShape("1,000,000 keys in order", orderedTree);
    printWriteLocality(randomTree);
  }

  private static void printTinyTree() {
    BTree tinyTree = new BTree(TINY_KEYS_PER_PAGE);
    LongStream.rangeClosed(1, TINY_SEAT_COUNT).forEach(tinyTree::insert);
    System.out.println("== part 1: seats 1 to 20, at most 4 keys per page");
    tinyTree.describeLevels().forEach(level -> System.out.println("  " + level));
    BTree.Lookup lookup = tinyTree.find(TINY_LOOKUP_SEAT);
    System.out.printf(
        Locale.ROOT,
        "  find(13): found=%s, pages read=%s (one per level, height %d)%n",
        lookup.isFound(),
        lookup.pagesRead(),
        tinyTree.height());
  }

  private static BTree loadRandomTree() {
    BTree tree = new BTree(REAL_KEYS_PER_PAGE);
    SplittableRandom random = new SplittableRandom(SEED);
    int inserted = 0;
    while (inserted < MILLION_KEYS) {
      // Keys like a random UUID's: spread over the whole key space, never in arrival order. An
      // insert that writes no page met a duplicate, so it does not count.
      if (tree.insert(random.nextLong(Long.MAX_VALUE)) > 0) {
        inserted++;
      }
    }
    return tree;
  }

  private static void printShape(String label, BTree tree) {
    System.out.printf(
        Locale.ROOT,
        "== part 2: %s, at most %d keys per page%n"
            + "  height %d, pages %,d, leaves %,d, average leaf fill %.0f%%, pages read per lookup %d%n",
        label,
        REAL_KEYS_PER_PAGE,
        tree.height(),
        tree.pageCount(),
        tree.leafCount(),
        tree.averageLeafFill() * 100,
        tree.height());
  }

  private static void printWriteLocality(BTree randomTree) {
    SplittableRandom random = new SplittableRandom(SEED + 1);
    randomTree.clearDirtiedPages();
    for (int insertIndex = 0; insertIndex < EXTRA_INSERTS; insertIndex++) {
      randomTree.insert(random.nextLong(Long.MAX_VALUE));
    }
    int randomDirtied = randomTree.dirtiedPageCount();
    randomTree.clearDirtiedPages();
    // Larger than every key so far, the way a timestamp or a sequence number arrives.
    long nextOrderedKey = Long.MAX_VALUE - EXTRA_INSERTS;
    for (int insertIndex = 0; insertIndex < EXTRA_INSERTS; insertIndex++) {
      randomTree.insert(nextOrderedKey + insertIndex);
    }
    int orderedDirtied = randomTree.dirtiedPageCount();
    System.out.printf(
        Locale.ROOT,
        "== part 3: 10,000 more inserts into the random tree%n"
            + "  random keys dirtied %,d distinct pages; ordered keys dirtied %,d%n",
        randomDirtied,
        orderedDirtied);
  }
}
