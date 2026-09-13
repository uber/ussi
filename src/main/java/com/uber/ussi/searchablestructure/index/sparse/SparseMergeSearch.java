/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.sparse;

import com.carrotsearch.hppc.IntArrayList;
import com.uber.ussi.comparator.Comparator;
import com.uber.ussi.entity.meta.MetaFilter;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;
import com.uber.ussi.searchablestructure.RowNumAndSimilarity;
import com.uber.ussi.utils.BoundedSizeMaxHeap;
import java.util.Arrays;
import java.util.List;
import java.util.PriorityQueue;
import java.util.function.LongFunction;
import javax.annotation.Nullable;

/**
 * Row-major merge candidate generation over uni-sorted sparse inverted lists.
 *
 * <p>One frontier spans the query's sparse keys and advances them in step, so every inverted-list
 * entry of a candidate row arrives together. That lets the merge accumulate a row's conjunction as
 * it goes and abandon the row as soon as no completion of it can reach minSimilarity.
 */
final class SparseMergeSearch {
  private SparseMergeSearch() {}

  /**
   * Generates and scores candidates for {@code query}.
   *
   * <p>When {@code scoresFromConjunction} is set, the accumulated conjunction is the row's exact
   * score and {@code verificationRowLookup} is never consulted. The approximate index types key
   * their lists by signature rather than by term, so they verify each candidate through the
   * comparator.
   *
   * @param query the query in verification form, which is the only form the comparator can score.
   * @param indexedQuery the query in indexed form, which is the form the rows behind {@code
   *     context}'s uni values are in. Length filtering compares the two uni values, so it has to
   *     read the query's from the same form, not from {@code query}.
   */
  static List<RowNumAndSimilarity> search(
      Comparator comparator,
      LongTermsAndValues query,
      LongTermsAndValues indexedQuery,
      @Nullable MetaFilter metadataFilter,
      float minSimilarity,
      int maxResults,
      QueryKey[] queryKeys,
      Context context,
      SparseSearchRowFilter rowFilter,
      boolean scoresFromConjunction,
      LongFunction<LongTermsAndValues> verificationRowLookup) {
    if (queryKeys.length == 0) {
      return List.of();
    }
    double uniValue1 = context.stableSortedUniValue(indexedQuery);
    double[] unscannedKeysUniValue =
        scoresFromConjunction ? computeUnscannedKeysUniValue(comparator, queryKeys) : null;
    Frontier frontier =
        new Frontier(
            queryKeys,
            context,
            getFirstIndexInEachList(comparator, queryKeys, context, uniValue1, minSimilarity));

    BoundedSizeMaxHeap<RowNumAndSimilarity> rows =
        SparseSearchResults.newTopResultsHeap(maxResults);
    double currentMinSimilarity = minSimilarity;
    Conjunction conjunction = new Conjunction();
    IntArrayList advancedKeyIndexes = new IntArrayList(queryKeys.length);

    while (!frontier.isEmpty()) {
      FrontierHead nextHead = frontier.peek();
      long rowNum = nextHead.rowNum;
      double uniValue2 = nextHead.uniValue2;
      if (uniValue2 > uniValue1
          && !comparator.mayPassLengthFiltering(uniValue1, uniValue2, currentMinSimilarity)) {
        // The lists are uni-sorted, so every row still ahead of the frontier is at least this long.
        break;
      }

      conjunction.reset();
      advancedKeyIndexes.clear();
      boolean mayReachMinSimilarity =
          mergeRow(
              comparator,
              frontier,
              rowNum,
              conjunction,
              advancedKeyIndexes,
              unscannedKeysUniValue,
              uniValue1,
              uniValue2,
              currentMinSimilarity);
      for (int index = 0; index < advancedKeyIndexes.size(); ++index) {
        frontier.pushHead(advancedKeyIndexes.get(index));
      }
      if (!mayReachMinSimilarity || !rowFilter.canScore(rowNum, metadataFilter)) {
        continue;
      }

      double similarity =
          scoresFromConjunction
              ? conjunction.getSimilarity(comparator, uniValue1, uniValue2)
              : getVerifiedSimilarity(
                  comparator, query, verificationRowLookup.apply(rowNum), currentMinSimilarity);
      if (similarity < currentMinSimilarity) {
        continue;
      }
      rows.add(new RowNumAndSimilarity(rowNum, (float) similarity));
      if (rows.isFull()) {
        currentMinSimilarity =
            Math.max(currentMinSimilarity, SparseSearchResults.getConservativeMinSimilarity(rows));
      }
    }
    return rows.toList();
  }

  /**
   * Consumes every frontier head that sits on {@code rowNum}, adding each shared sparse key to
   * {@code conjunction} and recording the key indexes it advanced. Returns false once no completion
   * of the row can reach {@code minSimilarity}, having still consumed the row's whole group.
   *
   * <p>A null {@code unscannedKeysUniValue} means the caller scores through the comparator instead,
   * so neither the conjunction nor its bound is worth computing.
   */
  private static boolean mergeRow(
      Comparator comparator,
      Frontier frontier,
      long rowNum,
      Conjunction conjunction,
      IntArrayList advancedKeyIndexes,
      @Nullable double[] unscannedKeysUniValue,
      double uniValue1,
      double uniValue2,
      double minSimilarity) {
    boolean mayReachMinSimilarity = true;
    while (frontier.isOnRow(rowNum)) {
      FrontierHead head = frontier.pollAndAdvanceIndexInList();
      advancedKeyIndexes.add(head.keyIndex);
      if (unscannedKeysUniValue == null || !mayReachMinSimilarity) {
        continue;
      }
      QueryKey queryKey = frontier.getQueryKey(head.keyIndex);
      conjunction.add(comparator, queryKey, queryKey.getValueAt(head.indexInList));
      double unscanned =
          frontier.isOnRow(rowNum) ? unscannedKeysUniValue[frontier.peek().keyIndex] : 0.0;
      mayReachMinSimilarity =
          conjunction.getMaxSimilarity(comparator, unscanned, uniValue1, uniValue2)
              >= minSimilarity;
      // Falls through to consume the rest of the row's group, keeping the list positions right.
    }
    return mayReachMinSimilarity;
  }

  private static double getVerifiedSimilarity(
      Comparator comparator,
      LongTermsAndValues query,
      @Nullable LongTermsAndValues termsAndValues2,
      double minSimilarity) {
    if (termsAndValues2 == null || termsAndValues2.termsLength() == 0) {
      return Double.NEGATIVE_INFINITY;
    }
    return comparator.getSimilarity(query, termsAndValues2, minSimilarity);
  }

  /**
   * Returns, per key index, the summed Uni value of that key and every key after it. This bounds
   * what the query's not-yet-merged keys can still add to a conjunction, which only holds for
   * comparators whose per-key contribution never exceeds the query's Uni-transformed value there.
   */
  private static double[] computeUnscannedKeysUniValue(
      Comparator comparator, QueryKey[] queryKeys) {
    double[] unscannedKeysUniValue = new double[queryKeys.length + 1];
    if (!comparator.doesSuffixBoundConjunction()) {
      Arrays.fill(unscannedKeysUniValue, Double.MAX_VALUE);
      return unscannedKeysUniValue;
    }
    for (int keyIndex = queryKeys.length - 1; keyIndex >= 0; --keyIndex) {
      unscannedKeysUniValue[keyIndex] =
          unscannedKeysUniValue[keyIndex + 1] + queryKeys[keyIndex].uniTransformedValue;
    }
    return unscannedKeysUniValue;
  }

  /**
   * Returns each key's first candidate index. Rows too short to pass the length filter can never
   * match, and because the lists are uni-sorted they all sit below one offset per key.
   */
  private static int[] getFirstIndexInEachList(
      Comparator comparator,
      QueryKey[] queryKeys,
      Context context,
      double uniValue1,
      double minSimilarity) {
    int[] firstIndexInList = new int[queryKeys.length];
    double minUniValue2 =
        comparator.doesSuffixBoundConjunction() && minSimilarity > 0.0
            ? uniValue1 * minSimilarity
            : 0.0;
    if (minUniValue2 <= 0.0) {
      // Uni values are never negative, so every list already starts at its first candidate.
      return firstIndexInList;
    }
    for (int keyIndex = 0; keyIndex < queryKeys.length; ++keyIndex) {
      firstIndexInList[keyIndex] =
          getFirstRowNumAtLeastUniValue(queryKeys[keyIndex].getRowNums(), minUniValue2, context);
    }
    return firstIndexInList;
  }

  /** Returns the index of the first row in {@code rowNums} whose Uni value clears the bound. */
  private static int getFirstRowNumAtLeastUniValue(
      long[] rowNums, double minUniValue, Context context) {
    int low = 0;
    int high = rowNums.length;
    while (low < high) {
      int middle = low + (high - low) / 2;
      if (context.getUniValue(rowNums[middle]) < minUniValue) {
        low = middle + 1;
      } else {
        high = middle;
      }
    }
    return low;
  }

  interface Context extends SparseSearchContext {
    /** Returns a query's Uni value summed in the same fixed order used for the indexed rows. */
    double stableSortedUniValue(LongTermsAndValues termsAndValues);
  }

  /** A query sparse key the index knows, paired with the inverted list it probes. */
  static final class QueryKey {
    private final SparseInvertedList invertedList;
    private final float value1;
    private final double uniTransformedValue;

    QueryKey(SparseInvertedList invertedList, float value1, double uniTransformedValue) {
      this.invertedList = invertedList;
      this.value1 = value1;
      this.uniTransformedValue = uniTransformedValue;
    }

    long[] getRowNums() {
      return invertedList.getRowNums();
    }

    float getValueAt(int indexInList) {
      return invertedList.getValues()[indexInList];
    }

    int getNumRows() {
      return invertedList.size();
    }
  }

  /**
   * The merge frontier: one head per query key, ordered so that a candidate row's heads arrive
   * together and a key's own entries arrive in inverted-list order.
   *
   * <p>Because a key contributes at most one head at a time, each key owns a single reusable head
   * rather than one per inverted-list entry. A head is only ever mutated while it sits outside the
   * queue, between {@link #pollAndAdvanceIndexInList()} and the matching {@link #pushHead(int)}.
   */
  private static final class Frontier {
    private final QueryKey[] queryKeys;
    private final Context context;
    private final int[] nextIndexInList;
    private final FrontierHead[] heads;
    private final PriorityQueue<FrontierHead> queue;

    private Frontier(QueryKey[] queryKeys, Context context, int[] nextIndexInList) {
      this.queryKeys = queryKeys;
      this.context = context;
      this.nextIndexInList = nextIndexInList;
      this.heads = new FrontierHead[queryKeys.length];
      this.queue = new PriorityQueue<>(queryKeys.length);
      for (int keyIndex = 0; keyIndex < queryKeys.length; ++keyIndex) {
        heads[keyIndex] = new FrontierHead(keyIndex);
        pushHead(keyIndex);
      }
    }

    private boolean isEmpty() {
      return queue.isEmpty();
    }

    private FrontierHead peek() {
      return queue.peek();
    }

    /** Returns true if the next head still belongs to {@code rowNum}. */
    private boolean isOnRow(long rowNum) {
      FrontierHead head = queue.peek();
      return head != null && head.rowNum == rowNum;
    }

    private QueryKey getQueryKey(int keyIndex) {
      return queryKeys[keyIndex];
    }

    /**
     * Removes the next head and steps its key past the inverted-list entry it was on. The head is
     * returned to the queue by {@link #pushHead(int)} once the whole row group has been consumed.
     */
    private FrontierHead pollAndAdvanceIndexInList() {
      FrontierHead head = queue.poll();
      ++nextIndexInList[head.keyIndex];
      return head;
    }

    /** Re-inserts one key's head at its next index, or leaves it out once the list runs out. */
    private void pushHead(int keyIndex) {
      long[] rowNums = queryKeys[keyIndex].getRowNums();
      int indexInList = nextIndexInList[keyIndex];
      if (indexInList >= rowNums.length) {
        return;
      }
      long rowNum = rowNums[indexInList];
      heads[keyIndex].moveTo(context.getUniValue(rowNum), rowNum, indexInList);
      queue.offer(heads[keyIndex]);
    }
  }

  /** One candidate row's conjunction, grown one shared sparse key at a time. */
  private static final class Conjunction {
    private double conjunction;
    private double partialUniValue1;
    private double partialUniValue2;

    private void reset() {
      conjunction = 0.0;
      partialUniValue1 = 0.0;
      partialUniValue2 = 0.0;
    }

    private void add(Comparator comparator, QueryKey queryKey, float value2) {
      conjunction += comparator.conjunctionContribution(queryKey.value1, value2);
      partialUniValue1 += queryKey.uniTransformedValue;
      partialUniValue2 += comparator.getUniTransformedValue(value2);
    }

    private double getSimilarity(Comparator comparator, double uniValue1, double uniValue2) {
      return Math.max(
          0.0,
          comparator.similarityFromConjunction(
              conjunction, partialUniValue1, uniValue1, partialUniValue2, uniValue2));
    }

    private double getMaxSimilarity(
        Comparator comparator,
        double unscannedKeysUniValue,
        double uniValue1,
        double uniValue2) {
      return comparator.maxSimilarityFromPartialConjunction(
          conjunction,
          unscannedKeysUniValue,
          partialUniValue1,
          uniValue1,
          partialUniValue2,
          uniValue2);
    }
  }

  /** The entry one query key currently contributes to the merge frontier. */
  private static final class FrontierHead implements Comparable<FrontierHead> {
    private final int keyIndex;
    private double uniValue2;
    private long rowNum;
    private int indexInList;

    private FrontierHead(int keyIndex) {
      this.keyIndex = keyIndex;
    }

    private void moveTo(double uniValue2, long rowNum, int indexInList) {
      this.uniValue2 = uniValue2;
      this.rowNum = rowNum;
      this.indexInList = indexInList;
    }

    /**
     * Orders by ascending Uni value, then by row so a row's heads are always contiguous, then by
     * key index so the unscanned-key bound covers exactly the heads still to come for that row.
     */
    @Override
    public int compareTo(FrontierHead other) {
      int byUniValue = Double.compare(uniValue2, other.uniValue2);
      if (byUniValue != 0) {
        return byUniValue;
      }
      int byRowNum = Long.compare(rowNum, other.rowNum);
      return byRowNum != 0 ? byRowNum : Integer.compare(keyIndex, other.keyIndex);
    }
  }
}
