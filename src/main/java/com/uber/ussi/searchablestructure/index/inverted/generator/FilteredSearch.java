/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.inverted.generator;

import com.carrotsearch.hppc.LongHashSet;
import com.uber.ussi.comparator.Comparator;
import com.uber.ussi.entity.meta.MetaFilter;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;
import com.uber.ussi.searchablestructure.RowNumAndSimilarity;
import com.uber.ussi.searchablestructure.SharedMinSimilarity;
import com.uber.ussi.searchablestructure.TopResults;
import com.uber.ussi.searchablestructure.inverted.KeyAndPrefixFilteringData;
import com.uber.ussi.utils.BoundedSizeMaxHeap;
import com.uber.ussi.utils.MathUtils;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.LongFunction;
import javax.annotation.Nullable;

/**
 * Key-major filtered-scan candidate generation over uni-sorted inverted lists.
 *
 * <p>The query's keys are visited cheapest first, and each one's inverted list is narrowed to the
 * rows that length filtering admits. Every candidate is then scored through the comparator, so this
 * generator works for every inverted index type.
 *
 * <p>Public only for the sibling inverted index packages.
 */
public final class FilteredSearch {
  /**
   * Below this range size, {@link #getFirstMatchingUniValue} and {@link #getLastMatchingUniValue}
   * scan linearly instead of binary searching.
   */
  private static final int MIN_NUM_CANDIDATES_FOR_BINARY_SEARCH = 32;

  private FilteredSearch() {}

  /**
   * Generates and scores candidates for {@code query}. Length filtering compares uni values, so the
   * query's is read from {@code indexedQuery}, the form the indexed rows are in.
   */
  public static List<RowNumAndSimilarity> search(
      Comparator comparator,
      LongTermsAndValues query,
      LongTermsAndValues indexedQuery,
      @Nullable MetaFilter metadataFilter,
      float minSimilarity,
      int maxResults,
      KeyAndPrefixFilteringData[] queryKeys,
      Context context,
      RowFilter rowFilter,
      LongFunction<LongTermsAndValues> verificationRowLookup,
      SharedMinSimilarity sharedMinSimilarity) {
    BoundedSizeMaxHeap<RowNumAndSimilarity> rows = TopResults.newTopResultsHeap(maxResults);
    double currentMinSimilarity = Math.max(minSimilarity, sharedMinSimilarity.get());
    // The prefix is chosen for the minimum similarity already proved rather than the one the
    // caller asked for. A prefix chosen for a lower one covers keys that no row reaching the
    // answer can be found under, and the keys a search would then read grow with the number of
    // searches sharing the proof.
    CandidateIterator candidates =
        new CandidateIterator(
            comparator, context, queryKeys, indexedQuery.getUniValue(), currentMinSimilarity);
    while (candidates.hasNext()) {
      long rowNum = candidates.next();
      // Another shard may have proved a higher minimum similarity since this one last looked.
      float publishedMinSimilarity = sharedMinSimilarity.get();
      if (publishedMinSimilarity > currentMinSimilarity) {
        currentMinSimilarity = publishedMinSimilarity;
        candidates.setMinSimilarity(currentMinSimilarity);
      }
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
        double tightenedMinSimilarity = TopResults.getConservativeMinSimilarity(rows);
        if (tightenedMinSimilarity > currentMinSimilarity) {
          currentMinSimilarity = tightenedMinSimilarity;
          candidates.setMinSimilarity(currentMinSimilarity);
          sharedMinSimilarity.raiseTo((float) currentMinSimilarity);
        }
      }
    }
    return rows.toList();
  }

  /**
   * Returns the inclusive lower bound of a key's matching row range within [searchFromIndex,
   * searchToIndex). Re-narrowing a key's previous range as minSimilarity rises is sound because a
   * matching range only ever shrinks. Endpoint checks resolve it in O(1) when they agree, since
   * rowNums is uni-sorted; otherwise short ranges are scanned and long ones binary searched.
   */
  public static int getFirstMatchingUniValue(
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
   * Returns the exclusive upper bound of a key's matching row range, the mirror of {@link
   * #getFirstMatchingUniValue} and narrowed the same way.
   */
  public static int getLastMatchingUniValue(
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

  public interface Context extends SearchContext {
    /**
     * Returns the most a qualifying candidate may leave unmatched, against which the traversal
     * compares the partial unilateral value accumulated over the keys it has visited: once the
     * accumulation exceeds it, no unseen row can qualify. Keys are not always the record's own
     * terms, so a structure whose bound is stated over terms reads {@code recordUniValue} rather
     * than {@code keysUniValue}.
     */
    double getMaxPrefixSum(
        double keysUniValue, double recordUniValue, double minSimilarity);

    /** Returns the uni-sorted inverted list of a key, empty when the key is unindexed. */
    long[] getRowNums(long key);
  }

  /**
   * Iterates deduplicated candidates in nondecreasing prefix cost. Takes ownership of the
   * {@code keyData} it is handed and sorts it in place. Not an {@code Iterator}, whose element
   * type would box every row number it yields.
   */
  static final class CandidateIterator {
    private final Comparator comparator;
    private final Context context;
    private final KeyAndPrefixFilteringData[] keyData;
    private final double keysUniValue;
    private final double comparatorUniValue;
    private final LongHashSet generatedRowNums;
    private double minSimilarity;
    private double maxPrefixSum;
    private final MathUtils.StableSumAccumulator prefixSumAccumulator;
    private double currentKeyPrefixSum;
    private int keyIndex;
    private long[] currentRowNums;
    private int currentRowStartIndex;
    private int currentRowEndIndex;
    private boolean nextRowPrepared;
    private long nextRowNum;

    CandidateIterator(
        Comparator comparator,
        Context context,
        KeyAndPrefixFilteringData[] keyData,
        double comparatorUniValue,
        double minSimilarity) {
      this.comparator = comparator;
      this.context = context;
      this.keyData = keyData;
      Arrays.sort(this.keyData);
      MathUtils.StableSumAccumulator keysUniValueAccumulator =
          new MathUtils.StableSumAccumulator();
      for (KeyAndPrefixFilteringData key : this.keyData) {
        keysUniValueAccumulator.add(key.getUniTransformedValue());
      }
      this.keysUniValue = keysUniValueAccumulator.getSum();
      this.comparatorUniValue = comparatorUniValue;
      this.generatedRowNums = new LongHashSet();
      this.minSimilarity = minSimilarity;
      this.maxPrefixSum =
          context.getMaxPrefixSum(keysUniValue, comparatorUniValue, minSimilarity);
      this.prefixSumAccumulator = new MathUtils.StableSumAccumulator();
      this.currentKeyPrefixSum = 0.0;
      this.keyIndex = -1;
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
      this.maxPrefixSum =
          context.getMaxPrefixSum(keysUniValue, comparatorUniValue, minSimilarity);
      if (keyIndex < 0 || currentRowStartIndex >= currentRowEndIndex) {
        return;
      }
      if (currentKeyPrefixSum > maxPrefixSum) {
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

    boolean hasNext() {
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
        if (keyIndex >= keyData.length - 1
            || prefixSumAccumulator.getSum() > maxPrefixSum) {
          return false;
        }
        ++keyIndex;
        KeyAndPrefixFilteringData currentKey = keyData[keyIndex];
        currentKeyPrefixSum = prefixSumAccumulator.getSum();
        currentRowNums = context.getRowNums(currentKey.getKey());
        if (currentRowNums.length != currentKey.getNumRows()) {
          throw new IllegalStateException(
              String.format(
                  "Inconsistent inverted-list length for key %s.",
                  currentKey.getKey()));
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
        prefixSumAccumulator.add(currentKey.getUniTransformedValue());
      }
    }

    long next() {
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
    if (comparatorUniValue == Comparator.UNSET_UNI_VALUE
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
