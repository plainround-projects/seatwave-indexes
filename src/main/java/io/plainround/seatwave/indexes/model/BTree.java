package io.plainround.seatwave.indexes.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * A B+tree of {@code long} keys that counts the pages it reads and writes, so a demo can print what
 * one lookup and one insert touch. Each page holds at most {@code maxKeysPerPage} keys; leaves hold
 * the keys, internal pages hold separator keys and child pointers, as in PostgreSQL's B-tree.
 *
 * <p>Not thread-safe: one thread builds and queries a tree. A teaching model, not a database index:
 * it has no disk, no locks and no deletes; PostgreSQL's B-tree adds all three.
 */
public final class BTree {

  // PostgreSQL fills a leaf to its fillfactor (90 percent by default) when keys arrive in order at
  // the right edge, instead of splitting it in half; the model does the same.
  private static final int RIGHT_EDGE_FILL_PERCENT = 90;
  private static final int PERCENT = 100;
  private static final int SMALLEST_USEFUL_PAGE = 3;

  private final int maxKeysPerPage;
  private final Set<Integer> dirtiedPageIds = new HashSet<>();
  private Page root;
  private int nextPageId;
  private int height = 1;

  /**
   * Creates an empty tree.
   *
   * @param maxKeysPerPage how many keys fit on one page; at least 3
   * @throws IllegalArgumentException if {@code maxKeysPerPage} is smaller than 3
   */
  public BTree(int maxKeysPerPage) {
    if (maxKeysPerPage < SMALLEST_USEFUL_PAGE) {
      throw new IllegalArgumentException(
          "maxKeysPerPage must be at least " + SMALLEST_USEFUL_PAGE + ", was " + maxKeysPerPage);
    }
    this.maxKeysPerPage = maxKeysPerPage;
    this.root = new Page(nextPageId++, true);
  }

  /** What a lookup found and the ids of the pages it read, root first. */
  public record Lookup(boolean isFound, List<Integer> pagesRead) {
    /** Copies the path so the record stays immutable. */
    public Lookup {
      pagesRead = List.copyOf(pagesRead);
    }
  }

  /** Finds {@code key}, reading one page per level from the root down to a leaf. */
  public Lookup find(long key) {
    List<Integer> pagesRead = new ArrayList<>();
    Page page = root;
    while (true) {
      pagesRead.add(page.id);
      if (page.isLeaf) {
        return new Lookup(Collections.binarySearch(page.keys, key) >= 0, pagesRead);
      }
      page = page.children.get(childIndex(page, key));
    }
  }

  /**
   * Inserts {@code key}; a key that is already present is left alone.
   *
   * @return the number of pages this insert wrote: the leaf, plus two per split (the new page and
   *     the parent that gains a pointer), plus one if the root split
   */
  public int insert(long key) {
    Set<Integer> writtenNow = new HashSet<>();
    Optional<Split> rootSplit = insertInto(root, key, writtenNow);
    rootSplit.ifPresent(split -> growNewRoot(split, writtenNow));
    dirtiedPageIds.addAll(writtenNow);
    return writtenNow.size();
  }

  private void growNewRoot(Split split, Set<Integer> writtenNow) {
    // The root split: a new root above it is the only way a B-tree grows taller.
    Page newRoot = new Page(nextPageId++, false);
    newRoot.keys.add(split.separator());
    newRoot.children.add(root);
    newRoot.children.add(split.rightPage());
    writtenNow.add(newRoot.id);
    root = newRoot;
    height++;
  }

  /** Forgets which pages were dirtied, so the next inserts can be counted on their own. */
  public void clearDirtiedPages() {
    dirtiedPageIds.clear();
  }

  /** How many distinct pages the inserts since the last {@link #clearDirtiedPages()} wrote. */
  public int dirtiedPageCount() {
    return dirtiedPageIds.size();
  }

  /** Levels from the root to the leaves; a lookup reads exactly this many pages. */
  public int height() {
    return height;
  }

  /** All pages, internal and leaf. */
  public int pageCount() {
    return nextPageId;
  }

  /** Leaf pages only, counted by walking the tree. */
  public int leafCount() {
    return leaves().size();
  }

  /** Average share of a leaf's capacity in use, from 0 to 1. */
  public double averageLeafFill() {
    List<Page> leaves = leaves();
    long keysInLeaves = leaves.stream().mapToLong(leaf -> leaf.keys.size()).sum();
    return (double) keysInLeaves / ((long) leaves.size() * maxKeysPerPage);
  }

  /** Each level's pages as their key lists, root first; meant for small trees. */
  public List<String> describeLevels() {
    List<String> levels = new ArrayList<>();
    List<Page> level = List.of(root);
    while (!level.isEmpty()) {
      StringBuilder line = new StringBuilder();
      List<Page> below = new ArrayList<>();
      for (Page page : level) {
        line.append("p").append(page.id).append(page.keys).append(' ');
        below.addAll(page.children);
      }
      levels.add(line.toString().strip());
      level = below;
    }
    return levels;
  }

  private Optional<Split> insertInto(Page page, long key, Set<Integer> writtenNow) {
    if (page.isLeaf) {
      int position = Collections.binarySearch(page.keys, key);
      if (position >= 0) {
        return Optional.empty();
      }
      page.keys.add(-position - 1, key);
      writtenNow.add(page.id);
      boolean isAppendAtRightEdge = page.next == null && -position - 1 == page.keys.size() - 1;
      return page.keys.size() > maxKeysPerPage
          ? Optional.of(splitLeaf(page, isAppendAtRightEdge, writtenNow))
          : Optional.empty();
    }
    int childPosition = childIndex(page, key);
    Optional<Split> childSplit = insertInto(page.children.get(childPosition), key, writtenNow);
    if (childSplit.isEmpty()) {
      return Optional.empty();
    }
    page.keys.add(childPosition, childSplit.get().separator());
    page.children.add(childPosition + 1, childSplit.get().rightPage());
    writtenNow.add(page.id);
    return page.keys.size() > maxKeysPerPage
        ? Optional.of(splitInternal(page, writtenNow))
        : Optional.empty();
  }

  private Split splitLeaf(Page leaf, boolean isAppendAtRightEdge, Set<Integer> writtenNow) {
    // Keys arriving in order would leave every leaf half empty after a middle split; keeping the
    // left page at the fillfactor is why an ordered key (a time, a sequence) packs its index tight.
    int keysKeptLeft =
        isAppendAtRightEdge
            ? Math.max(1, maxKeysPerPage * RIGHT_EDGE_FILL_PERCENT / PERCENT)
            : leaf.keys.size() / 2;
    Page right = new Page(nextPageId++, true);
    List<Long> moving = leaf.keys.subList(keysKeptLeft, leaf.keys.size());
    right.keys.addAll(moving);
    moving.clear();
    right.next = leaf.next;
    leaf.next = right;
    writtenNow.add(right.id);
    return new Split(right.keys.getFirst(), right);
  }

  private Split splitInternal(Page page, Set<Integer> writtenNow) {
    int middle = page.keys.size() / 2;
    long separator = page.keys.get(middle);
    Page right = new Page(nextPageId++, false);
    List<Long> movingKeys = page.keys.subList(middle + 1, page.keys.size());
    right.keys.addAll(movingKeys);
    movingKeys.clear();
    // The separator moves up to the parent and stays in neither half.
    page.keys.remove(middle);
    List<Page> movingChildren = page.children.subList(middle + 1, page.children.size());
    right.children.addAll(movingChildren);
    movingChildren.clear();
    writtenNow.add(right.id);
    return new Split(separator, right);
  }

  private static int childIndex(Page page, long key) {
    int position = Collections.binarySearch(page.keys, key);
    // An exact separator match belongs to the right child: separators are the right page's first
    // key.
    return position >= 0 ? position + 1 : -position - 1;
  }

  private List<Page> leaves() {
    Page leftmost = root;
    while (!leftmost.isLeaf) {
      leftmost = leftmost.children.getFirst();
    }
    List<Page> leaves = new ArrayList<>();
    for (Page leaf = leftmost; leaf != null; leaf = leaf.next) {
      leaves.add(leaf);
    }
    return leaves;
  }

  private record Split(long separator, Page rightPage) {}

  private static final class Page {
    private final int id;
    private final boolean isLeaf;
    private final List<Long> keys = new ArrayList<>();
    private final List<Page> children = new ArrayList<>();
    private Page next;

    private Page(int id, boolean isLeaf) {
      this.id = id;
      this.isLeaf = isLeaf;
    }
  }
}
