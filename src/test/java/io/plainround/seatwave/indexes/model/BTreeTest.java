package io.plainround.seatwave.indexes.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.SplittableRandom;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

class BTreeTest {

  private static final int KEYS_PER_PAGE = 4;
  private static final long SEED = 7L;

  @Test
  void findsEveryInsertedKeyAndNoOther() {
    BTree tree = new BTree(KEYS_PER_PAGE);
    LongStream.rangeClosed(1, 500).forEach(key -> tree.insert(key * 2));

    assertThat(LongStream.rangeClosed(1, 500).allMatch(key -> tree.find(key * 2).isFound()))
        .isTrue();
    assertThat(LongStream.rangeClosed(1, 500).noneMatch(key -> tree.find(key * 2 - 1).isFound()))
        .isTrue();
  }

  @Test
  void aLookupReadsExactlyOnePagePerLevel() {
    BTree tree = new BTree(KEYS_PER_PAGE);
    new SplittableRandom(SEED).longs(2_000).forEach(tree::insert);

    assertThat(tree.find(42).pagesRead()).hasSize(tree.height());
  }

  @Test
  void aDuplicateInsertWritesNoPage() {
    BTree tree = new BTree(KEYS_PER_PAGE);
    tree.insert(7);

    assertThat(tree.insert(7)).isZero();
  }

  @Test
  void keysInOrderPackLeavesToTheFillfactorAndRandomKeysDoNot() {
    BTree ordered = new BTree(100);
    LongStream.rangeClosed(1, 100_000).forEach(ordered::insert);
    BTree random = new BTree(100);
    new SplittableRandom(SEED).longs(100_000).forEach(random::insert);

    assertThat(ordered.averageLeafFill()).isGreaterThan(0.89);
    assertThat(random.averageLeafFill()).isBetween(0.6, 0.8);
  }

  @Test
  void refusesAPageTooSmallToSplit() {
    assertThatThrownBy(() -> new BTree(2))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("at least 3");
  }
}
