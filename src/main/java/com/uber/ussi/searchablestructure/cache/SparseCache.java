/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.cache;

import com.carrotsearch.hppc.LongArrayList;
import com.carrotsearch.hppc.LongHashSet;
import com.carrotsearch.hppc.LongObjectHashMap;
import com.carrotsearch.hppc.cursors.LongCursor;
import com.carrotsearch.hppc.cursors.LongObjectCursor;
import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.entity.meta.MetaFilter;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;
import com.uber.ussi.searchablestructure.RowNumAndSimilarity;
import com.uber.ussi.searchablestructure.metadata.PreFilteringResult;
import com.uber.ussi.searchablestructure.sparse.SparseKeyAndPrefixFilteringData;
import com.uber.ussi.utils.BoundedSizeMaxHeap;
import com.uber.ussi.utils.Constants;
import com.uber.ussi.utils.MathUtils;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Writable sparse cache with a mutable inverted term index.
 *
 * <p>Inverted lists are mutable and kept in insertion order, unlike the immutable uni-value-sorted
 * inverted lists of the sparse index, so candidate generation uses unordered-prefix filtering
 * without per-list length bounds. As in the sparse index, results are limited to the rows sharing
 * at least one term with the query.
 *
 * <p>High-popularity terms are filtered dynamically. The cache observes an incrementally changing
 * sample of rows rather than a complete dataset, so each cached row is treated as a Bernoulli trial
 * for containing a term, and a term is excluded from comparisons when the upper bound of the
 * one-sided confidence interval of its true popularity exceeds max_fraction_ids_per_sparse_key.
 * This errs on the side of filtering when few rows have been observed, and the decision is
 * reversible: inverted lists and stored rows retain all terms. Popularity decisions are updated
 * incrementally after mutations, with a full reevaluation after the cache shrinks sufficiently to
 * account for the lower denominator across all terms.
 */
public final class SparseCache extends Cache {
  private static final double MAX_ROWS_RATIO_TO_BRUTE_FORCE_PRE_FILTERING = 0.01;

  private final double maxFractionIdsPerSparseKey;
  private final double fullReevaluationCacheSizeDecreaseFraction;
  private final MathUtils.ProportionConfidenceInterval1Sided filteringOutTermsConfidenceTester;
  private final LongObjectHashMap<LongArrayList> termAndRowNumsIndex;
  private final LongHashSet filteredOutTerms;
  private int numRowsAtLastExactPopularityEvaluation;
  private boolean lastSearchUsedPreFilteringBruteForce;

  public SparseCache(NamespaceConfig namespaceConfig) {
    super(namespaceConfig);
    this.maxFractionIdsPerSparseKey = parseMaxFractionIdsPerSparseKey(namespaceConfig);
    this.fullReevaluationCacheSizeDecreaseFraction =
        parseFullReevaluationCacheSizeDecreaseFraction(namespaceConfig);
    this.filteringOutTermsConfidenceTester =
        new MathUtils.ProportionConfidenceInterval1Sided(
            parseMaxFractionIdsPerSparseKeyConfidence(namespaceConfig));
    this.termAndRowNumsIndex = new LongObjectHashMap<>(CACHE_INITIAL_CAPACITY);
    this.filteredOutTerms = new LongHashSet();
  }

  @Override
  protected List<RowNumAndSimilarity> getNearestNeighborsLocked(
      int k, LongTermsAndValues record, MetaFilter metadataFilter) {
    if (k <= 0) {
      throw new IllegalArgumentException("k must be greater than 0.");
    }
    int numResults = Math.min(k, namespaceConfig.getMaxNumSimilarities());
    return search(record, metadataFilter, /* minSimilarity */ 0.0f, numResults);
  }

  @Override
  protected List<RowNumAndSimilarity> getSimilarRowNumsLocked(
      float minSimilarity, LongTermsAndValues record, MetaFilter metadataFilter) {
    if (minSimilarity < 0.0f || minSimilarity > 1.0f) {
      throw new IllegalArgumentException("minSimilarity must be in the range [0.0, 1.0].");
    }
    return search(record, metadataFilter, minSimilarity, namespaceConfig.getMaxNumSimilarities());
  }

  @Override
  protected void onRowInserted(long rowNum, LongTermsAndValues record) {
    for (int i = 0; i < record.termsLength(); ++i) {
      long term = record.getTerm(i);
      LongArrayList invertedList = termAndRowNumsIndex.get(term);
      if (invertedList == null) {
        invertedList = new LongArrayList();
        termAndRowNumsIndex.put(term, invertedList);
      }
      invertedList.add(rowNum);
    }
    updateFilteredOutTermsAfterInsertion(record);
    if (size() >= numRowsAtLastExactPopularityEvaluation) {
      numRowsAtLastExactPopularityEvaluation = size();
    }
  }

  @Override
  protected void onRowDeleted(long rowNum, LongTermsAndValues record) {
    for (int i = 0; i < record.termsLength(); ++i) {
      long term = record.getTerm(i);
      LongArrayList invertedList = termAndRowNumsIndex.get(term);
      if (invertedList == null) {
        continue;
      }
      invertedList.removeFirst(rowNum);
      if (invertedList.isEmpty()) {
        termAndRowNumsIndex.remove(term);
      }
    }
    updateFilteredOutTermsAfterDeletion(record);
  }

  long[] getFilteredOutTermsForTests() {
    long[] terms = filteredOutTerms.toArray();
    Arrays.sort(terms);
    return terms;
  }

  long[] getInvertedListForTests(long term) {
    LongArrayList invertedList = termAndRowNumsIndex.get(term);
    return invertedList == null ? new long[0] : invertedList.toArray();
  }

  boolean getLastSearchUsedPreFilteringBruteForceForTests() {
    return lastSearchUsedPreFilteringBruteForce;
  }

  private List<RowNumAndSimilarity> search(
      LongTermsAndValues record, MetaFilter metadataFilter, float minSimilarity, int maxResults) {
    lastSearchUsedPreFilteringBruteForce = false;
    if (maxResults == 0 || rowNumToTermsAndValuesMap.isEmpty()) {
      return Collections.emptyList();
    }
    LongTermsAndValues filteredQuery = record.newWithFilteredTerms(filteredOutTerms, comparator);
    if (filteredQuery.termsLength() == 0) {
      return Collections.emptyList();
    }
    /*
     * When metadata pre-filtering leaves only a small fraction of the cache, scanning the matching
     * rows directly is cheaper than candidate generation over inverted lists.
     */
    PreFilteringResult preFiltering =
        metadataFilteringModule.getMatchingRowNumsIfUnderLimit(
            metadataFilter, (int) (size() * MAX_ROWS_RATIO_TO_BRUTE_FORCE_PRE_FILTERING));
    if (preFiltering.isSuccess()) {
      lastSearchUsedPreFilteringBruteForce = true;
      return bruteForceSearch(filteredQuery, preFiltering.getRowNums(), minSimilarity, maxResults);
    }
    return invertedIndexSearch(filteredQuery, metadataFilter, minSimilarity, maxResults);
  }

  private List<RowNumAndSimilarity> bruteForceSearch(
      LongTermsAndValues query, LongHashSet matchingRowNums, float minSimilarity, int maxResults) {
    BoundedSizeMaxHeap<RowNumAndSimilarity> rows = createTopResultsHeap(maxResults);
    for (LongCursor rowNum : matchingRowNums) {
      LongTermsAndValues comparisonRow = getComparisonRow(rowNum.value);
      if (comparisonRow == null || !query.sharesAnyTerm(comparisonRow)) {
        continue;
      }
      float similarity = (float) comparator.getSimilarity(query, comparisonRow, minSimilarity);
      if (similarity >= minSimilarity) {
        rows.add(new RowNumAndSimilarity(rowNum.value, similarity));
      }
    }
    return rows.toList();
  }

  /**
   * Generates candidates from the inverted lists of the query terms in nondecreasing
   * unordered-prefix cost, stopping once the accumulated uni-transformed prefix mass exceeds the
   * budget implied by the dynamically tightened similarity threshold.
   */
  private List<RowNumAndSimilarity> invertedIndexSearch(
      LongTermsAndValues query, MetaFilter metadataFilter, float minSimilarity, int maxResults) {
    SparseKeyAndPrefixFilteringData[] termData = getTermAndPrefixFilteringData(query);
    Arrays.sort(termData);
    BoundedSizeMaxHeap<RowNumAndSimilarity> rows = createTopResultsHeap(maxResults);
    double currentMinSimilarity = minSimilarity;
    double maxPrefixSum =
        comparator.getMinPrefixSumForTermsAndValues(query.getUniValue(), currentMinSimilarity);
    MathUtils.StableSumAccumulator prefixSumAccumulator = new MathUtils.StableSumAccumulator();
    LongHashSet scannedRowNums = new LongHashSet();
    for (SparseKeyAndPrefixFilteringData term : termData) {
      if (prefixSumAccumulator.getSum() > maxPrefixSum) {
        break;
      }
      LongArrayList invertedList = termAndRowNumsIndex.get(term.getSparseKey());
      int invertedListSize = invertedList == null ? 0 : invertedList.size();
      for (int i = 0; i < invertedListSize; ++i) {
        long rowNum = invertedList.get(i);
        if (!scannedRowNums.add(rowNum) || !matchesMetaFilter(rowNum, metadataFilter)) {
          continue;
        }
        LongTermsAndValues comparisonRow = getComparisonRow(rowNum);
        if (comparisonRow == null) {
          continue;
        }
        double similarity = comparator.getSimilarity(query, comparisonRow, currentMinSimilarity);
        if (similarity < currentMinSimilarity) {
          continue;
        }
        rows.add(new RowNumAndSimilarity(rowNum, (float) similarity));
        if (rows.isFull()) {
          double tightenedMinSimilarity = Math.nextDown(rows.peek().getSimilarity());
          if (tightenedMinSimilarity > currentMinSimilarity) {
            currentMinSimilarity = tightenedMinSimilarity;
            maxPrefixSum =
                comparator.getMinPrefixSumForTermsAndValues(
                    query.getUniValue(), currentMinSimilarity);
            if (prefixSumAccumulator.getSum() > maxPrefixSum) {
              break;
            }
          }
        }
      }
      prefixSumAccumulator.add(term.getUniTransformedValue());
    }
    return rows.toList();
  }

  private SparseKeyAndPrefixFilteringData[] getTermAndPrefixFilteringData(
      LongTermsAndValues query) {
    SparseKeyAndPrefixFilteringData[] termData =
        new SparseKeyAndPrefixFilteringData[query.termsLength()];
    for (int i = 0; i < termData.length; ++i) {
      long term = query.getTerm(i);
      LongArrayList invertedList = termAndRowNumsIndex.get(term);
      termData[i] =
          new SparseKeyAndPrefixFilteringData(
              term,
              invertedList == null ? 0 : invertedList.size(),
              comparator.getUniTransformedValue(query.getValue(i)));
    }
    return termData;
  }

  /**
   * Returns the row without the currently filtered-out terms, or null when the row does not exist
   * or has no remaining terms. Unlike the sparse index, comparison rows are derived on the fly
   * because the filtered-out terms change as the cache mutates.
   */
  @Nullable
  private LongTermsAndValues getComparisonRow(long rowNum) {
    LongTermsAndValues termsAndValues = rowNumToTermsAndValuesMap.get(rowNum);
    if (termsAndValues == null) {
      return null;
    }
    LongTermsAndValues comparisonRow =
        termsAndValues.newWithFilteredTerms(filteredOutTerms, comparator);
    return comparisonRow.termsLength() == 0 ? null : comparisonRow;
  }

  private void updateFilteredOutTermsAfterInsertion(LongTermsAndValues insertedRecord) {
    if (maxFractionIdsPerSparseKey == 1.0) {
      filteredOutTerms.clear();
      return;
    }

    /*
     * An insertion cannot make an untouched, unfiltered term more popular. Recheck the inserted
     * terms for newly-popular terms and the filtered set for terms that the larger sample readmits.
     */
    LongHashSet termsToReevaluate =
        new LongHashSet(filteredOutTerms.size() + insertedRecord.termsLength());
    for (LongCursor term : filteredOutTerms) {
      termsToReevaluate.add(term.value);
    }
    for (int i = 0; i < insertedRecord.termsLength(); ++i) {
      termsToReevaluate.add(insertedRecord.getTerm(i));
    }
    for (LongCursor term : termsToReevaluate) {
      updateFilteredOutTerm(term.value);
    }
  }

  private void updateFilteredOutTermsAfterDeletion(LongTermsAndValues deletedRecord) {
    if (maxFractionIdsPerSparseKey == 1.0) {
      filteredOutTerms.clear();
      return;
    }
    if (size()
        <= numRowsAtLastExactPopularityEvaluation
            * (1.0 - fullReevaluationCacheSizeDecreaseFraction)) {
      rebuildFilteredOutTerms();
      return;
    }

    /*
     * Deleted terms may become less popular, while any currently filtered term may be readmitted.
     * An untouched term can become newly popular as the denominator shrinks; the periodic full
     * reevaluation above bounds how long such a decision can remain stale.
     */
    LongHashSet termsToReevaluate =
        new LongHashSet(filteredOutTerms.size() + deletedRecord.termsLength());
    for (LongCursor term : filteredOutTerms) {
      termsToReevaluate.add(term.value);
    }
    for (int i = 0; i < deletedRecord.termsLength(); ++i) {
      termsToReevaluate.add(deletedRecord.getTerm(i));
    }
    for (LongCursor term : termsToReevaluate) {
      updateFilteredOutTerm(term.value);
    }
  }

  private void updateFilteredOutTerm(long term) {
    if (shouldFilterOutTerm(term)) {
      filteredOutTerms.add(term);
    } else {
      filteredOutTerms.remove(term);
    }
  }

  private boolean shouldFilterOutTerm(long term) {
    LongArrayList invertedList = termAndRowNumsIndex.get(term);
    int numRows = size();
    int numRowsWithTerm = invertedList == null ? 0 : invertedList.size();
    if (numRows == 0 || numRowsWithTerm == 0) {
      return false;
    }
    if (numRowsWithTerm > numRows) {
      return true;
    }
    return filteringOutTermsConfidenceTester.getConfidenceIntervalUpperBound(
            /* numTrials */ numRows, /* numSuccesses */ numRowsWithTerm)
        > maxFractionIdsPerSparseKey;
  }

  /** Rebuilds all popularity decisions and resets the exact-evaluation cache-size baseline. */
  private void rebuildFilteredOutTerms() {
    filteredOutTerms.clear();
    int numRows = size();
    numRowsAtLastExactPopularityEvaluation = numRows;
    if (numRows == 0 || maxFractionIdsPerSparseKey == 1.0) {
      return;
    }
    for (LongObjectCursor<LongArrayList> entry : termAndRowNumsIndex) {
      updateFilteredOutTerm(entry.key);
    }
  }

  private static BoundedSizeMaxHeap<RowNumAndSimilarity> createTopResultsHeap(int maxResults) {
    return new BoundedSizeMaxHeap<>(maxResults, RowNumAndSimilarity.TOP_RESULTS_HEAP_ORDER);
  }

  private static double parseMaxFractionIdsPerSparseKey(NamespaceConfig namespaceConfig) {
    String value = namespaceConfig.getCacheParams().get(Constants.MAX_FRACTION_IDS_PER_SPARSE_KEY);
    if (value == null || value.trim().isEmpty()) {
      return Constants.DEFAULT_MAX_FRACTION_IDS_PER_SPARSE_KEY;
    }
    double maxFraction;
    try {
      maxFraction = Double.parseDouble(value);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(
          String.format(
              "%s must be a double in (0.0, 1.0].", Constants.MAX_FRACTION_IDS_PER_SPARSE_KEY),
          e);
    }
    if (!(maxFraction > 0.0 && maxFraction <= 1.0)) {
      throw new IllegalArgumentException(
          String.format(
              "%s must be a double in (0.0, 1.0].", Constants.MAX_FRACTION_IDS_PER_SPARSE_KEY));
    }
    return maxFraction;
  }

  private static double parseFullReevaluationCacheSizeDecreaseFraction(
      NamespaceConfig namespaceConfig) {
    String value =
        namespaceConfig
            .getCacheParams()
            .get(Constants.FULL_REEVALUATION_CACHE_SIZE_DECREASE_FRACTION);
    if (value == null || value.trim().isEmpty()) {
      return Constants.DEFAULT_FULL_REEVALUATION_CACHE_SIZE_DECREASE_FRACTION;
    }
    double decreaseFraction;
    try {
      decreaseFraction = Double.parseDouble(value);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(
          String.format(
              "%s must be a double in [0.0, 1.0].",
              Constants.FULL_REEVALUATION_CACHE_SIZE_DECREASE_FRACTION),
          e);
    }
    if (!(decreaseFraction >= 0.0 && decreaseFraction <= 1.0)) {
      throw new IllegalArgumentException(
          String.format(
              "%s must be a double in [0.0, 1.0].",
              Constants.FULL_REEVALUATION_CACHE_SIZE_DECREASE_FRACTION));
    }
    return decreaseFraction;
  }

  /**
   * Parses the one-sided confidence of the interval used to declare a term as high-popularity. A
   * confidence of 0.5 (K-alpha of 0.0) degenerates to comparing the observed popularity against
   * maxFractionIdsPerSparseKey directly. The [0.5, 1.0] range is enforced by
   * ProportionConfidenceInterval1Sided.
   */
  private static double parseMaxFractionIdsPerSparseKeyConfidence(NamespaceConfig namespaceConfig) {
    String value =
        namespaceConfig.getCacheParams().get(Constants.MAX_FRACTION_IDS_PER_SPARSE_KEY_CONFIDENCE);
    if (value == null || value.trim().isEmpty()) {
      return Constants.DEFAULT_MAX_FRACTION_IDS_PER_SPARSE_KEY_CONFIDENCE;
    }
    try {
      return Double.parseDouble(value);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(
          String.format(
              "%s must be a double in [0.5, 1.0].",
              Constants.MAX_FRACTION_IDS_PER_SPARSE_KEY_CONFIDENCE),
          e);
    }
  }
}
