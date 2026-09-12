/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.sparse;

import com.carrotsearch.hppc.LongDoubleHashMap;
import com.carrotsearch.hppc.LongHashSet;
import com.carrotsearch.hppc.LongIntHashMap;
import com.carrotsearch.hppc.LongObjectHashMap;
import com.carrotsearch.hppc.cursors.LongCursor;
import com.carrotsearch.hppc.cursors.LongIntCursor;
import com.carrotsearch.hppc.cursors.LongObjectCursor;
import com.uber.ussi.comparator.SignatureComparator;
import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.config.NamespaceConfig.SparseCandidateGenerator;
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
import java.util.List;
import java.util.Objects;
import javax.annotation.Nullable;

/** Shared sparse-key index implementation with length and unordered-prefix filtering. */
abstract class BaseSparseIndex extends Index {
  private static final long[] EMPTY_ROW_NUMS = new long[0];
  private static final float[] EMPTY_VALUES = new float[0];

  @Nullable private final SignatureComparator signatureComparator;
  private final SparseCandidateGenerator sparseCandidateGenerator;
  private final boolean scoresFromConjunction;
  private final double maxFractionIdsPerSparseKey;
  private final LongHashSet filteredOutTerms;
  private final LongObjectHashMap<LongTermsAndValues> comparisonRowNumToTermsAndValuesMap;
  private final LongDoubleHashMap rowNumToUniValue;
  private final LongObjectHashMap<SparseInvertedList> sparseKeyToInvertedList;
  private final MetadataFilteredSearchExecutor metadataFilteredSearchExecutor;
  private final SearchContext searchContext = new SearchContext();

  BaseSparseIndex(
      NamespaceConfig namespaceConfig,
      LongObjectHashMap<LongTermsAndValues> rowNumToTermsAndValuesMap,
      LongObjectHashMap<LongMeta> rowNumToMetaMap,
      SparseKeyType sparseKeyType) {
    super(namespaceConfig, rowNumToTermsAndValuesMap, rowNumToMetaMap);
    this.signatureComparator =
        comparator instanceof SignatureComparator ? (SignatureComparator) comparator : null;
    if (sparseKeyType.requiresSignatureSupport()
        && (signatureComparator == null || !signatureComparator.supportsSignatures())) {
      throw new IndexCreationError(
          "A signature-based index requires a comparator with a configured signature generator.");
    }
    this.sparseCandidateGenerator = namespaceConfig.getSparseCandidateGenerator();
    this.scoresFromConjunction =
        sparseCandidateGenerator == SparseCandidateGenerator.SPARS_MERGE
            && sparseKeyType.supportsConjunctionScoring();
    this.maxFractionIdsPerSparseKey = parseMaxFractionIdsPerSparseKey(namespaceConfig);
    validateRows();
    this.filteredOutTerms = buildFilteredOutTerms();
    this.comparisonRowNumToTermsAndValuesMap = buildComparisonRows();
    this.rowNumToUniValue = buildRowNumToUniValue(comparisonRowNumToTermsAndValuesMap);
    this.sparseKeyToInvertedList =
        buildSparseInvertedIndex(comparisonRowNumToTermsAndValuesMap);
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

  protected abstract float getValueAtSparseKey(LongTermsAndValues termsAndValues, long sparseKey);

  protected final SignatureComparator getSignatureComparator() {
    return Objects.requireNonNull(
        signatureComparator, "This index does not have a signature-capable comparator.");
  }

  @Override
  public final List<RowNumAndSimilarity> getNearestNeighborRowNums(
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
    return sparseKeyToInvertedList.size();
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

  /** Returns the row as the comparator sees it, with the high-popularity terms already dropped. */
  final LongTermsAndValues getComparisonTermsAndValues(long rowNum) {
    return comparisonRowNumToTermsAndValuesMap.get(rowNum);
  }

  /** See {@link SparseFilteredSearch#getFirstMatchingUniValue}. */
  final int getFirstMatchingUniValueForTests(
      long[] rowNums,
      double comparatorUniValue,
      double minSimilarity,
      int searchFromIndex,
      int searchToIndex) {
    return SparseFilteredSearch.getFirstMatchingUniValue(
        comparator,
        searchContext,
        rowNums,
        comparatorUniValue,
        minSimilarity,
        searchFromIndex,
        searchToIndex);
  }

  /** See {@link SparseFilteredSearch#getLastMatchingUniValue}. */
  final int getLastMatchingUniValueForTests(
      long[] rowNums,
      double comparatorUniValue,
      double minSimilarity,
      int searchFromIndex,
      int searchToIndex) {
    return SparseFilteredSearch.getLastMatchingUniValue(
        comparator,
        searchContext,
        rowNums,
        comparatorUniValue,
        minSimilarity,
        searchFromIndex,
        searchToIndex);
  }

  final SearchContext getSearchContext() {
    return searchContext;
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
    if (sparseCandidateGenerator == SparseCandidateGenerator.SPARS_MERGE) {
      return SparseMergeSearch.search(
          comparator,
          query,
          metadataFilter,
          minSimilarity,
          maxResults,
          collectSparseMergeSearchQueryKeys(query),
          searchContext,
          this::canScoreRow,
          scoresFromConjunction,
          this::getComparisonTermsAndValues);
    }
    return SparseFilteredSearch.search(
        comparator,
        query,
        metadataFilter,
        minSimilarity,
        maxResults,
        collectSparseFilteredSearchQueryKeys(query),
        searchContext,
        this::canScoreRow,
        this::getComparisonTermsAndValues);
  }

  /**
   * Returns the query's indexed sparse keys, shortest inverted list first so that the merge reaches
   * its pruning bound on the selective keys before paying for the popular ones.
   */
  private SparseMergeSearch.QueryKey[] collectSparseMergeSearchQueryKeys(LongTermsAndValues query) {
    SparseKeyAndUniTransformedValue[] sparseKeys = getSparseKeysAndUniTransformedValues(query);
    ArrayList<SparseMergeSearch.QueryKey> queryKeys = new ArrayList<>(sparseKeys.length);
    for (SparseKeyAndUniTransformedValue sparseKey : sparseKeys) {
      SparseInvertedList invertedList = sparseKeyToInvertedList.get(sparseKey.getSparseKey());
      if (invertedList == null || invertedList.size() == 0) {
        continue;
      }
      queryKeys.add(
          new SparseMergeSearch.QueryKey(
              invertedList,
              getValueAtSparseKey(query, sparseKey.getSparseKey()),
              sparseKey.getUniTransformedValue()));
    }
    queryKeys.sort(java.util.Comparator.comparingInt(SparseMergeSearch.QueryKey::getNumRows));
    return queryKeys.toArray(new SparseMergeSearch.QueryKey[0]);
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
    BoundedSizeMaxHeap<RowNumAndSimilarity> rows =
        SparseSearchResults.newTopResultsHeap(maxResults);
    double currentMinSimilarity = minSimilarity;
    for (LongCursor rowNum : candidateRowNums) {
      currentMinSimilarity =
          scoreRowAndUpdateMinSimilarity(
              rows,
              rowNum.value,
              getComparisonTermsAndValues(rowNum.value),
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
    return Math.max(minSimilarity, SparseSearchResults.getConservativeMinSimilarity(rows));
  }

  /**
   * Returns true if the row should be scored during search. A row is skipped if it has been
   * tombstoned (soft-deleted) or does not match the metadata filter. This check allows tombstoned
   * rows to remain in the physical inverted lists without affecting search results.
   */
  private boolean canScoreRow(long rowNum, @Nullable MetaFilter metadataFilter) {
    return !isDeleted(rowNum)
        && (metadataFilter == null || matchesMetaFilter(rowNum, metadataFilter));
  }

  private SparseKeyAndPrefixFilteringData[] collectSparseFilteredSearchQueryKeys(
      LongTermsAndValues termsAndValues) {
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

  /** Returns the rows with the high-popularity terms dropped, as both search and build see them. */
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

  private LongDoubleHashMap buildRowNumToUniValue(
      LongObjectHashMap<LongTermsAndValues> comparisonRows) {
    LongDoubleHashMap uniValues = new LongDoubleHashMap(comparisonRows.size());
    for (LongObjectCursor<LongTermsAndValues> entry : comparisonRows) {
      uniValues.put(entry.key, stableSortedUniValue(entry.value));
    }
    return uniValues;
  }

  private double stableSortedUniValue(LongTermsAndValues termsAndValues) {
    double[] transformedValues = new double[termsAndValues.valuesLength()];
    for (int index = 0; index < transformedValues.length; ++index) {
      transformedValues[index] = comparator.getUniTransformedValue(termsAndValues.getValue(index));
    }
    Arrays.sort(transformedValues);
    return MathUtils.stableSum(transformedValues);
  }

  /**
   * Builds the uni-sorted inverted list of every sparse key. The merge generator scores from the
   * inverted-list values when it can, so those are only materialized when they will be read.
   */
  private LongObjectHashMap<SparseInvertedList> buildSparseInvertedIndex(
      LongObjectHashMap<LongTermsAndValues> comparisonRows) {
    LongObjectHashMap<ArrayList<RowNumAndUniValue>> entriesBySparseKey = new LongObjectHashMap<>();
    for (LongObjectCursor<LongTermsAndValues> row : comparisonRows) {
      LongTermsAndValues termsAndValues = row.value;
      double uniValue = rowNumToUniValue.get(row.key);
      for (LongCursor sparseKeyCursor : LongHashSet.from(getSparseKeys(termsAndValues))) {
        long sparseKey = sparseKeyCursor.value;
        ArrayList<RowNumAndUniValue> entries = entriesBySparseKey.get(sparseKey);
        if (entries == null) {
          entries = new ArrayList<>();
          entriesBySparseKey.put(sparseKey, entries);
        }
        float value =
            scoresFromConjunction ? getValueAtSparseKey(termsAndValues, sparseKey) : 0.0f;
        entries.add(new RowNumAndUniValue(row.key, uniValue, value));
      }
    }

    LongObjectHashMap<SparseInvertedList> invertedIndex =
        new LongObjectHashMap<>(entriesBySparseKey.size());
    for (LongObjectCursor<ArrayList<RowNumAndUniValue>> sparseKey : entriesBySparseKey) {
      List<RowNumAndUniValue> entries = sparseKey.value;
      Collections.sort(entries);
      long[] rowNums = new long[entries.size()];
      float[] values = scoresFromConjunction ? new float[entries.size()] : EMPTY_VALUES;
      for (int index = 0; index < rowNums.length; ++index) {
        rowNums[index] = entries.get(index).getRowNum();
        if (scoresFromConjunction) {
          values[index] = entries.get(index).getValue();
        }
      }
      invertedIndex.put(sparseKey.key, new SparseInvertedList(rowNums, values));
    }
    return invertedIndex;
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
    double tolerance = MathUtils.EPSILON_12 * Math.max(1.0, Math.abs(expectedUniValue));
    if (!Double.isFinite(actualUniValue)
        || actualUniValue < 0.0
        || Math.abs(expectedUniValue - actualUniValue) > tolerance) {
      throw new IllegalArgumentException(
          String.format(
              "%s has uniValue %s, expected %s for the configured comparator.",
              source, actualUniValue, expectedUniValue));
    }
  }

  private long[] getRawRowNums(long sparseKey) {
    SparseInvertedList invertedList = sparseKeyToInvertedList.get(sparseKey);
    return invertedList == null ? EMPTY_ROW_NUMS : invertedList.getRowNums();
  }

  private static double parseMaxFractionIdsPerSparseKey(NamespaceConfig namespaceConfig) {
    return namespaceConfig.readDoubleIndexParam(
        Constants.MAX_FRACTION_IDS_PER_SPARSE_KEY,
        Constants.DEFAULT_MAX_FRACTION_IDS_PER_SPARSE_KEY);
  }

  /** The index state both candidate generators traverse, shared so it is allocated once. */
  final class SearchContext implements SparseFilteredSearch.Context, SparseMergeSearch.Context {
    @Override
    public double getUniValue(long rowNum) {
      if (!rowNumToUniValue.containsKey(rowNum)) {
        throw new IllegalStateException(
            String.format("rowNum %s is absent from the sparse uni-value index.", rowNum));
      }
      return rowNumToUniValue.get(rowNum);
    }

    @Override
    public double getMinPrefixSum(double sparseKeysUniValue, double minSimilarity) {
      return BaseSparseIndex.this.getMinPrefixSum(sparseKeysUniValue, minSimilarity);
    }

    @Override
    public long[] getRowNums(long sparseKey) {
      return getRawRowNums(sparseKey);
    }

    @Override
    public double stableSortedUniValue(LongTermsAndValues termsAndValues) {
      return BaseSparseIndex.this.stableSortedUniValue(termsAndValues);
    }
  }
}
