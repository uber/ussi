/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.inverted.generator;

import com.carrotsearch.hppc.IntArrayList;
import com.uber.ussi.comparator.Comparator;
import com.uber.ussi.comparator.ConjunctionScored;
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
 * Row-major merge candidate generation over uni-sorted inverted lists.
 *
 * <p>One frontier spans the query's keys and advances them in step, so a candidate row's entries
 * all arrive together. The merge accumulates the row's conjunction as it goes and abandons the row
 * once no completion of it can reach minSimilarity.
 *
 * <p>Public only for the sibling inverted index packages.
 */
public final class MergeSearch {
  private MergeSearch() {}

  /**
   * Generates and scores candidates for {@code query}. When {@code scoresFromConjunction} is set,
   * the accumulated conjunction is the row's exact score and {@code verificationRowLookup} is never
   * consulted. Length filtering reads the query's uni value from {@code indexedQuery}, the form the
   * indexed rows are in.
   */
  public static List<RowNumAndSimilarity> search(
      Comparator comparator,
      LongTermsAndValues query,
      LongTermsAndValues indexedQuery,
      @Nullable MetaFilter metadataFilter,
      float minSimilarity,
      int maxResults,
      QueryKey[] queryKeys,
      Context context,
      RowFilter rowFilter,
      boolean scoresFromConjunction,
      LongFunction<LongTermsAndValues> verificationRowLookup) {
    if (queryKeys.length == 0) {
      return List.of();
    }
    // Only a SPARS_MERGE namespace reaches here, which the config validator admits only for a
    // comparator a conjunction scores.
    ConjunctionScored conjunctionScored = (ConjunctionScored) comparator;
    double uniValue1 = context.stableSortedUniValue(indexedQuery);
    double[] unscannedKeysUniValue =
        scoresFromConjunction ? computeUnscannedKeysUniValue(conjunctionScored, queryKeys) : null;
    Frontier frontier =
        new Frontier(
            queryKeys,
            context,
            getFirstIndexInEachList(
                conjunctionScored, queryKeys, context, uniValue1, minSimilarity));

    BoundedSizeMaxHeap<RowNumAndSimilarity> rows = TopResults.newTopResultsHeap(maxResults);
    double currentMinSimilarity = minSimilarity;
    Conjunction conjunction = new Conjunction(comparator, conjunctionScored);
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
              ? conjunction.getSimilarity(uniValue1, uniValue2)
              : getVerifiedSimilarity(
                  comparator, query, verificationRowLookup.apply(rowNum), currentMinSimilarity);
      if (similarity < currentMinSimilarity) {
        continue;
      }
      rows.add(new RowNumAndSimilarity(rowNum, (float) similarity));
      if (rows.isFull()) {
        currentMinSimilarity =
            Math.max(currentMinSimilarity, TopResults.getConservativeMinSimilarity(rows));
      }
    }
    return rows.toList();
  }

  /**
   * Consumes every frontier head that sits on {@code rowNum}, adding each shared key to {@code
   * conjunction}. Returns false once no completion of the row can reach {@code minSimilarity},
   * having still consumed the row's whole group. A null {@code unscannedKeysUniValue} means the
   * caller scores through the comparator, so no conjunction is computed.
   */
  private static boolean mergeRow(
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
      conjunction.add(queryKey, queryKey.getValueAt(head.indexInList));
      double unscanned =
          frontier.isOnRow(rowNum) ? unscannedKeysUniValue[frontier.peek().keyIndex] : 0.0;
      mayReachMinSimilarity =
          conjunction.getMaxSimilarity(unscanned, uniValue1, uniValue2)
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
      ConjunctionScored conjunctionScored, QueryKey[] queryKeys) {
    double[] unscannedKeysUniValue = new double[queryKeys.length + 1];
    if (!conjunctionScored.doesSuffixBoundConjunction()) {
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
      ConjunctionScored conjunctionScored,
      QueryKey[] queryKeys,
      Context context,
      double uniValue1,
      double minSimilarity) {
    int[] firstIndexInList = new int[queryKeys.length];
    double minUniValue2 =
        conjunctionScored.doesSuffixBoundConjunction() && minSimilarity > 0.0
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

  public interface Context extends SearchContext {
    /** Returns a query's Uni value summed in the same fixed order used for the indexed rows. */
    double stableSortedUniValue(LongTermsAndValues termsAndValues);
  }

  /** A query key the index knows, paired with the inverted list it probes. */
  public static final class QueryKey {
    private final InvertedList invertedList;
    private final float value1;
    private final double uniTransformedValue;

    public QueryKey(InvertedList invertedList, float value1, double uniTransformedValue) {
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

    public int getNumRows() {
      return invertedList.size();
    }
  }

  /**
   * The merge frontier: one head per query key, ordered so that a candidate row's heads arrive
   * together and a key's own entries arrive in inverted-list order.
   *
   * <p>Each key owns one reusable head, mutated only while it sits outside the queue, between
   * {@link #pollAndAdvanceIndexInList()} and the matching {@link #pushHead(int)}.
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

  /** One candidate row's conjunction, grown one shared key at a time. */
  private static final class Conjunction {
    private final Comparator comparator;
    private final ConjunctionScored conjunctionScored;
    private double conjunction;
    private double partialUniValue1;
    private double partialUniValue2;

    private Conjunction(Comparator comparator, ConjunctionScored conjunctionScored) {
      this.comparator = comparator;
      this.conjunctionScored = conjunctionScored;
    }

    private void reset() {
      conjunction = 0.0;
      partialUniValue1 = 0.0;
      partialUniValue2 = 0.0;
    }

    private void add(QueryKey queryKey, float value2) {
      conjunction += conjunctionScored.conjunctionContribution(queryKey.value1, value2);
      partialUniValue1 += queryKey.uniTransformedValue;
      partialUniValue2 += comparator.getUniTransformedValue(value2);
    }

    private double getSimilarity(double uniValue1, double uniValue2) {
      return Math.max(
          0.0,
          conjunctionScored.similarityFromConjunction(
              conjunction, partialUniValue1, uniValue1, partialUniValue2, uniValue2));
    }

    private double getMaxSimilarity(
        double unscannedKeysUniValue, double uniValue1, double uniValue2) {
      return conjunctionScored.maxSimilarityFromPartialConjunction(
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
