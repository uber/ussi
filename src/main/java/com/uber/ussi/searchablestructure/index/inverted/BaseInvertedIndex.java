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
import com.uber.ussi.searchablestructure.result.RowNumAndSimilarity;
import com.uber.ussi.searchablestructure.index.RowStoringIndex;
import com.uber.ussi.searchablestructure.index.IndexType;
import com.uber.ussi.searchablestructure.index.MetadataFilteredSearchExecutor;
import com.uber.ussi.searchablestructure.index.inverted.generator.FilteredSearch;
import com.uber.ussi.searchablestructure.index.inverted.generator.InvertedList;
import com.uber.ussi.searchablestructure.index.inverted.generator.MergeSearch;
import com.uber.ussi.searchablestructure.result.ResultHeaps;
import com.uber.ussi.searchablestructure.index.inverted.KeyAndPrefixFilteringData;
import com.uber.ussi.searchablestructure.utils.metadata.MetadataFilteringStrategy;
import com.uber.ussi.searchablestructure.utils.parallel.ParallelShardSearch;
import com.uber.ussi.searchablestructure.utils.parallel.SharedMinSimilarity;
import com.uber.ussi.utils.BoundedSizeMaxHeap;
import com.uber.ussi.utils.ConfigKeys;
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
 *       generation probes and the shared-key test reads. See {@link RecordIndexingStrategy}.
 *   <li><b>verification</b> is the form the comparator scores: the record as supplied, minus any
 *       high-popularity terms dropped at build time.
 * </ul>
 *
 * <p>Both forms report the same Uni value, so length and prefix filtering read the same bound
 * whichever one reaches them.
 *
 * <p>Storage is two components. The inverted lists key rows by term or signature, and the forward
 * index holds the other side, mapping each row number to its two forms and its Uni value, so
 * candidate generation reads the lists and verification reads the forward index.
 */
abstract class BaseInvertedIndex extends RowStoringIndex {
  private static final long[] EMPTY_ROW_NUMS = new long[0];
  private static final float[] EMPTY_VALUES = new float[0];

  /**
   * Rows a shard holds once an index is divided as finely as it will be. It sizes the shard count
   * of a small index rather than deciding whether to shard one, so an index too small for a shard
   * per core takes as many shards as it has rows for.
   *
   * <p>An index is built once from the rows it is given and never grows, so the count is settled at
   * build time and no index is ever converted from unsharded to sharded.
   *
   * <p>The value is conservative rather than measured. Sharding was measured to pay at about 60,000
   * rows per shard and to cost more than it returned at about 15,000, so this sits at the safe end
   * of a range whose crossover has not been located.
   */
  static final int MIN_NUM_ROWS_PER_SHARD = 50_000;

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
  private final List<LongObjectHashMap<InvertedList>> keyToInvertedListByShard;
  private final MetadataFilteredSearchExecutor metadataFilteredSearchExecutor;
  private final List<SharedSearchContext> searchContextByShard;

  protected BaseInvertedIndex(
      NamespaceConfig namespaceConfig,
      LongObjectHashMap<LongTermsAndValues> rowNumToTermsAndValuesMap,
      LongObjectHashMap<LongMeta> rowNumToMetaMap,
      IndexType indexType) {
    this(namespaceConfig, rowNumToTermsAndValuesMap, rowNumToMetaMap, indexType, null);
  }

  /**
   * An index over some of a structure's rows, discarding the terms the structure found popular.
   *
   * <p>A structure built from several indexes must give each the same terms to discard, since
   * popularity is a property of the whole. An index left to measure popularity over its own rows
   * would take its share of a term for the whole of it, and would discard terms the structure
   * keeps.
   */
  protected BaseInvertedIndex(
      NamespaceConfig namespaceConfig,
      LongObjectHashMap<LongTermsAndValues> rowNumToTermsAndValuesMap,
      LongObjectHashMap<LongMeta> rowNumToMetaMap,
      IndexType indexType,
      @Nullable LongHashSet structureDiscardedTerms) {
    this(namespaceConfig, rowNumToTermsAndValuesMap, rowNumToMetaMap, indexType,
        structureDiscardedTerms, getNumShards(rowNumToTermsAndValuesMap.size()));
  }

  /**
   * An index over a fixed number of shards, which only a test has reason to choose: the shard count
   * follows from the rows an index holds.
   */
  BaseInvertedIndex(
      NamespaceConfig namespaceConfig,
      LongObjectHashMap<LongTermsAndValues> rowNumToTermsAndValuesMap,
      LongObjectHashMap<LongMeta> rowNumToMetaMap,
      IndexType indexType,
      @Nullable LongHashSet structureDiscardedTerms,
      int numShards) {
    super(namespaceConfig, rowNumToTermsAndValuesMap, rowNumToMetaMap);
    // Null exactly when the structure keys by terms, which is a property of the index type and
    // not of what the comparator happens to support. Created here because the constructor derives
    // keys below, and eagerly so that an empty structure is rejected on the same grounds as a
    // populated one.
    this.signatureKeyingStrategy =
        indexType.keysBySignatures()
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
    this.discardedTerms =
        structureDiscardedTerms == null
            ? discardedTermsOf(rowNumToTermsAndValuesMap, maxFractionIdsPerTerm)
            : structureDiscardedTerms;
    LongObjectHashMap<LongTermsAndValues> discardedTermFreeRows = buildDiscardedTermFreeRows();
    this.verificationRowNumToTermsAndValuesMap =
        popularTermDiscardScope == PopularTermDiscardScope.CANDIDATES_ONLY
            ? rowNumToTermsAndValuesMap
            : discardedTermFreeRows;
    // The keys the lists are under are derived from the rows the popular terms are already gone
    // from, which is the order the discard depends on: a term is discarded while it is still a
    // term, so a structure keyed by signatures generates them from a row already stripped rather
    // than dropping signatures it has generated. Nothing here counts the frequency of a key.
    this.indexedRowNumToTermsAndValuesMap = buildIndexedRows(discardedTermFreeRows);
    this.rowNumToUniValue = buildRowNumToUniValue(indexedRowNumToTermsAndValuesMap);
    this.keyToInvertedListByShard =
        buildInvertedLists(indexedRowNumToTermsAndValuesMap, Math.max(1, numShards));
    List<SharedSearchContext> searchContexts = new ArrayList<>(keyToInvertedListByShard.size());
    for (LongObjectHashMap<InvertedList> keyToInvertedList : keyToInvertedListByShard) {
      searchContexts.add(new SharedSearchContext(keyToInvertedList));
    }
    this.searchContextByShard = List.copyOf(searchContexts);
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

  protected abstract double getMaxPrefixSum(
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
      int k, LongTermsAndValues record, MetaFilter metadataFilter, float minSimilarity) {
    if (k <= 0) {
      throw new IllegalArgumentException("k must be greater than 0.");
    }
    int numResults = Math.min(k, namespaceConfig.getMaxNumSimilarities());
    return search(record, metadataFilter, minSimilarity, numResults);
  }

  @Override
  public final List<RowNumAndSimilarity> getSimilarRowNums(
      float minSimilarity, LongTermsAndValues record, MetaFilter metadataFilter) {
    if (minSimilarity < 0.0f || minSimilarity > 1.0f) {
      throw new IllegalArgumentException("minSimilarity must be in the range [0.0, 1.0].");
    }
    return search(record, metadataFilter, minSimilarity, namespaceConfig.getMaxNumSimilarities());
  }

  final MetadataFilteringStrategy
      getResolvedMetadataFilteringStrategyForLastSearchForTests() {
    return metadataFilteredSearchExecutor.getResolvedMetadataFilteringStrategyForLastSearch();
  }

  final int getNumShardsForTests() {
    return keyToInvertedListByShard.size();
  }

  final int getNumIndexedKeysForTests(int shard) {
    return keyToInvertedListByShard.get(shard).size();
  }

  final long[] getDiscardedTermsForTests() {
    long[] terms = discardedTerms.toArray();
    Arrays.sort(terms);
    return terms;
  }

  final boolean discardsPopularTerms() {
    return maxFractionIdsPerTerm < 1.0;
  }

  final long[] getRowNumsForKeyForTests(int shard, long key) {
    InvertedList invertedList = keyToInvertedListByShard.get(shard).get(key);
    return invertedList == null ? EMPTY_ROW_NUMS : invertedList.getRowNums().clone();
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

  /** See {@link FilteredSearch#getFirstMatchingUniValue getFirstMatchingUniValue()}. */
  final int getFirstMatchingUniValueForTests(
      long[] rowNums,
      double comparatorUniValue,
      double minSimilarity,
      int searchFromIndex,
      int searchToIndex) {
    return FilteredSearch.getFirstMatchingUniValue(
        comparator,
        searchContextByShard.get(0),
        rowNums,
        comparatorUniValue,
        minSimilarity,
        searchFromIndex,
        searchToIndex);
  }

  /** See {@link FilteredSearch#getLastMatchingUniValue getLastMatchingUniValue()}. */
  final int getLastMatchingUniValueForTests(
      long[] rowNums,
      double comparatorUniValue,
      double minSimilarity,
      int searchFromIndex,
      int searchToIndex) {
    return FilteredSearch.getLastMatchingUniValue(
        comparator,
        searchContextByShard.get(0),
        rowNums,
        comparatorUniValue,
        minSimilarity,
        searchFromIndex,
        searchToIndex);
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

  /**
   * Generates candidates from the inverted lists and scores them, searching every shard and keeping
   * the nearest rows across all of them.
   *
   * <p>Each shard is a generation and a verification of its own, over its own lists and into its
   * own heap, so each prunes from the rows it has seen rather than from the answer as a whole.
   */
  private List<RowNumAndSimilarity> invertedListSearch(
      LongTermsAndValues query,
      LongTermsAndValues indexedQuery,
      @Nullable MetaFilter metadataFilter,
      float minSimilarity,
      int maxResults) {
    return ParallelShardSearch.search(
        searchContextByShard.size(),
        maxResults,
        minSimilarity,
        (shard, sharedMinSimilarity) ->
            searchShard(
                shard,
                query,
                indexedQuery,
                metadataFilter,
                minSimilarity,
                maxResults,
                sharedMinSimilarity));
  }

  private List<RowNumAndSimilarity> searchShard(
      int shard,
      LongTermsAndValues query,
      LongTermsAndValues indexedQuery,
      @Nullable MetaFilter metadataFilter,
      float minSimilarity,
      int maxResults,
      SharedMinSimilarity sharedMinSimilarity) {
    SharedSearchContext searchContext = searchContextByShard.get(shard);
    if (candidateGeneratorType == CandidateGeneratorType.SPARS_MERGE) {
      return MergeSearch.search(
          comparator,
          query,
          indexedQuery,
          metadataFilter,
          minSimilarity,
          maxResults,
          collectMergeSearchQueryKeys(indexedQuery, shard),
          searchContext,
          this::canScoreRow,
          scoresFromConjunction,
          this::getVerificationRow,
          sharedMinSimilarity);
    }
    return FilteredSearch.search(
        comparator,
        query,
        indexedQuery,
        metadataFilter,
        minSimilarity,
        maxResults,
        collectFilteredSearchQueryKeys(indexedQuery, shard),
        searchContext,
        this::canScoreRow,
        this::getVerificationRow,
        sharedMinSimilarity);
  }

  /**
   * Returns the query's keys, shortest inverted list first so the merge reaches its pruning bound
   * on the selective keys before paying for the popular ones.
   */
  private MergeSearch.QueryKey[] collectMergeSearchQueryKeys(
      LongTermsAndValues indexedQuery, int shard) {
    LongObjectHashMap<InvertedList> keyToInvertedList = keyToInvertedListByShard.get(shard);
    KeyAndUniTransformedValue[] keys = getKeysAndUniTransformedValues(indexedQuery);
    ArrayList<MergeSearch.QueryKey> queryKeys = new ArrayList<>(keys.length);
    for (KeyAndUniTransformedValue key : keys) {
      InvertedList invertedList = keyToInvertedList.get(key.getKey());
      // A key this shard has no rows under contributes nothing, so it gets no frontier entry.
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
   * than from an inverted list, so the shared-key restriction is applied here directly to keep both
   * metadata filtering strategies returning the same rows.
   */
  private List<RowNumAndSimilarity> searchCandidateRows(
      LongTermsAndValues query,
      LongTermsAndValues indexedQuery,
      LongHashSet candidateRowNums,
      @Nullable MetaFilter metadataFilter,
      float minSimilarity,
      int maxResults) {
    BoundedSizeMaxHeap<RowNumAndSimilarity> rows = ResultHeaps.newTopResults(maxResults);
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
    return Math.max(minSimilarity, ResultHeaps.getConservativeMinSimilarity(rows));
  }

  /** Lets tombstoned rows stay in the physical inverted lists without reaching search results. */
  private boolean canScoreRow(long rowNum, @Nullable MetaFilter metadataFilter) {
    return !isDeleted(rowNum)
        && (metadataFilter == null || matchesMetaFilter(rowNum, metadataFilter));
  }

  private KeyAndPrefixFilteringData[] collectFilteredSearchQueryKeys(
      LongTermsAndValues indexedQuery, int shard) {
    LongObjectHashMap<InvertedList> keyToInvertedList = keyToInvertedListByShard.get(shard);
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
              numRowsUnderKey(keyToInvertedList, key.getKey()),
              key.getUniTransformedValue());
    }
    return keyData;
  }

  /** The terms a structure holding {@code rowNumToTermsAndValuesMap} discards as popular. */
  static LongHashSet discardedTermsOf(
      NamespaceConfig namespaceConfig,
      LongObjectHashMap<LongTermsAndValues> rowNumToTermsAndValuesMap) {
    return discardedTermsOf(
        rowNumToTermsAndValuesMap, parseMaxFractionIdsPerTerm(namespaceConfig));
  }

  /**
   * Identifies the high-popularity terms to discard. The structure sees the complete dataset, so
   * observed popularity is true popularity: a term is discarded when it occurs in more than
   * floor(numRows * maxFractionIdsPerTerm) rows.
   *
   * <p>Counted over the terms of each row, never over the keys the rows are indexed under, so a
   * frequent key is never discarded for being frequent. Where the keys are signatures they are
   * generated afterwards, from the rows these terms have been removed from.
   */
  private static LongHashSet discardedTermsOf(
      LongObjectHashMap<LongTermsAndValues> rowNumToTermsAndValuesMap,
      double maxFractionIdsPerTerm) {
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
   * Builds the uni-sorted inverted list of every key, one set of lists per shard. Values are
   * materialized only when the merge generator will score from them.
   *
   * <p>A row's lists go to the shard its row number falls in. Row numbers are assigned in turn,
   * so the shards receive equal shares, which is what makes them cost the same to search as each
   * other.
   */
  private List<LongObjectHashMap<InvertedList>> buildInvertedLists(
      LongObjectHashMap<LongTermsAndValues> indexedRows, int numShards) {
    List<LongObjectHashMap<ArrayList<RowNumAndUniValue>>> entriesByKeyByShard =
        new ArrayList<>(numShards);
    for (int shard = 0; shard < numShards; shard++) {
      entriesByKeyByShard.add(new LongObjectHashMap<>());
    }
    for (LongObjectCursor<LongTermsAndValues> row : indexedRows) {
      LongObjectHashMap<ArrayList<RowNumAndUniValue>> entriesByKey =
          entriesByKeyByShard.get(shardOf(row.key, numShards));
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

    List<LongObjectHashMap<InvertedList>> invertedListsByShard = new ArrayList<>(numShards);
    for (LongObjectHashMap<ArrayList<RowNumAndUniValue>> entriesByKey : entriesByKeyByShard) {
      invertedListsByShard.add(materializeInvertedLists(entriesByKey));
    }
    return List.copyOf(invertedListsByShard);
  }

  private LongObjectHashMap<InvertedList> materializeInvertedLists(
      LongObjectHashMap<ArrayList<RowNumAndUniValue>> entriesByKey) {
    LongObjectHashMap<InvertedList> invertedLists = new LongObjectHashMap<>(entriesByKey.size());
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

  private static int numRowsUnderKey(
      LongObjectHashMap<InvertedList> keyToInvertedList, long key) {
    InvertedList invertedList = keyToInvertedList.get(key);
    return invertedList == null ? 0 : invertedList.size();
  }

  /** The shard a row's inverted list entries belong to, fixed when the index is built. */
  private static int shardOf(long rowNum, int numShards) {
    return Math.floorMod(rowNum, numShards);
  }

  /**
   * Shards to build {@code numRows} into: one per core once every shard would hold {@link
   * #MIN_NUM_ROWS_PER_SHARD} rows, and fewer until then.
   *
   * <p>One per core is what lets a search with the cores to itself use all of them. The count does
   * not follow the load, because the shards are fixed when the index is built and a search under
   * load searches the same shards with fewer threads.
   */
  static int getNumShards(int numRows) {
    int numCores = Math.max(1, Runtime.getRuntime().availableProcessors());
    return Math.max(1, Math.min(numCores, numRows / MIN_NUM_ROWS_PER_SHARD));
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

  private static double parseMaxFractionIdsPerTerm(NamespaceConfig namespaceConfig) {
    return namespaceConfig.readDoubleIndexParam(
        ConfigKeys.MAX_FRACTION_IDS_PER_TERM, ConfigKeys.DEFAULT_MAX_FRACTION_IDS_PER_TERM);
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

  /**
   * The index state both candidate generators traverse over one shard, allocated once per shard.
   *
   * <p>Only the inverted lists belong to a shard. Uni values, and everything else a search reads by
   * row number, belong to the index and are read by every shard's search.
   */
  final class SharedSearchContext implements FilteredSearch.Context, MergeSearch.Context {
    private final LongObjectHashMap<InvertedList> keyToInvertedList;

    SharedSearchContext(LongObjectHashMap<InvertedList> keyToInvertedList) {
      this.keyToInvertedList = keyToInvertedList;
    }
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
    public double getMaxPrefixSum(
        double keysUniValue, double recordUniValue, double minSimilarity) {
      return BaseInvertedIndex.this.getMaxPrefixSum(
          keysUniValue, recordUniValue, minSimilarity);
    }

    @Override
    public long[] getRowNums(long key) {
      InvertedList invertedList = keyToInvertedList.get(key);
      return invertedList == null ? EMPTY_ROW_NUMS : invertedList.getRowNums();
    }

    @Override
    public double stableSortedUniValue(LongTermsAndValues termsAndValues) {
      return BaseInvertedIndex.this.stableSortedUniValue(termsAndValues);
    }
  }
}
