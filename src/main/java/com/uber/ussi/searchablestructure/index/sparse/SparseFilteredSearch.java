/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.sparse;

import com.carrotsearch.hppc.LongHashSet;
import com.uber.ussi.comparator.Comparator;
import com.uber.ussi.entity.meta.MetaFilter;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;
import com.uber.ussi.searchablestructure.RowNumAndSimilarity;
import com.uber.ussi.searchablestructure.sparse.SparseKeyAndPrefixFilteringData;
import com.uber.ussi.utils.BoundedSizeMaxHeap;
import com.uber.ussi.utils.Constants;
import com.uber.ussi.utils.MathUtils;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.LongFunction;
import javax.annotation.Nullable;

/**
 * Sparse-key-major filtered-scan candidate generation over uni-sorted sparse inverted lists.
 *
 * <p>The query's sparse keys are visited cheapest first, and each one's inverted list is narrowed
 * to the rows that length filtering admits. Every candidate is then scored through the comparator,
 * so this generator works for every sparse index type.
 */
final class SparseFilteredSearch {
  /**
   * Below this range size, {@link #getFirstMatchingUniValue} and {@link #getLastMatchingUniValue}
   * scan linearly instead of binary searching.
   */
  private static final int MIN_NUM_CANDIDATES_FOR_BINARY_SEARCH = 32;

  private SparseFilteredSearch() {}

  /**
   * Generates and scores candidates for {@code query}.
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
      SparseKeyAndPrefixFilteringData[] queryKeys,
      Context context,
      SparseSearchRowFilter rowFilter,
      LongFunction<LongTermsAndValues> verificationRowLookup) {
    CandidateIterator candidates =
        new CandidateIterator(
            comparator, context, queryKeys, indexedQuery.getUniValue(), minSimilarity);
    BoundedSizeMaxHeap<RowNumAndSimilarity> rows =
        SparseSearchResults.newTopResultsHeap(maxResults);
    double currentMinSimilarity = minSimilarity;
    while (candidates.hasNext()) {
      long rowNum = candidates.next();
      if (!rowFilter.canScore(rowNum, metadataFilter)) {
        continue;
      }
      LongTermsAndValues termsAndValues2 = verificationRowLookup.apply(rowNum);
      if (termsAndValues2 == null || termsAndValues2.termsLength() == 0) {
        continue;
      }
      double similarity = comparator.getSimilarity(query, termsAndValues2, currentMinSimilarity);
      if (similarity < currentMinSimilarity) {
        continue;
      }
      rows.add(new RowNumAndSimilarity(rowNum, (float) similarity));
      if (rows.isFull()) {
        double tightenedMinSimilarity = SparseSearchResults.getConservativeMinSimilarity(rows);
        if (tightenedMinSimilarity > currentMinSimilarity) {
          currentMinSimilarity = tightenedMinSimilarity;
          candidates.setMinSimilarity(currentMinSimilarity);
        }
      }
    }
    return rows.toList();
  }

  /**
   * Returns the inclusive lower bound of a sparse key's matching row range within
   * [searchFromIndex, searchToIndex) of rowNums for the given comparatorUniValue and minSimilarity.
   * Called with (0, rowNums.length) for a sparse key's first window, and with the key's previous
   * [first, last) range on later calls as minSimilarity rises in {@link CandidateIterator}. This is
   * sound because a sparse key's matching range only shrinks as minSimilarity rises, never grows.
   * Tries these tiers, cheapest first.
   *
   * <p>Tier 1, O(1). One of the range's endpoints already resolves the search.
   *
   * <p>Tier 2, O(1). The endpoints share the same uniValue. Since rowNums is uniValue-sorted, every
   * row between them shares it too, so tier 1's checks cover the whole range.
   *
   * <p>Tier 3. Range smaller than {@link #MIN_NUM_CANDIDATES_FOR_BINARY_SEARCH}. Linear scan from
   * searchFromIndex.
   *
   * <p>Tier 4. Otherwise, binary search.
   */
  static int getFirstMatchingUniValue(
      Comparator comparator,
      Context context,
      long[] rowNums,
      double comparatorUniValue,
      double minSimilarity,
      int searchFromIndex,
      int searchToIndex) {
    validateUniValueSearch(rowNums, comparatorUniValue, searchFromIndex, searchToIndex);
    if (searchFromIndex >= searchToIndex) {
      return searchFromIndex;
    }
    if (isSmallerThanAndNotSimilar(
        comparator, context, comparatorUniValue, rowNums[searchFromIndex], minSimilarity)) {
      return searchFromIndex;
    }
    if (isGreaterThanAndNotSimilar(
        comparator, context, comparatorUniValue, rowNums[searchToIndex - 1], minSimilarity)) {
      return searchToIndex;
    }
    if (context.getUniValue(rowNums[searchFromIndex])
        == context.getUniValue(rowNums[searchToIndex - 1])) {
      return searchFromIndex;
    }
    if (searchToIndex - searchFromIndex <= MIN_NUM_CANDIDATES_FOR_BINARY_SEARCH) {
      int index = searchFromIndex;
      while (index < searchToIndex
          && isGreaterThanAndNotSimilar(
              comparator, context, comparatorUniValue, rowNums[index], minSimilarity)) {
        ++index;
      }
      return index;
    }
    int low = searchFromIndex;
    int high = searchToIndex;
    while (low < high) {
      int middle = low + (high - low) / 2;
      if (isGreaterThanAndNotSimilar(
          comparator, context, comparatorUniValue, rowNums[middle], minSimilarity)) {
        low = middle + 1;
      } else {
        high = middle;
      }
    }
    return low;
  }

  /**
   * Returns the exclusive upper bound of a sparse key's matching row range, the mirror of {@link
   * #getFirstMatchingUniValue} and narrowed by the same tiers.
   */
  static int getLastMatchingUniValue(
      Comparator comparator,
      Context context,
      long[] rowNums,
      double comparatorUniValue,
      double minSimilarity,
      int searchFromIndex,
      int searchToIndex) {
    validateUniValueSearch(rowNums, comparatorUniValue, searchFromIndex, searchToIndex);
    if (searchFromIndex >= searchToIndex) {
      return searchFromIndex;
    }
    if (isGreaterThanAndNotSimilar(
        comparator, context, comparatorUniValue, rowNums[searchToIndex - 1], minSimilarity)) {
      return searchToIndex;
    }
    if (isSmallerThanAndNotSimilar(
        comparator, context, comparatorUniValue, rowNums[searchFromIndex], minSimilarity)) {
      return searchFromIndex;
    }
    if (context.getUniValue(rowNums[searchFromIndex])
        == context.getUniValue(rowNums[searchToIndex - 1])) {
      return searchToIndex;
    }
    if (searchToIndex - searchFromIndex <= MIN_NUM_CANDIDATES_FOR_BINARY_SEARCH) {
      int index = searchToIndex - 1;
      while (index >= searchFromIndex
          && isSmallerThanAndNotSimilar(
              comparator, context, comparatorUniValue, rowNums[index], minSimilarity)) {
        --index;
      }
      return index + 1;
    }
    int low = searchFromIndex;
    int high = searchToIndex;
    while (low < high) {
      int middle = low + (high - low) / 2;
      if (isSmallerThanAndNotSimilar(
          comparator, context, comparatorUniValue, rowNums[middle], minSimilarity)) {
        high = middle;
      } else {
        low = middle + 1;
      }
    }
    return low;
  }

  interface Context extends SparseSearchContext {
    /** Returns the highest prefix cost a sparse key may carry and still admit candidates. */
    double getMinPrefixSum(double sparseKeysUniValue, double minSimilarity);

    /** Returns the uni-sorted inverted list of a sparse key, empty when the key is unindexed. */
    long[] getRowNums(long sparseKey);
  }

  /**
   * Iterates deduplicated candidates in nondecreasing unordered-prefix cost. Takes ownership of the
   * {@code sparseKeyData} it is handed and sorts it in place.
   */
  static final class CandidateIterator implements Iterator<Long> {
    private final Comparator comparator;
    private final Context context;
    private final SparseKeyAndPrefixFilteringData[] sparseKeyData;
    private final double sparseKeysUniValue;
    private final double comparatorUniValue;
    private final LongHashSet generatedRowNums;
    private double minSimilarity;
    private double maxPrefixSum;
    private final MathUtils.StableSumAccumulator prefixSumAccumulator;
    private double currentSparseKeyPrefixSum;
    private int sparseKeyIndex;
    private long[] currentRowNums;
    private int currentRowStartIndex;
    private int currentRowEndIndex;
    private boolean nextRowPrepared;
    private long nextRowNum;

    CandidateIterator(
        Comparator comparator,
        Context context,
        SparseKeyAndPrefixFilteringData[] sparseKeyData,
        double comparatorUniValue,
        double minSimilarity) {
      this.comparator = comparator;
      this.context = context;
      this.sparseKeyData = sparseKeyData;
      Arrays.sort(this.sparseKeyData);
      MathUtils.StableSumAccumulator sparseKeysUniValueAccumulator =
          new MathUtils.StableSumAccumulator();
      for (SparseKeyAndPrefixFilteringData sparseKey : this.sparseKeyData) {
        sparseKeysUniValueAccumulator.add(sparseKey.getUniTransformedValue());
      }
      this.sparseKeysUniValue = sparseKeysUniValueAccumulator.getSum();
      this.comparatorUniValue = comparatorUniValue;
      this.generatedRowNums = new LongHashSet();
      this.minSimilarity = minSimilarity;
      this.maxPrefixSum = context.getMinPrefixSum(sparseKeysUniValue, minSimilarity);
      this.prefixSumAccumulator = new MathUtils.StableSumAccumulator();
      this.currentSparseKeyPrefixSum = 0.0;
      this.sparseKeyIndex = -1;
      this.currentRowNums = new long[0];
      this.currentRowStartIndex = 0;
      this.currentRowEndIndex = 0;
      this.nextRowPrepared = false;
    }

    void setMinSimilarity(double minSimilarity) {
      if (minSimilarity < this.minSimilarity) {
        throw new IllegalArgumentException(
            String.format(
                "Cannot lower minSimilarity from %s to %s.", this.minSimilarity, minSimilarity));
      }
      if (minSimilarity == this.minSimilarity) {
        return;
      }
      this.minSimilarity = minSimilarity;
      this.maxPrefixSum = context.getMinPrefixSum(sparseKeysUniValue, minSimilarity);
      if (sparseKeyIndex < 0 || currentRowStartIndex >= currentRowEndIndex) {
        return;
      }
      if (currentSparseKeyPrefixSum > maxPrefixSum) {
        currentRowStartIndex = currentRowEndIndex;
        return;
      }
      currentRowStartIndex =
          getFirstMatchingUniValue(
              comparator,
              context,
              currentRowNums,
              comparatorUniValue,
              minSimilarity,
              currentRowStartIndex,
              currentRowEndIndex);
      currentRowEndIndex =
          getLastMatchingUniValue(
              comparator,
              context,
              currentRowNums,
              comparatorUniValue,
              minSimilarity,
              currentRowStartIndex,
              currentRowEndIndex);
    }

    @Override
    public boolean hasNext() {
      if (nextRowPrepared) {
        return true;
      }
      while (true) {
        while (currentRowStartIndex < currentRowEndIndex) {
          long rowNum = currentRowNums[currentRowStartIndex++];
          if (!generatedRowNums.contains(rowNum)) {
            generatedRowNums.add(rowNum);
            nextRowNum = rowNum;
            nextRowPrepared = true;
            return true;
          }
        }
        if (sparseKeyIndex >= sparseKeyData.length - 1
            || prefixSumAccumulator.getSum() > maxPrefixSum) {
          return false;
        }
        ++sparseKeyIndex;
        SparseKeyAndPrefixFilteringData currentSparseKey = sparseKeyData[sparseKeyIndex];
        currentSparseKeyPrefixSum = prefixSumAccumulator.getSum();
        currentRowNums = context.getRowNums(currentSparseKey.getSparseKey());
        if (currentRowNums.length != currentSparseKey.getNumRows()) {
          throw new IllegalStateException(
              String.format(
                  "Inconsistent inverted-list length for sparse key %s.",
                  currentSparseKey.getSparseKey()));
        }
        currentRowStartIndex =
            getFirstMatchingUniValue(
                comparator,
                context,
                currentRowNums,
                comparatorUniValue,
                minSimilarity,
                0,
                currentRowNums.length);
        currentRowEndIndex =
            getLastMatchingUniValue(
                comparator,
                context,
                currentRowNums,
                comparatorUniValue,
                minSimilarity,
                currentRowStartIndex,
                currentRowNums.length);
        prefixSumAccumulator.add(currentSparseKey.getUniTransformedValue());
      }
    }

    @Override
    public Long next() {
      if (!hasNext()) {
        throw new NoSuchElementException();
      }
      nextRowPrepared = false;
      return nextRowNum;
    }
  }

  private static boolean isSmallerThanAndNotSimilar(
      Comparator comparator,
      Context context,
      double comparatorUniValue,
      long rowNum,
      double minSimilarity) {
    double uniValue2 = context.getUniValue(rowNum);
    return comparatorUniValue < uniValue2
        && !comparator.mayPassLengthFiltering(comparatorUniValue, uniValue2, minSimilarity);
  }

  private static boolean isGreaterThanAndNotSimilar(
      Comparator comparator,
      Context context,
      double comparatorUniValue,
      long rowNum,
      double minSimilarity) {
    double uniValue2 = context.getUniValue(rowNum);
    return comparatorUniValue > uniValue2
        && !comparator.mayPassLengthFiltering(comparatorUniValue, uniValue2, minSimilarity);
  }

  private static void validateUniValueSearch(
      long[] rowNums, double comparatorUniValue, int searchFromIndex, int searchToIndex) {
    Objects.requireNonNull(rowNums, "rowNums");
    if (comparatorUniValue == Constants.UNSET_UNI_VALUE
        || !Double.isFinite(comparatorUniValue)
        || comparatorUniValue < 0.0) {
      throw new IllegalArgumentException(
          String.format("Invalid comparatorUniValue (%s).", comparatorUniValue));
    }
    if (searchFromIndex < 0 || searchFromIndex > searchToIndex || searchToIndex > rowNums.length) {
      throw new IndexOutOfBoundsException(
          String.format(
              "Invalid search range [%s, %s) for %s rowNums.",
              searchFromIndex, searchToIndex, rowNums.length));
    }
  }
}
