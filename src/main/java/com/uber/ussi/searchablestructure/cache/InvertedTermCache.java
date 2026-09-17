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
import com.uber.ussi.searchablestructure.SharedMinSimilarity;
import com.uber.ussi.searchablestructure.TopResults;
import com.uber.ussi.searchablestructure.ParallelRowScan;
import com.uber.ussi.searchablestructure.inverted.KeyAndPrefixFilteringData;
import com.uber.ussi.searchablestructure.metadata.PreFilteringResult;
import com.uber.ussi.utils.BoundedSizeMaxHeap;
import com.uber.ussi.utils.ConfigKeys;
import com.uber.ussi.utils.MathUtils;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Writable inverted term cache with mutable lists.
 *
 * <p>Lists are mutable and kept in insertion order rather than uni-value-sorted, so candidate
 * generation uses prefix filtering without per-list length bounds, and results are limited to the
 * rows sharing at least one term with the query.
 *
 * <p>High-popularity terms are filtered dynamically. The cache sees an incrementally changing
 * sample rather than a complete dataset, so each cached row is a Bernoulli trial for containing a
 * term, and a term is excluded from comparisons when the upper bound of the one-sided confidence
 * interval of its true popularity exceeds max_fraction_ids_per_term. Decisions are reversible,
 * since lists and stored rows retain all terms; they are updated incrementally after mutations and
 * fully reevaluated once the cache shrinks enough for the lower denominator to matter.
 */
public final class InvertedTermCache extends Cache {
  private static final double MAX_ROWS_RATIO_TO_BRUTE_FORCE_PRE_FILTERING = 0.01;

  private final double maxFractionIdsPerTerm;
  private final PopularTermDiscardScope popularTermDiscardScope;
  private final double fullReevaluationCacheSizeDecreaseFraction;
  private final MathUtils.ProportionConfidenceInterval1Sided popularityConfidenceTester;
  private final LongObjectHashMap<LongArrayList> termAndRowNumsIndex;
  private final LongHashSet discardedTerms;
  private int numRowsAtLastExactPopularityEvaluation;
  private boolean lastSearchUsedPreFilteringBruteForce;

  public InvertedTermCache(NamespaceConfig namespaceConfig) {
    super(namespaceConfig);
    this.maxFractionIdsPerTerm = parseMaxFractionIdsPerTerm(namespaceConfig);
    this.popularTermDiscardScope = namespaceConfig.getCachePopularTermDiscardScope();
    this.fullReevaluationCacheSizeDecreaseFraction =
        parseFullReevaluationCacheSizeDecreaseFraction(namespaceConfig);
    this.popularityConfidenceTester =
        new MathUtils.ProportionConfidenceInterval1Sided(
            parseMaxFractionIdsPerTermConfidence(namespaceConfig));
    this.termAndRowNumsIndex = new LongObjectHashMap<>(CACHE_INITIAL_CAPACITY);
    this.discardedTerms = new LongHashSet();
  }

  @Override
  protected List<RowNumAndSimilarity> getNearestNeighborRowNumsLocked(
      int k, LongTermsAndValues record, MetaFilter metadataFilter, float minSimilarity) {
    if (k <= 0) {
      throw new IllegalArgumentException("k must be greater than 0.");
    }
    int numResults = Math.min(k, namespaceConfig.getMaxNumSimilarities());
    return search(record, metadataFilter, minSimilarity, numResults);
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
    // Score the query in whichever form the rows are scored in, so both sides of a comparison
    // carry the same terms; candidates always come from the discarded-term-free form.
    LongTermsAndValues verificationQuery =
        popularTermDiscardScope == PopularTermDiscardScope.CANDIDATES_ONLY
            ? record
            : discardedTermFreeQuery;
    // When pre-filtering leaves only a small fraction of the cache, scanning those rows directly
    // is cheaper than candidate generation over the inverted lists.
    PreFilteringResult preFiltering =
        metadataFilteringModule.getMatchingRowNumsIfUnderLimit(
            metadataFilter, (int) (size() * MAX_ROWS_RATIO_TO_BRUTE_FORCE_PRE_FILTERING));
    if (preFiltering.isSuccess()) {
      lastSearchUsedPreFilteringBruteForce = true;
      return bruteForceSearch(
          verificationQuery, preFiltering.getRowNums(), minSimilarity, maxResults);
    }
    return invertedListSearch(
        verificationQuery, discardedTermFreeQuery, metadataFilter, minSimilarity, maxResults);
  }

  private List<RowNumAndSimilarity> bruteForceSearch(
      LongTermsAndValues query, LongHashSet matchingRowNums, float minSimilarity, int maxResults) {
    return ParallelRowScan.searchCandidates(
        matchingRowNums,
        query,
        searchParallelism(),
        maxResults,
        minSimilarity,
        (rowNum, rows, sharedMinSimilarity) -> {
          LongTermsAndValues verificationRow = getVerificationRow(rowNum);
          if (verificationRow == null || !query.sharesAnyTerm(verificationRow)) {
            return;
          }
          float threshold =
              TopResults.tightenedMinSimilarity(rows, minSimilarity, sharedMinSimilarity);
          float similarity = (float) comparator.getSimilarity(query, verificationRow, threshold);
          if (similarity >= threshold) {
            rows.add(new RowNumAndSimilarity(rowNum, similarity));
          }
        });
  }

  /**
   * Generates candidates from the inverted lists of the query terms in nondecreasing prefix cost,
   * stopping once the accumulated uni-transformed prefix mass exceeds the budget implied by the
   * dynamically tightened minimum similarity. Candidates and the budget come from {@code
   * discardedTermFreeQuery}, whose terms the prefix mass accumulates over, while scoring uses
   * {@code query}, the only form the comparator can score.
   */
  private List<RowNumAndSimilarity> invertedListSearch(
      LongTermsAndValues query,
      LongTermsAndValues discardedTermFreeQuery,
      MetaFilter metadataFilter,
      float minSimilarity,
      int maxResults) {
    KeyAndPrefixFilteringData[] termData = getTermAndPrefixFilteringData(discardedTermFreeQuery);
    Arrays.sort(termData);
    BoundedSizeMaxHeap<RowNumAndSimilarity> rows = createTopResultsHeap(maxResults);
    double currentMinSimilarity = minSimilarity;
    double maxPrefixSum =
        comparator.getMaxPrefixSumForTermsAndValues(
            discardedTermFreeQuery.getUniValue(), currentMinSimilarity);
    MathUtils.StableSumAccumulator prefixSumAccumulator = new MathUtils.StableSumAccumulator();
    LongHashSet scannedRowNums = new LongHashSet();
    for (KeyAndPrefixFilteringData term : termData) {
      if (prefixSumAccumulator.getSum() > maxPrefixSum) {
        break;
      }
      LongArrayList invertedList = termAndRowNumsIndex.get(term.getKey());
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
                comparator.getMaxPrefixSumForTermsAndValues(
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

  private KeyAndPrefixFilteringData[] getTermAndPrefixFilteringData(LongTermsAndValues query) {
    KeyAndPrefixFilteringData[] termData = new KeyAndPrefixFilteringData[query.termsLength()];
    for (int i = 0; i < termData.length; ++i) {
      long term = query.getTerm(i);
      LongArrayList invertedList = termAndRowNumsIndex.get(term);
      termData[i] =
          new KeyAndPrefixFilteringData(
              term,
              invertedList == null ? 0 : invertedList.size(),
              comparator.getUniTransformedValue(query.getValue(i)));
    }
    return termData;
  }

  /**
   * Returns the row in the form the comparator scores it in, or null when the row is absent or the
   * discard leaves it with no terms. The form is derived per call because which terms are
   * discarded changes as the cache mutates.
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
    if (maxFractionIdsPerTerm == 1.0) {
      discardedTerms.clear();
      return;
    }

    // An insertion cannot make an untouched, unfiltered term more popular, so recheck only the
    // inserted terms and the discarded set the larger sample may readmit.
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
    if (maxFractionIdsPerTerm == 1.0) {
      discardedTerms.clear();
      return;
    }
    if (size()
        <= numRowsAtLastExactPopularityEvaluation
            * (1.0 - fullReevaluationCacheSizeDecreaseFraction)) {
      rebuildDiscardedTerms();
      return;
    }

    // An untouched term can still become popular as the denominator shrinks, which the full
    // reevaluation above bounds the staleness of.
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
        > maxFractionIdsPerTerm;
  }

  /** Rebuilds all popularity decisions and resets the exact-evaluation cache-size baseline. */
  private void rebuildDiscardedTerms() {
    discardedTerms.clear();
    int numRows = size();
    numRowsAtLastExactPopularityEvaluation = numRows;
    if (numRows == 0 || maxFractionIdsPerTerm == 1.0) {
      return;
    }
    for (LongObjectCursor<LongArrayList> entry : termAndRowNumsIndex) {
      updateDiscardedTerm(entry.key);
    }
  }

  private static BoundedSizeMaxHeap<RowNumAndSimilarity> createTopResultsHeap(int maxResults) {
    return new BoundedSizeMaxHeap<>(maxResults, RowNumAndSimilarity.TOP_RESULTS_HEAP_ORDER);
  }

  private static double parseMaxFractionIdsPerTerm(NamespaceConfig namespaceConfig) {
    return namespaceConfig.readDoubleCacheParam(
        ConfigKeys.MAX_FRACTION_IDS_PER_TERM,
        ConfigKeys.DEFAULT_MAX_FRACTION_IDS_PER_TERM);
  }

  private static double parseFullReevaluationCacheSizeDecreaseFraction(
      NamespaceConfig namespaceConfig) {
    return namespaceConfig.readDoubleCacheParam(
        ConfigKeys.FULL_REEVALUATION_CACHE_SIZE_DECREASE_FRACTION,
        ConfigKeys.DEFAULT_FULL_REEVALUATION_CACHE_SIZE_DECREASE_FRACTION);
  }

  /**
   * Parses the one-sided confidence used to declare a term high-popularity. A confidence of 0.5
   * degenerates to comparing the observed popularity against maxFractionIdsPerTerm directly.
   */
  private static double parseMaxFractionIdsPerTermConfidence(NamespaceConfig namespaceConfig) {
    return namespaceConfig.readDoubleCacheParam(
        ConfigKeys.MAX_FRACTION_IDS_PER_TERM_CONFIDENCE,
        ConfigKeys.DEFAULT_MAX_FRACTION_IDS_PER_TERM_CONFIDENCE);
  }
}
