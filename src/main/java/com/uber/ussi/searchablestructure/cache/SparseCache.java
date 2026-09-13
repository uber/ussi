/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.cache;

import com.carrotsearch.hppc.LongArrayList;
import com.carrotsearch.hppc.LongHashSet;
import com.carrotsearch.hppc.LongObjectHashMap;
import com.carrotsearch.hppc.cursors.LongCursor;
import com.carrotsearch.hppc.cursors.LongObjectCursor;
import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.config.NamespaceConfig.PopularTermDiscardScope;
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
  private final PopularTermDiscardScope popularTermDiscardScope;
  private final double fullReevaluationCacheSizeDecreaseFraction;
  private final MathUtils.ProportionConfidenceInterval1Sided popularityConfidenceTester;
  private final LongObjectHashMap<LongArrayList> termAndRowNumsIndex;
  private final LongHashSet discardedTerms;
  private int numRowsAtLastExactPopularityEvaluation;
  private boolean lastSearchUsedPreFilteringBruteForce;

  public SparseCache(NamespaceConfig namespaceConfig) {
    super(namespaceConfig);
    this.maxFractionIdsPerSparseKey = parseMaxFractionIdsPerSparseKey(namespaceConfig);
    this.popularTermDiscardScope = namespaceConfig.getCachePopularTermDiscardScope();
    this.fullReevaluationCacheSizeDecreaseFraction =
        parseFullReevaluationCacheSizeDecreaseFraction(namespaceConfig);
    this.popularityConfidenceTester =
        new MathUtils.ProportionConfidenceInterval1Sided(
            parseMaxFractionIdsPerSparseKeyConfidence(namespaceConfig));
    this.termAndRowNumsIndex = new LongObjectHashMap<>(CACHE_INITIAL_CAPACITY);
    this.discardedTerms = new LongHashSet();
  }

  @Override
  protected List<RowNumAndSimilarity> getNearestNeighborRowNumsLocked(
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
    updateDiscardedTermsAfterInsertion(record);
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
    updateDiscardedTermsAfterDeletion(record);
  }

  long[] getDiscardedTermsForTests() {
    long[] terms = discardedTerms.toArray();
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
    LongTermsAndValues discardedTermFreeQuery = record.newWithoutTerms(discardedTerms, comparator);
    if (discardedTermFreeQuery.termsLength() == 0) {
      return Collections.emptyList();
    }
    /*
     * The query is scored in whichever form the discard scope says the rows are scored in, so that
     * both sides of every comparison carry the same terms, and candidates are always generated
     * from the discarded-term-free form so that the popular terms' inverted lists go unvisited.
     */
    LongTermsAndValues verificationQuery =
        popularTermDiscardScope == PopularTermDiscardScope.CANDIDATES_ONLY
            ? record
            : discardedTermFreeQuery;
    /*
     * When metadata pre-filtering leaves only a small fraction of the cache, scanning the matching
     * rows directly is cheaper than candidate generation over inverted lists.
     */
    PreFilteringResult preFiltering =
        metadataFilteringModule.getMatchingRowNumsIfUnderLimit(
            metadataFilter, (int) (size() * MAX_ROWS_RATIO_TO_BRUTE_FORCE_PRE_FILTERING));
    if (preFiltering.isSuccess()) {
      lastSearchUsedPreFilteringBruteForce = true;
      return bruteForceSearch(
          verificationQuery, preFiltering.getRowNums(), minSimilarity, maxResults);
    }
    return invertedIndexSearch(
        verificationQuery, discardedTermFreeQuery, metadataFilter, minSimilarity, maxResults);
  }

  private List<RowNumAndSimilarity> bruteForceSearch(
      LongTermsAndValues query, LongHashSet matchingRowNums, float minSimilarity, int maxResults) {
    BoundedSizeMaxHeap<RowNumAndSimilarity> rows = createTopResultsHeap(maxResults);
    for (LongCursor rowNum : matchingRowNums) {
      LongTermsAndValues verificationRow = getVerificationRow(rowNum.value);
      if (verificationRow == null || !query.sharesAnyTerm(verificationRow)) {
        continue;
      }
      float similarity = (float) comparator.getSimilarity(query, verificationRow, minSimilarity);
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
   *
   * @param query the query in verification form, which is the only form the comparator can score.
   * @param discardedTermFreeQuery the query without its discarded terms, which supplies both the
   *     inverted lists to visit and the uni value prefix filtering budgets against, since the
   *     prefix mass accumulates over this form's terms.
   */
  private List<RowNumAndSimilarity> invertedIndexSearch(
      LongTermsAndValues query,
      LongTermsAndValues discardedTermFreeQuery,
      MetaFilter metadataFilter,
      float minSimilarity,
      int maxResults) {
    SparseKeyAndPrefixFilteringData[] termData =
        getTermAndPrefixFilteringData(discardedTermFreeQuery);
    Arrays.sort(termData);
    BoundedSizeMaxHeap<RowNumAndSimilarity> rows = createTopResultsHeap(maxResults);
    double currentMinSimilarity = minSimilarity;
    double maxPrefixSum =
        comparator.getMinPrefixSumForTermsAndValues(
            discardedTermFreeQuery.getUniValue(), currentMinSimilarity);
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
        LongTermsAndValues verificationRow = getVerificationRow(rowNum);
        if (verificationRow == null) {
          continue;
        }
        double similarity = comparator.getSimilarity(query, verificationRow, currentMinSimilarity);
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
                    discardedTermFreeQuery.getUniValue(), currentMinSimilarity);
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
   * Returns the row in the form the comparator scores it in, or null when the row does not exist or
   * the discard leaves it with no terms. Unlike the sparse index, this form is derived on the fly
   * because which terms are discarded changes as the cache mutates.
   */
  @Nullable
  private LongTermsAndValues getVerificationRow(long rowNum) {
    LongTermsAndValues termsAndValues = rowNumToTermsAndValuesMap.get(rowNum);
    if (termsAndValues == null
        || popularTermDiscardScope == PopularTermDiscardScope.CANDIDATES_ONLY) {
      return termsAndValues;
    }
    LongTermsAndValues verificationRow = termsAndValues.newWithoutTerms(discardedTerms, comparator);
    return verificationRow.termsLength() == 0 ? null : verificationRow;
  }

  private void updateDiscardedTermsAfterInsertion(LongTermsAndValues insertedRecord) {
    if (maxFractionIdsPerSparseKey == 1.0) {
      discardedTerms.clear();
      return;
    }

    /*
     * An insertion cannot make an untouched, unfiltered term more popular. Recheck the inserted
     * terms for newly-popular terms and the discarded set for those the larger sample readmits.
     */
    LongHashSet termsToReevaluate =
        new LongHashSet(discardedTerms.size() + insertedRecord.termsLength());
    for (LongCursor term : discardedTerms) {
      termsToReevaluate.add(term.value);
    }
    for (int i = 0; i < insertedRecord.termsLength(); ++i) {
      termsToReevaluate.add(insertedRecord.getTerm(i));
    }
    for (LongCursor term : termsToReevaluate) {
      updateDiscardedTerm(term.value);
    }
  }

  private void updateDiscardedTermsAfterDeletion(LongTermsAndValues deletedRecord) {
    if (maxFractionIdsPerSparseKey == 1.0) {
      discardedTerms.clear();
      return;
    }
    if (size()
        <= numRowsAtLastExactPopularityEvaluation
            * (1.0 - fullReevaluationCacheSizeDecreaseFraction)) {
      rebuildDiscardedTerms();
      return;
    }

    /*
     * Deleted terms may become less popular, while any currently filtered term may be readmitted.
     * An untouched term can become newly popular as the denominator shrinks; the periodic full
     * reevaluation above bounds how long such a decision can remain stale.
     */
    LongHashSet termsToReevaluate =
        new LongHashSet(discardedTerms.size() + deletedRecord.termsLength());
    for (LongCursor term : discardedTerms) {
      termsToReevaluate.add(term.value);
    }
    for (int i = 0; i < deletedRecord.termsLength(); ++i) {
      termsToReevaluate.add(deletedRecord.getTerm(i));
    }
    for (LongCursor term : termsToReevaluate) {
      updateDiscardedTerm(term.value);
    }
  }

  private void updateDiscardedTerm(long term) {
    if (shouldDiscardTerm(term)) {
      discardedTerms.add(term);
    } else {
      discardedTerms.remove(term);
    }
  }

  private boolean shouldDiscardTerm(long term) {
    LongArrayList invertedList = termAndRowNumsIndex.get(term);
    int numRows = size();
    int numRowsWithTerm = invertedList == null ? 0 : invertedList.size();
    if (numRows == 0 || numRowsWithTerm == 0) {
      return false;
    }
    if (numRowsWithTerm > numRows) {
      return true;
    }
    return popularityConfidenceTester.getConfidenceIntervalUpperBound(
            /* numTrials */ numRows, /* numSuccesses */ numRowsWithTerm)
        > maxFractionIdsPerSparseKey;
  }

  /** Rebuilds all popularity decisions and resets the exact-evaluation cache-size baseline. */
  private void rebuildDiscardedTerms() {
    discardedTerms.clear();
    int numRows = size();
    numRowsAtLastExactPopularityEvaluation = numRows;
    if (numRows == 0 || maxFractionIdsPerSparseKey == 1.0) {
      return;
    }
    for (LongObjectCursor<LongArrayList> entry : termAndRowNumsIndex) {
      updateDiscardedTerm(entry.key);
    }
  }

  private static BoundedSizeMaxHeap<RowNumAndSimilarity> createTopResultsHeap(int maxResults) {
    return new BoundedSizeMaxHeap<>(maxResults, RowNumAndSimilarity.TOP_RESULTS_HEAP_ORDER);
  }

  private static double parseMaxFractionIdsPerSparseKey(NamespaceConfig namespaceConfig) {
    return namespaceConfig.readDoubleCacheParam(
        Constants.MAX_FRACTION_IDS_PER_SPARSE_KEY,
        Constants.DEFAULT_MAX_FRACTION_IDS_PER_SPARSE_KEY);
  }

  private static double parseFullReevaluationCacheSizeDecreaseFraction(
      NamespaceConfig namespaceConfig) {
    return namespaceConfig.readDoubleCacheParam(
        Constants.FULL_REEVALUATION_CACHE_SIZE_DECREASE_FRACTION,
        Constants.DEFAULT_FULL_REEVALUATION_CACHE_SIZE_DECREASE_FRACTION);
  }

  /**
   * Parses the one-sided confidence of the interval used to declare a term as high-popularity. A
   * confidence of 0.5 (K-alpha of 0.0) degenerates to comparing the observed popularity against
   * maxFractionIdsPerSparseKey directly. The [0.5, 1.0] range is enforced by
   * ProportionConfidenceInterval1Sided.
   */
  private static double parseMaxFractionIdsPerSparseKeyConfidence(NamespaceConfig namespaceConfig) {
    return namespaceConfig.readDoubleCacheParam(
        Constants.MAX_FRACTION_IDS_PER_SPARSE_KEY_CONFIDENCE,
        Constants.DEFAULT_MAX_FRACTION_IDS_PER_SPARSE_KEY_CONFIDENCE);
  }
}
