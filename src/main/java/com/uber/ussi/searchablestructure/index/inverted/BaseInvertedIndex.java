/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.inverted;

import com.carrotsearch.hppc.LongDoubleHashMap;
import com.carrotsearch.hppc.LongHashSet;
import com.carrotsearch.hppc.LongIntHashMap;
import com.carrotsearch.hppc.LongObjectHashMap;
import com.carrotsearch.hppc.cursors.LongCursor;
import com.carrotsearch.hppc.cursors.LongIntCursor;
import com.carrotsearch.hppc.cursors.LongObjectCursor;
import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.config.NamespaceConfig.CandidateGeneratorType;
import com.uber.ussi.config.NamespaceConfig.PopularTermDiscardScope;
import com.uber.ussi.entity.meta.LongMeta;
import com.uber.ussi.entity.meta.MetaFilter;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;
import com.uber.ussi.entity.termsandvalues.RecordType;
import com.uber.ussi.error.IndexCreationError;
import com.uber.ussi.searchablestructure.RowNumAndSimilarity;
import com.uber.ussi.searchablestructure.index.Index;
import com.uber.ussi.searchablestructure.index.IndexType;
import com.uber.ussi.searchablestructure.index.MetadataFilteredSearchExecutor;
import com.uber.ussi.searchablestructure.index.inverted.generator.FilteredSearch;
import com.uber.ussi.searchablestructure.index.inverted.generator.InvertedList;
import com.uber.ussi.searchablestructure.index.inverted.generator.MergeSearch;
import com.uber.ussi.searchablestructure.index.inverted.generator.TopResults;
import com.uber.ussi.searchablestructure.inverted.KeyAndPrefixFilteringData;
import com.uber.ussi.searchablestructure.metadata.MetadataFilteringStrategy;
import com.uber.ussi.utils.BoundedSizeMaxHeap;
import com.uber.ussi.utils.Constants;
import com.uber.ussi.utils.MathUtils;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Shared inverted-list index implementation with length and prefix filtering.
 *
 * <p>A record travels through a search in two forms, named consistently throughout this package:
 *
 * <ul>
 *   <li><b>indexed</b> is the form whose terms are the inverted-list keys, which candidate
 *       generation probes and the shared-key test reads; see {@link RecordIndexingStrategy}.
 *   <li><b>verification</b> is the form the comparator scores: the record as supplied, minus any
 *       high-popularity terms dropped at build time.
 * </ul>
 *
 * <p>Both forms report the same Uni value, so length and prefix filtering read the same bound
 * whichever one reaches them.
 */
abstract class BaseInvertedIndex extends Index {
  private static final long[] EMPTY_ROW_NUMS = new long[0];
  private static final float[] EMPTY_VALUES = new float[0];

  @Nullable private final SignatureKeyingStrategy signatureKeyingStrategy;
  private final RecordIndexingStrategy recordIndexingStrategy;
  private final CandidateGeneratorType candidateGeneratorType;
  private final PopularTermDiscardScope popularTermDiscardScope;
  private final boolean scoresFromConjunction;
  private final double maxFractionIdsPerTerm;
  private final LongHashSet discardedTerms;
  private final LongObjectHashMap<LongTermsAndValues> verificationRowNumToTermsAndValuesMap;
  private final LongObjectHashMap<LongTermsAndValues> indexedRowNumToTermsAndValuesMap;
  private final LongDoubleHashMap rowNumToUniValue;
  private final LongObjectHashMap<InvertedList> keyToInvertedList;
  private final MetadataFilteredSearchExecutor metadataFilteredSearchExecutor;
  private final SharedSearchContext searchContext = new SharedSearchContext();

  protected BaseInvertedIndex(
      NamespaceConfig namespaceConfig,
      LongObjectHashMap<LongTermsAndValues> rowNumToTermsAndValuesMap,
      LongObjectHashMap<LongMeta> rowNumToMetaMap,
      IndexType indexType) {
    super(namespaceConfig, rowNumToTermsAndValuesMap, rowNumToMetaMap);
    // Null exactly when the structure keys by terms, which is a property of the index type and
    // not of what the comparator happens to support. Created here because the constructor derives
    // keys below, and eagerly so that an empty structure is rejected on the same grounds as a
    // populated one.
    this.signatureKeyingStrategy =
        indexType.requiresSignatureSupport()
            ? SignatureKeyingStrategy.create(namespaceConfig, comparator)
            : null;
    RecordType recordType = resolveRecordType(indexType);
    this.recordIndexingStrategy =
        RecordIndexingStrategyFactory.createRecordIndexingStrategy(recordType);
    this.candidateGeneratorType = namespaceConfig.getCandidateGeneratorType();
    this.popularTermDiscardScope = namespaceConfig.getIndexPopularTermDiscardScope();
    // A conjunction excludes discarded terms, so CANDIDATES_ONLY verifies via the comparator.
    this.scoresFromConjunction =
        candidateGeneratorType == CandidateGeneratorType.SPARS_MERGE
            && indexType.conjunctionDeterminesSimilarity(recordType)
            && popularTermDiscardScope == PopularTermDiscardScope.CANDIDATES_AND_VERIFICATION;
    this.maxFractionIdsPerTerm = parseMaxFractionIdsPerTerm(namespaceConfig);
    validateRows();
    this.discardedTerms = buildDiscardedTerms();
    LongObjectHashMap<LongTermsAndValues> discardedTermFreeRows = buildDiscardedTermFreeRows();
    this.verificationRowNumToTermsAndValuesMap =
        popularTermDiscardScope == PopularTermDiscardScope.CANDIDATES_ONLY
            ? rowNumToTermsAndValuesMap
            : discardedTermFreeRows;
    this.indexedRowNumToTermsAndValuesMap = buildIndexedRows(discardedTermFreeRows);
    this.rowNumToUniValue = buildRowNumToUniValue(indexedRowNumToTermsAndValuesMap);
    this.keyToInvertedList = buildInvertedLists(indexedRowNumToTermsAndValuesMap);
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

  /** Returns the keying strategy of a structure whose index type keys its lists by signatures. */
  protected final SignatureKeyingStrategy getSignatureKeyingStrategy() {
    return Objects.requireNonNull(
        signatureKeyingStrategy, "This index does not key its lists by signatures.");
  }

  protected abstract double getMinPrefixSum(
      double keysUniValue, double recordUniValue, double minSimilarity);

  /** Returns each key of {@code indexedRecord} with the Uni value it contributes. */
  protected abstract KeyAndUniTransformedValue[] getKeysAndUniTransformedValues(
      LongTermsAndValues indexedRecord);

  /**
   * Returns the distinct keys whose inverted lists a record belongs in. Implementations
   * deduplicate, because only they know whether their keys repeat: terms cannot, signatures often
   * do.
   */
  protected abstract long[] getKeys(LongTermsAndValues indexedRecord);

  protected abstract float getValueAtKey(LongTermsAndValues indexedRecord, long key);

  private LongTermsAndValues toIndexedRecord(LongTermsAndValues termsAndValues) {
    return recordIndexingStrategy.toIndexedRecord(termsAndValues, comparator);
  }

  /**
   * Validates the requirements every inverted index shares, throwing {@link
   * IllegalArgumentException} when a row does not qualify: non-empty terms, and the Uni value that
   * length and prefix filtering read rather than recompute. Rows are checked as the caller supplied
   * them, before the indexed form is derived.
   */
  protected final void validateRecord(LongTermsAndValues termsAndValues, String source) {
    Objects.requireNonNull(termsAndValues, source + " is null.");
    if (termsAndValues.termsLength() == 0) {
      throw new IllegalArgumentException(source + " must have non-empty terms.");
    }
    recordIndexingStrategy.validateRecordType(termsAndValues, source);
    validateUniValue(termsAndValues, source);
  }

  private void validateUniValue(LongTermsAndValues termsAndValues, String source) {
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

  final MetadataFilteringStrategy
      getResolvedMetadataFilteringStrategyForLastSearchForTests() {
    return metadataFilteredSearchExecutor.getResolvedMetadataFilteringStrategyForLastSearch();
  }

  final int getNumIndexedKeysForTests() {
    return keyToInvertedList.size();
  }

  final long[] getDiscardedTermsForTests() {
    long[] terms = discardedTerms.toArray();
    Arrays.sort(terms);
    return terms;
  }

  final boolean discardsPopularTerms() {
    return maxFractionIdsPerTerm < 1.0;
  }

  final long[] getRowNumsForKeyForTests(long key) {
    return getRawRowNums(key).clone();
  }

  /**
   * Returns the row in verification form, which keeps its high-popularity terms only under the
   * {@code candidates_only} discard scope.
   */
  final LongTermsAndValues getVerificationRow(long rowNum) {
    return verificationRowNumToTermsAndValuesMap.get(rowNum);
  }

  final LongTermsAndValues getIndexedRow(long rowNum) {
    return indexedRowNumToTermsAndValuesMap.get(rowNum);
  }

  /** See {@link FilteredSearch#getFirstMatchingUniValue}. */
  final int getFirstMatchingUniValueForTests(
      long[] rowNums,
      double comparatorUniValue,
      double minSimilarity,
      int searchFromIndex,
      int searchToIndex) {
    return FilteredSearch.getFirstMatchingUniValue(
        comparator,
        searchContext,
        rowNums,
        comparatorUniValue,
        minSimilarity,
        searchFromIndex,
        searchToIndex);
  }

  /** See {@link FilteredSearch#getLastMatchingUniValue}. */
  final int getLastMatchingUniValueForTests(
      long[] rowNums,
      double comparatorUniValue,
      double minSimilarity,
      int searchFromIndex,
      int searchToIndex) {
    return FilteredSearch.getLastMatchingUniValue(
        comparator,
        searchContext,
        rowNums,
        comparatorUniValue,
        minSimilarity,
        searchFromIndex,
        searchToIndex);
  }

  final SharedSearchContext getSearchContext() {
    return searchContext;
  }

  private List<RowNumAndSimilarity> search(
      LongTermsAndValues record, MetaFilter metadataFilter, float minSimilarity, int maxResults) {
    if (maxResults == 0 || rowNumToTermsAndValuesMap.isEmpty()) {
      return Collections.emptyList();
    }
    LongTermsAndValues discardedTermFreeRecord = record.newWithoutTerms(discardedTerms, comparator);
    if (discardedTermFreeRecord.termsLength() == 0) {
      return Collections.emptyList();
    }
    // The query is scored in the form the rows were kept in, so both sides carry the same terms.
    LongTermsAndValues verificationRecord =
        popularTermDiscardScope == PopularTermDiscardScope.CANDIDATES_ONLY
            ? record
            : discardedTermFreeRecord;
    LongTermsAndValues indexedRecord = toIndexedRecord(discardedTermFreeRecord);
    return metadataFilteredSearchExecutor.search(
        metadataFilter,
        maxResults,
        (resolvedMetadataFilter, resolvedMaxResults) ->
            invertedListSearch(
                verificationRecord,
                indexedRecord,
                resolvedMetadataFilter,
                minSimilarity,
                resolvedMaxResults),
        (candidateRowNums, resolvedMetadataFilter, resolvedMaxResults) ->
            searchCandidateRows(
                verificationRecord,
                indexedRecord,
                candidateRowNums,
                resolvedMetadataFilter,
                minSimilarity,
                resolvedMaxResults));
  }

  /** Generates candidates from the inverted lists and scores them. */
  private List<RowNumAndSimilarity> invertedListSearch(
      LongTermsAndValues query,
      LongTermsAndValues indexedQuery,
      @Nullable MetaFilter metadataFilter,
      float minSimilarity,
      int maxResults) {
    if (candidateGeneratorType == CandidateGeneratorType.SPARS_MERGE) {
      return MergeSearch.search(
          comparator,
          query,
          indexedQuery,
          metadataFilter,
          minSimilarity,
          maxResults,
          collectMergeSearchQueryKeys(indexedQuery),
          searchContext,
          this::canScoreRow,
          scoresFromConjunction,
          this::getVerificationRow);
    }
    return FilteredSearch.search(
        comparator,
        query,
        indexedQuery,
        metadataFilter,
        minSimilarity,
        maxResults,
        collectFilteredSearchQueryKeys(indexedQuery),
        searchContext,
        this::canScoreRow,
        this::getVerificationRow);
  }

  /**
   * Returns the query's keys, shortest inverted list first so the merge reaches its pruning bound
   * on the selective keys before paying for the popular ones.
   */
  private MergeSearch.QueryKey[] collectMergeSearchQueryKeys(
      LongTermsAndValues indexedQuery) {
    KeyAndUniTransformedValue[] keys = getKeysAndUniTransformedValues(indexedQuery);
    ArrayList<MergeSearch.QueryKey> queryKeys = new ArrayList<>(keys.length);
    for (KeyAndUniTransformedValue key : keys) {
      InvertedList invertedList = keyToInvertedList.get(key.getKey());
      // A key this index has no rows under contributes nothing, so it gets no frontier entry.
      if (invertedList != null && invertedList.size() != 0) {
        queryKeys.add(
            new MergeSearch.QueryKey(
                invertedList,
                getValueAtKey(indexedQuery, key.getKey()),
                key.getUniTransformedValue()));
      }
    }
    queryKeys.sort(java.util.Comparator.comparingInt(MergeSearch.QueryKey::getNumRows));
    return queryKeys.toArray(new MergeSearch.QueryKey[0]);
  }

  /**
   * Scores the pre-filtered candidate rows sequentially. They arrive from the metadata index rather
   * than from an inverted list, so the shared-key restriction is applied here by hand to keep both
   * metadata filtering strategies returning the same rows.
   */
  private List<RowNumAndSimilarity> searchCandidateRows(
      LongTermsAndValues query,
      LongTermsAndValues indexedQuery,
      LongHashSet candidateRowNums,
      @Nullable MetaFilter metadataFilter,
      float minSimilarity,
      int maxResults) {
    BoundedSizeMaxHeap<RowNumAndSimilarity> rows = TopResults.newTopResultsHeap(maxResults);
    double currentMinSimilarity = minSimilarity;
    for (LongCursor rowNum : candidateRowNums) {
      currentMinSimilarity =
          scoreRowAndUpdateMinSimilarity(
              rows,
              rowNum.value,
              getVerificationRow(rowNum.value),
              query,
              indexedQuery,
              metadataFilter,
              currentMinSimilarity);
    }
    return rows.toList();
  }

  /**
   * The shared-key test runs on the indexed forms: {@code sharesAnyTerm} walks two records in step,
   * so it needs the sorted, distinct terms only that form guarantees.
   */
  private double scoreRowAndUpdateMinSimilarity(
      BoundedSizeMaxHeap<RowNumAndSimilarity> rows,
      long rowNum,
      @Nullable LongTermsAndValues termsAndValues,
      LongTermsAndValues query,
      LongTermsAndValues indexedQuery,
      @Nullable MetaFilter metadataFilter,
      double minSimilarity) {
    LongTermsAndValues indexedRow = getIndexedRow(rowNum);
    if (termsAndValues == null
        || termsAndValues.termsLength() == 0
        || indexedRow == null
        || !indexedQuery.sharesAnyTerm(indexedRow)
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
    return Math.max(minSimilarity, TopResults.getConservativeMinSimilarity(rows));
  }

  /** Lets tombstoned rows stay in the physical inverted lists without reaching search results. */
  private boolean canScoreRow(long rowNum, @Nullable MetaFilter metadataFilter) {
    return !isDeleted(rowNum)
        && (metadataFilter == null || matchesMetaFilter(rowNum, metadataFilter));
  }

  private KeyAndPrefixFilteringData[] collectFilteredSearchQueryKeys(
      LongTermsAndValues indexedQuery) {
    KeyAndUniTransformedValue[] keys = getKeysAndUniTransformedValues(indexedQuery);
    KeyAndPrefixFilteringData[] keyData = new KeyAndPrefixFilteringData[keys.length];
    for (int i = 0; i < keys.length; ++i) {
      KeyAndUniTransformedValue key = keys[i];
      if (!Double.isFinite(key.getUniTransformedValue())
          || key.getUniTransformedValue() < 0.0) {
        throw new IllegalArgumentException(
            String.format(
                "Invalid uni-transformed value (%s) for key %s.",
                key.getUniTransformedValue(), key.getKey()));
      }
      keyData[i] =
          new KeyAndPrefixFilteringData(
              key.getKey(),
              getRawRowNums(key.getKey()).length,
              key.getUniTransformedValue());
    }
    return keyData;
  }

  /**
   * Identifies the high-popularity terms to discard. The index sees the complete dataset, so
   * observed popularity is true popularity: a term is discarded when it occurs in more than
   * floor(numRows * maxFractionIdsPerTerm) rows.
   */
  private LongHashSet buildDiscardedTerms() {
    LongIntHashMap numRowsByTerm = new LongIntHashMap();
    // A row counts once per distinct term, so a term repeated within one row stays one row.
    LongHashSet termsInRow = new LongHashSet();
    for (LongObjectCursor<LongTermsAndValues> entry : rowNumToTermsAndValuesMap) {
      termsInRow.clear();
      for (int i = 0; i < entry.value.termsLength(); ++i) {
        long term = entry.value.getTerm(i);
        if (!termsInRow.add(term)) {
          continue;
        }
        int numRows = numRowsByTerm.containsKey(term) ? numRowsByTerm.get(term) + 1 : 1;
        numRowsByTerm.put(term, numRows);
      }
    }
    int maxNumRowsPerTerm =
        (int) Math.floor(rowNumToTermsAndValuesMap.size() * maxFractionIdsPerTerm);
    LongHashSet popularTerms = new LongHashSet();
    for (LongIntCursor entry : numRowsByTerm) {
      if (entry.value > maxNumRowsPerTerm) {
        popularTerms.add(entry.key);
      }
    }
    return popularTerms;
  }

  /** Returns the rows with the high-popularity terms dropped, which is what gets indexed. */
  private LongObjectHashMap<LongTermsAndValues> buildDiscardedTermFreeRows() {
    if (discardedTerms.isEmpty()) {
      return rowNumToTermsAndValuesMap;
    }
    LongObjectHashMap<LongTermsAndValues> discardedTermFreeRows =
        new LongObjectHashMap<>(rowNumToTermsAndValuesMap.size());
    for (LongObjectCursor<LongTermsAndValues> entry : rowNumToTermsAndValuesMap) {
      discardedTermFreeRows.put(entry.key, entry.value.newWithoutTerms(discardedTerms, comparator));
    }
    return discardedTermFreeRows;
  }

  /**
   * Returns the rows in the form the inverted lists are keyed by, reusing the input map when that
   * form is the identity.
   */
  private LongObjectHashMap<LongTermsAndValues> buildIndexedRows(
      LongObjectHashMap<LongTermsAndValues> discardedTermFreeRows) {
    LongObjectHashMap<LongTermsAndValues> indexedRows =
        new LongObjectHashMap<>(discardedTermFreeRows.size());
    boolean anyRowDiffers = false;
    for (LongObjectCursor<LongTermsAndValues> entry : discardedTermFreeRows) {
      LongTermsAndValues indexedRecord = toIndexedRecord(entry.value);
      anyRowDiffers |= indexedRecord != entry.value;
      indexedRows.put(entry.key, indexedRecord);
    }
    return anyRowDiffers ? indexedRows : discardedTermFreeRows;
  }

  private LongDoubleHashMap buildRowNumToUniValue(
      LongObjectHashMap<LongTermsAndValues> indexedRows) {
    LongDoubleHashMap uniValues = new LongDoubleHashMap(indexedRows.size());
    for (LongObjectCursor<LongTermsAndValues> entry : indexedRows) {
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
   * Builds the uni-sorted inverted list of every key. Values are materialized only when the merge
   * generator will score from them.
   */
  private LongObjectHashMap<InvertedList> buildInvertedLists(
      LongObjectHashMap<LongTermsAndValues> indexedRows) {
    LongObjectHashMap<ArrayList<RowNumAndUniValue>> entriesByKey = new LongObjectHashMap<>();
    for (LongObjectCursor<LongTermsAndValues> row : indexedRows) {
      LongTermsAndValues termsAndValues = row.value;
      double uniValue = rowNumToUniValue.get(row.key);
      for (long key : getKeys(termsAndValues)) {
        ArrayList<RowNumAndUniValue> entries = entriesByKey.get(key);
        if (entries == null) {
          entries = new ArrayList<>();
          entriesByKey.put(key, entries);
        }
        float value = scoresFromConjunction ? getValueAtKey(termsAndValues, key) : 0.0f;
        entries.add(new RowNumAndUniValue(row.key, uniValue, value));
      }
    }

    LongObjectHashMap<InvertedList> invertedLists =
        new LongObjectHashMap<>(entriesByKey.size());
    for (LongObjectCursor<ArrayList<RowNumAndUniValue>> entry : entriesByKey) {
      List<RowNumAndUniValue> entries = entry.value;
      Collections.sort(entries);
      long[] rowNums = new long[entries.size()];
      float[] values = scoresFromConjunction ? new float[entries.size()] : EMPTY_VALUES;
      for (int index = 0; index < rowNums.length; ++index) {
        rowNums[index] = entries.get(index).getRowNum();
        if (scoresFromConjunction) {
          values[index] = entries.get(index).getValue();
        }
      }
      invertedLists.put(entry.key, new InvertedList(rowNums, values));
    }
    return invertedLists;
  }

  private void validateRows() {
    for (LongObjectCursor<LongTermsAndValues> entry : rowNumToTermsAndValuesMap) {
      if (entry.value == null) {
        throw new IndexCreationError(
            String.format("rowNum %s has a null TermsAndValues record.", entry.key));
      }
      try {
        validateRecord(entry.value, "row " + entry.key);
      } catch (IllegalArgumentException e) {
        String message = e.getMessage();
        throw new IndexCreationError(
            message == null ? String.format("row %s is invalid.", entry.key) : message);
      }
    }
  }

  private long[] getRawRowNums(long key) {
    InvertedList invertedList = keyToInvertedList.get(key);
    return invertedList == null ? EMPTY_ROW_NUMS : invertedList.getRowNums();
  }

  private static double parseMaxFractionIdsPerTerm(NamespaceConfig namespaceConfig) {
    return namespaceConfig.readDoubleIndexParam(
        Constants.MAX_FRACTION_IDS_PER_TERM, Constants.DEFAULT_MAX_FRACTION_IDS_PER_TERM);
  }

  /**
   * Returns the one record type the structure stores and the comparator reads. Both failures are
   * backstops: {@code IndexConfigValidator} rejects a pairing with none in common, and no
   * comparator today reads more than one type a structure stores.
   */
  private RecordType resolveRecordType(IndexType indexType) {
    Set<RecordType> recordTypes = indexType.resolveRecordTypes(comparator);
    if (recordTypes.size() != 1) {
      throw new IndexCreationError(
          String.format(
              "indexType %s and comparatorType %s have %s record type in common, not one.",
              indexType.getParamValue(),
              namespaceConfig.getComparatorType(),
              recordTypes.isEmpty() ? "no" : "more than one"));
    }
    return recordTypes.iterator().next();
  }

  /** The index state both candidate generators traverse, shared so it is allocated once. */
  final class SharedSearchContext implements FilteredSearch.Context, MergeSearch.Context {
    /**
     * Length filtering calls this for every row of every list it narrows, so the row is resolved to
     * a slot once rather than hashed twice.
     */
    @Override
    public double getUniValue(long rowNum) {
      int slot = rowNumToUniValue.indexOf(rowNum);
      if (!rowNumToUniValue.indexExists(slot)) {
        throw new IllegalStateException(
            String.format("rowNum %s is absent from the uni-value index.", rowNum));
      }
      return rowNumToUniValue.indexGet(slot);
    }

    @Override
    public double getMinPrefixSum(
        double keysUniValue, double recordUniValue, double minSimilarity) {
      return BaseInvertedIndex.this.getMinPrefixSum(
          keysUniValue, recordUniValue, minSimilarity);
    }

    @Override
    public long[] getRowNums(long key) {
      return getRawRowNums(key);
    }

    @Override
    public double stableSortedUniValue(LongTermsAndValues termsAndValues) {
      return BaseInvertedIndex.this.stableSortedUniValue(termsAndValues);
    }
  }
}
