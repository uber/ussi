/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.sparse;

import com.carrotsearch.hppc.LongHashSet;
import com.carrotsearch.hppc.LongIntHashMap;
import com.carrotsearch.hppc.LongObjectHashMap;
import com.carrotsearch.hppc.cursors.LongCursor;
import com.carrotsearch.hppc.cursors.LongIntCursor;
import com.carrotsearch.hppc.cursors.LongObjectCursor;
import com.uber.ussi.comparator.SignatureComparator;
import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.entity.meta.LongMeta;
import com.uber.ussi.entity.meta.MetaFilter;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;
import com.uber.ussi.error.IndexCreationError;
import com.uber.ussi.searchablestructure.RowNumAndSimilarity;
import com.uber.ussi.searchablestructure.index.Index;
import com.uber.ussi.searchablestructure.index.MetadataFilteredSearchExecutor;
import com.uber.ussi.searchablestructure.metadata.MetadataFilteringStrategy;
import com.uber.ussi.searchablestructure.sparse.SparseKeyAndPrefixFilteringData;
import com.uber.ussi.utils.BoundedSizeMaxHeap;
import com.uber.ussi.utils.Constants;
import com.uber.ussi.utils.MathUtils;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import javax.annotation.Nullable;

/** Shared sparse-key index implementation with length and unordered-prefix filtering. */
abstract class BaseSparseIndex extends Index {
  /**
   * Below this range size, getFirstMatchingUniValue/getLastMatchingUniValue use a linear scan
   * instead of binary search. The scan starts from the end closest to the expected boundary, so it
   * often beats binary search's fixed O(log n) cost at this range size.
   */
  private static final int MIN_NUM_CANDIDATES_FOR_BINARY_SEARCH = 32;

  private static final long[] EMPTY_ROW_NUMS = new long[0];

  @Nullable private final SignatureComparator signatureComparator;
  private final double maxFractionIdsPerSparseKey;
  private final LongHashSet filteredOutTerms;
  private final LongObjectHashMap<LongTermsAndValues> comparisonRowNumToTermsAndValuesMap;
  private final LongObjectHashMap<long[]> sparseKeyAndRowNumsIndex;
  private final MetadataFilteredSearchExecutor metadataFilteredSearchExecutor;

  BaseSparseIndex(
      NamespaceConfig namespaceConfig,
      LongObjectHashMap<LongTermsAndValues> rowNumToTermsAndValuesMap,
      LongObjectHashMap<LongMeta> rowNumToMetaMap) {
    this(
        namespaceConfig,
        rowNumToTermsAndValuesMap,
        rowNumToMetaMap,
        /* requireSignatureSupport */ false);
  }

  BaseSparseIndex(
      NamespaceConfig namespaceConfig,
      LongObjectHashMap<LongTermsAndValues> rowNumToTermsAndValuesMap,
      LongObjectHashMap<LongMeta> rowNumToMetaMap,
      boolean requireSignatureSupport) {
    super(namespaceConfig, rowNumToTermsAndValuesMap, rowNumToMetaMap);
    this.signatureComparator =
        comparator instanceof SignatureComparator ? (SignatureComparator) comparator : null;
    if (requireSignatureSupport
        && (signatureComparator == null || !signatureComparator.supportsSignatures())) {
      throw new IndexCreationError(
          "A signature-based index requires a comparator with a configured signature generator.");
    }
    this.maxFractionIdsPerSparseKey = parseMaxFractionIdsPerSparseKey(namespaceConfig);
    validateRows();
    this.filteredOutTerms = buildFilteredOutTerms();
    this.comparisonRowNumToTermsAndValuesMap = buildComparisonRows();
    this.sparseKeyAndRowNumsIndex = buildSparseKeyAndRowNumsIndex();
    this.metadataFilteredSearchExecutor =
        new MetadataFilteredSearchExecutor(
            metadataFilteringStrategy,
            MetadataFilteringStrategy.IN_FILTERING,
            /* autoAttemptPreFiltering */ true,
            MetadataFilteringStrategy.IN_FILTERING,
            this::getMatchingRowNumsIfUnderPreFilteringLimit,
            this::getPostFilteringMaxResults,
            this::matchesMetaFilter);
  }

  protected abstract double getMinPrefixSum(double sparseKeysUniValue, double minSimilarity);

  protected abstract SparseKeyAndUniTransformedValue[] getSparseKeysAndUniTransformedValues(
      LongTermsAndValues termsAndValues);

  protected abstract long[] getSparseKeys(LongTermsAndValues termsAndValues);

  protected final SignatureComparator getSignatureComparator() {
    return Objects.requireNonNull(
        signatureComparator, "This index does not have a signature-capable comparator.");
  }

  @Override
  public final List<RowNumAndSimilarity> getNearestNeighbors(
      int k, LongTermsAndValues record, MetaFilter metadataFilter) {
    if (k <= 0) {
      throw new IllegalArgumentException("k must be greater than 0.");
    }
    int numResults = Math.min(k, namespaceConfig.getMaxNumSimilarities());
    return search(record, metadataFilter, /* minSimilarity */ 0.0f, numResults);
  }

  @Override
  public final List<RowNumAndSimilarity> getSimilarRowNums(
      float minSimilarity, LongTermsAndValues record, MetaFilter metadataFilter) {
    if (minSimilarity < 0.0f || minSimilarity > 1.0f) {
      throw new IllegalArgumentException("minSimilarity must be in the range [0.0, 1.0].");
    }
    return search(record, metadataFilter, minSimilarity, namespaceConfig.getMaxNumSimilarities());
  }

  @Override
  protected final boolean supportsInFiltering() {
    return true;
  }

  final MetadataFilteringStrategy getResolvedMetadataFilteringStrategyForLastSearchForTests() {
    return metadataFilteredSearchExecutor.getResolvedMetadataFilteringStrategyForLastSearch();
  }

  final int getNumIndexedSparseKeysForTests() {
    return sparseKeyAndRowNumsIndex.size();
  }

  final long[] getFilteredOutTermsForTests() {
    long[] terms = filteredOutTerms.toArray();
    Arrays.sort(terms);
    return terms;
  }

  final boolean hasSparseKeyPopularityFiltering() {
    return maxFractionIdsPerSparseKey < 1.0;
  }

  final long[] getRowNumsForSparseKeyForTests(long sparseKey) {
    return getRawRowNums(sparseKey).clone();
  }

  final LongTermsAndValues getComparisonTermsAndValuesForTests(long rowNum) {
    return comparisonRowNumToTermsAndValuesMap.get(rowNum);
  }

  /**
   * Returns the inclusive lower bound of a sparse key's matching row range within [searchFromIndex,
   * searchToIndex) of rowNums for the current comparatorUniValue/minSimilarity. Called with (0,
   * rowNums.length) for a sparse key's first window, and with the key's previous [first, last)
   * range on later calls as minSimilarity rises in CandidateIterator. This is sound because a
   * sparse key's matching range only shrinks as minSimilarity rises, never grows. Tries these
   * tiers, cheapest first.
   *
   * <p>Tier 1, O(1). One of the range's endpoints already resolves the search.
   *
   * <p>Tier 2, O(1). The endpoints share the same uniValue. Since rowNums is uniValue-sorted, every
   * row between them shares it too, so tier 1's checks cover the whole range.
   *
   * <p>Tier 3. Range smaller than MIN_NUM_CANDIDATES_FOR_BINARY_SEARCH. Linear scan from
   * searchFromIndex.
   *
   * <p>Tier 4. Otherwise, binary search.
   */
  final int getFirstMatchingUniValue(
      long[] rowNums,
      double comparatorUniValue,
      double minSimilarity,
      int searchFromIndex,
      int searchToIndex) {
    validateUniValueSearch(rowNums, comparatorUniValue, searchFromIndex, searchToIndex);
    if (searchFromIndex >= searchToIndex) {
      return searchFromIndex;
    }
    /**
     * The smallest-uniValue row in range already fails "comparator is smaller", so every row in
     * range does too. None can be a valid first index.
     */
    if (isSmallerThanAndNotSimilar(comparatorUniValue, rowNums[searchFromIndex], minSimilarity)) {
      return searchFromIndex;
    }
    /**
     * The largest-uniValue row in range still fails "comparator is greater", so the boundary has
     * not been reached yet. It lies at or beyond searchToIndex.
     */
    if (isGreaterThanAndNotSimilar(comparatorUniValue, rowNums[searchToIndex - 1], minSimilarity)) {
      return searchToIndex;
    }
    /**
     * Both endpoint checks above failed. If the endpoints share the same uniValue, every row
     * between them shares it too (rowNums is uniValue-sorted), so the check above holds for the
     * whole range, not just the endpoint.
     */
    if (getUniValue(rowNums[searchFromIndex]) == getUniValue(rowNums[searchToIndex - 1])) {
      return searchFromIndex;
    }

    // A genuine boundary lies strictly inside (searchFromIndex, searchToIndex).
    if (searchToIndex - searchFromIndex <= MIN_NUM_CANDIDATES_FOR_BINARY_SEARCH) {
      /**
       * On a re-narrowing call, the boundary tends to sit close to searchFromIndex because it only
       * moves toward searchToIndex as minSimilarity rises. Scanning forward often finishes early.
       */
      int index = searchFromIndex;
      while (index < searchToIndex
          && isGreaterThanAndNotSimilar(comparatorUniValue, rowNums[index], minSimilarity)) {
        ++index;
      }
      return index;
    }
    int low = searchFromIndex;
    int high = searchToIndex;
    while (low < high) {
      int middle = low + (high - low) / 2;
      if (isGreaterThanAndNotSimilar(comparatorUniValue, rowNums[middle], minSimilarity)) {
        low = middle + 1;
      } else {
        high = middle;
      }
    }
    return low;
  }

  /**
   * Symmetric to getFirstMatchingUniValue, for the exclusive upper bound. See there for the tier
   * breakdown. The linear scan tier runs backward from searchToIndex - 1, the end expected to be
   * closest to the boundary here.
   */
  final int getLastMatchingUniValue(
      long[] rowNums,
      double comparatorUniValue,
      double minSimilarity,
      int searchFromIndex,
      int searchToIndex) {
    validateUniValueSearch(rowNums, comparatorUniValue, searchFromIndex, searchToIndex);
    if (searchFromIndex >= searchToIndex) {
      return searchFromIndex;
    }
    /**
     * Symmetric to getFirstMatchingUniValue's checks. The largest row in range already fails
     * "comparator is greater", so nothing in range is "too big". The upper bound is unconstrained
     * within this range.
     */
    if (isGreaterThanAndNotSimilar(comparatorUniValue, rowNums[searchToIndex - 1], minSimilarity)) {
      return searchToIndex;
    }
    /**
     * The smallest row in range already fails "comparator is smaller". The boundary was already
     * passed at or before searchFromIndex.
     */
    if (isSmallerThanAndNotSimilar(comparatorUniValue, rowNums[searchFromIndex], minSimilarity)) {
      return searchFromIndex;
    }
    /**
     * Symmetric to getFirstMatchingUniValue's analogous check. A shared uniValue at both endpoints
     * extends "isSmallerThanAndNotSimilar is false", just established at searchFromIndex, to the
     * entire range.
     */
    if (getUniValue(rowNums[searchFromIndex]) == getUniValue(rowNums[searchToIndex - 1])) {
      return searchToIndex;
    }

    if (searchToIndex - searchFromIndex <= MIN_NUM_CANDIDATES_FOR_BINARY_SEARCH) {
      /**
       * Symmetric to getFirstMatchingUniValue. The boundary tends to sit close to searchToIndex on
       * a re-narrowing call, so scan backward from there.
       */
      int index = searchToIndex - 1;
      while (index >= searchFromIndex
          && isSmallerThanAndNotSimilar(comparatorUniValue, rowNums[index], minSimilarity)) {
        --index;
      }
      return index + 1;
    }
    int low = searchFromIndex;
    int high = searchToIndex;
    while (low < high) {
      int middle = low + (high - low) / 2;
      if (isSmallerThanAndNotSimilar(comparatorUniValue, rowNums[middle], minSimilarity)) {
        high = middle;
      } else {
        low = middle + 1;
      }
    }
    return low;
  }

  private List<RowNumAndSimilarity> search(
      LongTermsAndValues record, MetaFilter metadataFilter, float minSimilarity, int maxResults) {
    if (maxResults == 0 || rowNumToTermsAndValuesMap.isEmpty()) {
      return Collections.emptyList();
    }
    LongTermsAndValues filteredRecord = record.newWithFilteredTerms(filteredOutTerms, comparator);
    if (filteredRecord.termsLength() == 0) {
      return Collections.emptyList();
    }
    return metadataFilteredSearchExecutor.search(
        metadataFilter,
        maxResults,
        (resolvedMetadataFilter, resolvedMaxResults) ->
            invertedIndexSearch(
                filteredRecord, resolvedMetadataFilter, minSimilarity, resolvedMaxResults),
        (candidateRowNums, resolvedMetadataFilter, resolvedMaxResults) ->
            searchCandidateRows(
                filteredRecord,
                candidateRowNums,
                resolvedMetadataFilter,
                minSimilarity,
                resolvedMaxResults));
  }

  private List<RowNumAndSimilarity> invertedIndexSearch(
      LongTermsAndValues query,
      @Nullable MetaFilter metadataFilter,
      float minSimilarity,
      int maxResults) {
    SparseKeyAndPrefixFilteringData[] sparseKeyData = getSparseKeyData(query);
    CandidateIterator candidates =
        new CandidateIterator(sparseKeyData, query.getUniValue(), minSimilarity);
    BoundedSizeMaxHeap<RowNumAndSimilarity> rows = createTopResultsHeap(maxResults);
    double currentMinSimilarity = minSimilarity;
    while (candidates.hasNext()) {
      long rowNum = candidates.next();
      if (!canScoreRow(rowNum, metadataFilter)) {
        continue;
      }
      LongTermsAndValues termsAndValues = comparisonRowNumToTermsAndValuesMap.get(rowNum);
      if (termsAndValues == null || termsAndValues.termsLength() == 0) {
        continue;
      }
      double similarity = comparator.getSimilarity(query, termsAndValues, currentMinSimilarity);
      if (similarity < currentMinSimilarity) {
        continue;
      }
      rows.add(new RowNumAndSimilarity(rowNum, (float) similarity));
      if (rows.isFull()) {
        double tightenedMinSimilarity = getConservativeMinSimilarity(rows);
        if (tightenedMinSimilarity > currentMinSimilarity) {
          currentMinSimilarity = tightenedMinSimilarity;
          candidates.setMinSimilarity(currentMinSimilarity);
        }
      }
    }
    return rows.toList();
  }

  /**
   * Scores the pre-filtered candidate rows sequentially. The shared-term restriction of the
   * inverted search is applied here as well, so both metadata filtering strategies return the same
   * rows.
   */
  private List<RowNumAndSimilarity> searchCandidateRows(
      LongTermsAndValues query,
      LongHashSet candidateRowNums,
      @Nullable MetaFilter metadataFilter,
      float minSimilarity,
      int maxResults) {
    BoundedSizeMaxHeap<RowNumAndSimilarity> rows = createTopResultsHeap(maxResults);
    double currentMinSimilarity = minSimilarity;
    for (LongCursor rowNum : candidateRowNums) {
      currentMinSimilarity =
          scoreRowAndUpdateMinSimilarity(
              rows,
              rowNum.value,
              comparisonRowNumToTermsAndValuesMap.get(rowNum.value),
              query,
              metadataFilter,
              currentMinSimilarity);
    }
    return rows.toList();
  }

  private double scoreRowAndUpdateMinSimilarity(
      BoundedSizeMaxHeap<RowNumAndSimilarity> rows,
      long rowNum,
      @Nullable LongTermsAndValues termsAndValues,
      LongTermsAndValues query,
      @Nullable MetaFilter metadataFilter,
      double minSimilarity) {
    if (termsAndValues == null
        || termsAndValues.termsLength() == 0
        || !query.sharesAnyTerm(termsAndValues)
        || !canScoreRow(rowNum, metadataFilter)) {
      return minSimilarity;
    }
    double similarity = comparator.getSimilarity(query, termsAndValues, minSimilarity);
    if (similarity < minSimilarity) {
      return minSimilarity;
    }
    rows.add(new RowNumAndSimilarity(rowNum, (float) similarity));
    if (!rows.isFull()) {
      return minSimilarity;
    }
    return Math.max(minSimilarity, getConservativeMinSimilarity(rows));
  }

  private boolean canScoreRow(long rowNum, @Nullable MetaFilter metadataFilter) {
    return !isDeleted(rowNum)
        && (metadataFilter == null || matchesMetaFilter(rowNum, metadataFilter));
  }

  private SparseKeyAndPrefixFilteringData[] getSparseKeyData(LongTermsAndValues termsAndValues) {
    SparseKeyAndUniTransformedValue[] sparseKeys =
        getSparseKeysAndUniTransformedValues(termsAndValues);
    SparseKeyAndPrefixFilteringData[] sparseKeyData =
        new SparseKeyAndPrefixFilteringData[sparseKeys.length];
    for (int i = 0; i < sparseKeys.length; ++i) {
      SparseKeyAndUniTransformedValue sparseKey = sparseKeys[i];
      if (!Double.isFinite(sparseKey.getUniTransformedValue())
          || sparseKey.getUniTransformedValue() < 0.0) {
        throw new IllegalArgumentException(
            String.format(
                "Invalid uni-transformed value (%s) for sparse key %s.",
                sparseKey.getUniTransformedValue(), sparseKey.getSparseKey()));
      }
      sparseKeyData[i] =
          new SparseKeyAndPrefixFilteringData(
              sparseKey.getSparseKey(),
              getRawRowNums(sparseKey.getSparseKey()).length,
              sparseKey.getUniTransformedValue());
    }
    return sparseKeyData;
  }

  /**
   * Identifies the high-popularity terms to discard. The index is built over the complete dataset,
   * so the observed popularity of a term is its true popularity and no confidence interval is
   * needed: a term is discarded when it occurs in more than floor(numRows *
   * maxFractionIdsPerSparseKey) rows. Confidence intervals are only used by the sparse cache, which
   * observes an incrementally growing sample.
   */
  private LongHashSet buildFilteredOutTerms() {
    LongIntHashMap numRowsByTerm = new LongIntHashMap();
    for (LongObjectCursor<LongTermsAndValues> entry : rowNumToTermsAndValuesMap) {
      for (int i = 0; i < entry.value.termsLength(); ++i) {
        long term = entry.value.getTerm(i);
        int numRows = numRowsByTerm.containsKey(term) ? numRowsByTerm.get(term) + 1 : 1;
        numRowsByTerm.put(term, numRows);
      }
    }
    int maxNumRowsPerTerm =
        (int) Math.floor(rowNumToTermsAndValuesMap.size() * maxFractionIdsPerSparseKey);
    LongHashSet termsToFilter = new LongHashSet();
    for (LongIntCursor entry : numRowsByTerm) {
      if (entry.value > maxNumRowsPerTerm) {
        termsToFilter.add(entry.key);
      }
    }
    return termsToFilter;
  }

  private LongObjectHashMap<LongTermsAndValues> buildComparisonRows() {
    if (filteredOutTerms.isEmpty()) {
      return rowNumToTermsAndValuesMap;
    }
    LongObjectHashMap<LongTermsAndValues> comparisonRows =
        new LongObjectHashMap<>(rowNumToTermsAndValuesMap.size());
    for (LongObjectCursor<LongTermsAndValues> entry : rowNumToTermsAndValuesMap) {
      comparisonRows.put(entry.key, entry.value.newWithFilteredTerms(filteredOutTerms, comparator));
    }
    return comparisonRows;
  }

  private LongObjectHashMap<long[]> buildSparseKeyAndRowNumsIndex() {
    LongObjectHashMap<ArrayList<RowNumAndUniValue>> mutableIndex = new LongObjectHashMap<>();
    for (LongObjectCursor<LongTermsAndValues> entry : comparisonRowNumToTermsAndValuesMap) {
      LongTermsAndValues termsAndValues = entry.value;
      LongHashSet distinctSparseKeys = LongHashSet.from(getSparseKeys(termsAndValues));
      for (LongCursor sparseKeyCursor : distinctSparseKeys) {
        long sparseKey = sparseKeyCursor.value;
        ArrayList<RowNumAndUniValue> invertedList = mutableIndex.get(sparseKey);
        if (invertedList == null) {
          invertedList = new ArrayList<>();
          mutableIndex.put(sparseKey, invertedList);
        }
        invertedList.add(new RowNumAndUniValue(entry.key, termsAndValues.getUniValue()));
      }
    }

    LongObjectHashMap<long[]> immutableIndex = new LongObjectHashMap<>(mutableIndex.size());
    for (LongObjectCursor<ArrayList<RowNumAndUniValue>> entry : mutableIndex) {
      entry.value.sort(null);
      long[] rowNums = new long[entry.value.size()];
      for (int i = 0; i < rowNums.length; ++i) {
        rowNums[i] = entry.value.get(i).getRowNum();
      }
      immutableIndex.put(entry.key, rowNums);
    }
    return immutableIndex;
  }

  private void validateRows() {
    for (LongObjectCursor<LongTermsAndValues> entry : rowNumToTermsAndValuesMap) {
      if (entry.value == null) {
        throw new IndexCreationError(
            String.format("rowNum %s has a null TermsAndValues record.", entry.key));
      }
      try {
        validateSparseRecord(entry.value, "row " + entry.key);
      } catch (IllegalArgumentException e) {
        String message = e.getMessage();
        throw new IndexCreationError(
            message == null ? String.format("row %s is invalid.", entry.key) : message);
      }
    }
  }

  private void validateSparseRecord(LongTermsAndValues termsAndValues, String source) {
    Objects.requireNonNull(termsAndValues, source + " is null.");
    if (termsAndValues.termsLength() == 0) {
      throw new IllegalArgumentException(source + " must have non-empty terms.");
    }
    if (termsAndValues.termsLength() != termsAndValues.valuesLength()) {
      throw new IllegalArgumentException(
          String.format("%s must have equal non-empty terms and values lengths.", source));
    }
    for (int i = 1; i < termsAndValues.termsLength(); ++i) {
      if (termsAndValues.getTerm(i - 1) >= termsAndValues.getTerm(i)) {
        throw new IllegalArgumentException(
            String.format("%s terms must be sorted and distinct.", source));
      }
    }
    double expectedUniValue = comparator.computeUniValue(termsAndValues);
    double actualUniValue = termsAndValues.getUniValue();
    double tolerance = MathUtils.EPSILON * Math.max(1.0, Math.abs(expectedUniValue));
    if (!Double.isFinite(actualUniValue)
        || actualUniValue < 0.0
        || Math.abs(expectedUniValue - actualUniValue) > tolerance) {
      throw new IllegalArgumentException(
          String.format(
              "%s has uniValue %s, expected %s for the configured comparator.",
              source, actualUniValue, expectedUniValue));
    }
  }

  private boolean isSmallerThanAndNotSimilar(
      double comparatorUniValue, long rowNum, double minSimilarity) {
    double rowUniValue = getUniValue(rowNum);
    return comparatorUniValue < rowUniValue
        && !comparator.mayPassLengthFiltering(comparatorUniValue, rowUniValue, minSimilarity);
  }

  private boolean isGreaterThanAndNotSimilar(
      double comparatorUniValue, long rowNum, double minSimilarity) {
    double rowUniValue = getUniValue(rowNum);
    return comparatorUniValue > rowUniValue
        && !comparator.mayPassLengthFiltering(comparatorUniValue, rowUniValue, minSimilarity);
  }

  private double getUniValue(long rowNum) {
    LongTermsAndValues termsAndValues = comparisonRowNumToTermsAndValuesMap.get(rowNum);
    if (termsAndValues == null) {
      throw new IllegalStateException(
          String.format("rowNum %s is absent from the sparse forward index.", rowNum));
    }
    return termsAndValues.getUniValue();
  }

  private long[] getRawRowNums(long sparseKey) {
    long[] rowNums = sparseKeyAndRowNumsIndex.get(sparseKey);
    return rowNums == null ? EMPTY_ROW_NUMS : rowNums;
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

  private static double parseMaxFractionIdsPerSparseKey(NamespaceConfig namespaceConfig) {
    String value = namespaceConfig.getIndexParams().get(Constants.MAX_FRACTION_IDS_PER_SPARSE_KEY);
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
              "%s must be in (0.0, 1.0], got %s.",
              Constants.MAX_FRACTION_IDS_PER_SPARSE_KEY, maxFraction));
    }
    return maxFraction;
  }

  private static BoundedSizeMaxHeap<RowNumAndSimilarity> createTopResultsHeap(int maxResults) {
    return new BoundedSizeMaxHeap<>(maxResults, RowNumAndSimilarity.TOP_RESULTS_HEAP_ORDER);
  }

  private static double getConservativeMinSimilarity(BoundedSizeMaxHeap<RowNumAndSimilarity> rows) {
    return Math.nextDown(rows.peek().getSimilarity());
  }

  /** Iterates deduplicated candidates in nondecreasing unordered-prefix cost. */
  private final class CandidateIterator implements Iterator<Long> {
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
        SparseKeyAndPrefixFilteringData[] sparseKeyData,
        double comparatorUniValue,
        double minSimilarity) {
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
      this.maxPrefixSum = getMinPrefixSum(sparseKeysUniValue, minSimilarity);
      this.prefixSumAccumulator = new MathUtils.StableSumAccumulator();
      this.currentSparseKeyPrefixSum = 0.0;
      this.sparseKeyIndex = -1;
      this.currentRowNums = EMPTY_ROW_NUMS;
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
      this.maxPrefixSum = getMinPrefixSum(sparseKeysUniValue, minSimilarity);
      if (sparseKeyIndex < 0 || currentRowStartIndex >= currentRowEndIndex) {
        return;
      }
      if (currentSparseKeyPrefixSum > maxPrefixSum) {
        currentRowStartIndex = currentRowEndIndex;
        return;
      }
      currentRowStartIndex =
          getFirstMatchingUniValue(
              currentRowNums,
              comparatorUniValue,
              minSimilarity,
              currentRowStartIndex,
              currentRowEndIndex);
      currentRowEndIndex =
          getLastMatchingUniValue(
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
        currentRowNums = getRawRowNums(currentSparseKey.getSparseKey());
        if (currentRowNums.length != currentSparseKey.getNumRows()) {
          throw new IllegalStateException(
              String.format(
                  "Inconsistent inverted-list length for sparse key %s.",
                  currentSparseKey.getSparseKey()));
        }
        currentRowStartIndex =
            getFirstMatchingUniValue(
                currentRowNums, comparatorUniValue, minSimilarity, 0, currentRowNums.length);
        currentRowEndIndex =
            getLastMatchingUniValue(
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
}
