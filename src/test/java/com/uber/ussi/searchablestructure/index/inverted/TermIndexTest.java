package com.uber.ussi.searchablestructure.index.inverted;

import static com.uber.ussi.TestLongObjectMaps.longObjectMap;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.carrotsearch.hppc.LongDoubleHashMap;
import com.carrotsearch.hppc.LongFloatHashMap;
import com.carrotsearch.hppc.LongHashSet;
import com.carrotsearch.hppc.LongObjectHashMap;
import com.carrotsearch.hppc.cursors.LongObjectCursor;
import com.uber.ussi.comparator.Comparator;
import com.uber.ussi.config.NamespaceConfig.PopularTermDiscardScope;
import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.entity.meta.LongMeta;
import com.uber.ussi.entity.meta.MetaFilter;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValuesTestFactory;
import com.uber.ussi.error.IndexCreationError;
import com.uber.ussi.searchablestructure.index.Index;
import com.uber.ussi.searchablestructure.index.IndexType;
import com.uber.ussi.searchablestructure.index.scan.ScanIndex;
import com.uber.ussi.searchablestructure.result.RowNumAndSimilarity;
import com.uber.ussi.searchablestructure.utils.metadata.MetadataFilteringStrategy;
import com.uber.ussi.utils.ConfigKeys;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
class TermIndexTest {
  /** Test indexes hold far fewer rows than a shard's minimum, so they have one shard. */
  private static final int ONLY_SHARD = 0;

  private static final float DELTA = 1e-6f;
  private static final String CANDIDATES_ONLY =
      NamespaceConfig.PopularTermDiscardScope.CANDIDATES_ONLY.getParamValue();

  /**
   * A scan index over the same rows holds the rows and the metadata and nothing else, so anything
   * the inverted index holds beyond them must raise its estimate above the scan index's.
   */
  @Test
  void memoryFootprintCountsTheInvertedListsAndNotOnlyTheRows() {
    LongObjectHashMap<LongTermsAndValues> rows = longObjectMap();
    for (long rowNum = 1; rowNum <= 20; ++rowNum) {
      rows.put(rowNum, jaccard(new long[] {1, 2, 3, 4}, 1, 1, 1, 1));
    }

    TermIndex termIndex = new TermIndex(config("jaccard"), rows, longObjectMap());
    ScanIndex scanIndex = new ScanIndex(config("jaccard", Map.of(), "scan"), rows, longObjectMap());

    long termIndexBytes = termIndex.getMemoryFootprint().getOnHeapBytes();
    long scanIndexBytes = scanIndex.getMemoryFootprint().getOnHeapBytes();

    assertTrue(
        termIndexBytes > scanIndexBytes,
        "the inverted lists and the maps beside them are uncounted: inverted "
            + termIndexBytes + " against scan " + scanIndexBytes);
  }

  /** The structure answering metadata filters holds one entry per row, so it must be counted. */
  @Test
  void memoryFootprintCountsTheMetadataStructure() {
    LongObjectHashMap<LongTermsAndValues> rows = longObjectMap();
    LongObjectHashMap<LongMeta> metadata = longObjectMap();
    for (long rowNum = 1; rowNum <= 20; ++rowNum) {
      rows.put(rowNum, jaccard(new long[] {1, 2}, 1, 1));
      metadata.put(rowNum, new LongMeta(Map.of("city", "city" + rowNum), false));
    }

    long withoutMetadata =
        new TermIndex(config("jaccard"), rows, longObjectMap())
            .getMemoryFootprint()
            .getOnHeapBytes();
    long withMetadata =
        new TermIndex(config("jaccard"), rows, metadata).getMemoryFootprint().getOnHeapBytes();

    assertTrue(
        withMetadata > withoutMetadata,
        "the metadata structure is uncounted: " + withMetadata + " against " + withoutMetadata);
  }

  @Test
  void constructorBuildsForwardAndUniValueSortedInvertedLists() {
    LongObjectHashMap<LongTermsAndValues> rows = longObjectMap();
    rows.put(12, jaccard(new long[] {1, 2, 3}, 1, 1, 1));
    rows.put(10, jaccard(new long[] {1}, 1));
    rows.put(9, jaccard(new long[] {1}, -1));
    rows.put(11, jaccard(new long[] {1, 2}, 1, 1));

    TermIndex index = new TermIndex(config("jaccard"), rows, longObjectMap());

    assertEquals(4, index.size());
    assertEquals(3, index.getNumIndexedKeysForTests(ONLY_SHARD));
    assertArrayEquals(new long[] {9, 10, 11, 12}, index.getRowNumsForKeyForTests(ONLY_SHARD, 1));
    assertArrayEquals(new long[] {11, 12}, index.getRowNumsForKeyForTests(ONLY_SHARD, 2));
    assertTrue(index.getAll().containsKey(12));
  }

  @Test
  void highFrequencyTermsAreRemovedFromVerificationRowsButNotConsolidationRows() {
    LongObjectHashMap<LongTermsAndValues> rows = longObjectMap();
    rows.put(1, jaccard(new long[] {1, 2}, 1, 1));
    rows.put(2, jaccard(new long[] {1, 3}, 1, 1));
    rows.put(3, jaccard(new long[] {1, 4}, 1, 1));
    rows.put(4, jaccard(new long[] {5}, 1));
    TermIndex index =
        new TermIndex(
            config("jaccard", Map.of(ConfigKeys.MAX_FRACTION_IDS_PER_TERM, "0.5")),
            rows,
            longObjectMap());

    assertArrayEquals(new long[] {1}, index.getDiscardedTermsForTests());
    assertArrayEquals(new long[0], index.getRowNumsForKeyForTests(ONLY_SHARD, 1));
    assertArrayEquals(new long[] {2}, index.getVerificationRow(1).getTerms());
    assertArrayEquals(new long[] {1, 2}, index.getAll().get(1).getTerms());

    List<RowNumAndSimilarity> result =
        index.getNearestNeighborRowNums(1, jaccard(new long[] {1, 2}, 1, 1), MetaFilter.empty());
    assertEquals(List.of(1L), rowNumsNearestFirst(result));
    assertTrue(
        index.getNearestNeighborRowNums(1, jaccard(new long[] {1}, 1), MetaFilter.empty()).isEmpty());
  }

  @Test
  void candidatesOnlyKeepsTheHighFrequencyTermsInTheVerificationRows() {
    LongObjectHashMap<LongTermsAndValues> rows = popularTermRows();
    Map<String, String> params =
        Map.of(
            ConfigKeys.MAX_FRACTION_IDS_PER_TERM, "0.5",
            ConfigKeys.POPULAR_TERM_DISCARD_SCOPE, CANDIDATES_ONLY);
    TermIndex index = new TermIndex(config("jaccard", params), rows, longObjectMap());

    // The scope leaves candidate generation alone and changes only scoring.
    assertArrayEquals(new long[] {1}, index.getDiscardedTermsForTests());
    assertArrayEquals(new long[0], index.getRowNumsForKeyForTests(ONLY_SHARD, 1));
    assertArrayEquals(new long[] {1, 2}, index.getVerificationRow(1).getTerms());

    // Row 1 is reached via term 2 and scored as supplied: two terms of three, not one of two.
    List<RowNumAndSimilarity> results =
        index.getSimilarRowNums(0.4f, jaccard(new long[] {1, 2, 6}, 1, 1, 1), MetaFilter.empty());
    assertEquals(List.of(1L), rowNumsNearestFirst(results));
    assertEquals(2.0f / 3.0f, results.get(0).getSimilarity(), DELTA);
  }

  @Test
  void candidatesAndVerificationScoresTheSameQueryWithoutTheHighFrequencyTerms() {
    LongObjectHashMap<LongTermsAndValues> rows = popularTermRows();
    TermIndex index =
        new TermIndex(
            config("jaccard", Map.of(ConfigKeys.MAX_FRACTION_IDS_PER_TERM, "0.5")),
            rows,
            longObjectMap());

    // Same row and query as above, scored without term 1: one term out of two.
    List<RowNumAndSimilarity> results =
        index.getSimilarRowNums(0.4f, jaccard(new long[] {1, 2, 6}, 1, 1, 1), MetaFilter.empty());
    assertEquals(List.of(1L), rowNumsNearestFirst(results));
    assertEquals(0.5f, results.get(0).getSimilarity(), DELTA);
  }

  @Test
  void candidatesOnlyMissesTheRowsThatOnlyADiscardedTermWouldHaveReached() {
    LongObjectHashMap<LongTermsAndValues> rows = popularTermRows();
    Map<String, String> params =
        Map.of(
            ConfigKeys.MAX_FRACTION_IDS_PER_TERM, "0.5",
            ConfigKeys.POPULAR_TERM_DISCARD_SCOPE, CANDIDATES_ONLY);
    TermIndex index = new TermIndex(config("jaccard", params), rows, longObjectMap());

    // Term 1 is the query's only key and the discard emptied its list, so no row is reachable.
    assertTrue(
        index.getSimilarRowNums(0.4f, jaccard(new long[] {1}, 1), MetaFilter.empty()).isEmpty());
  }

  @Test
  void candidatesOnlyStillPrunesOnTheSimilarityThatExcludesTheDiscardedTerms() {
    LongObjectHashMap<LongTermsAndValues> rows = popularTermRows();
    Map<String, String> params =
        Map.of(
            ConfigKeys.MAX_FRACTION_IDS_PER_TERM, "0.5",
            ConfigKeys.POPULAR_TERM_DISCARD_SCOPE, CANDIDATES_ONLY);
    TermIndex index = new TermIndex(config("jaccard", params), rows, longObjectMap());

    // Row 1 is two terms of three as supplied, clearing 0.6, but length filtering can only bound
    // the similarity excluding term 1, which is one of two, so it is pruned.
    assertTrue(
        index
            .getSimilarRowNums(0.6f, jaccard(new long[] {1, 2, 6}, 1, 1, 1), MetaFilter.empty())
            .isEmpty());
  }

  @Test
  void candidatesOnlyStopsTheMergeFromScoringRowsFromTheConjunction() {
    LongObjectHashMap<LongTermsAndValues> rows = popularTermRows();
    Map<String, String> params =
        Map.of(
            ConfigKeys.MAX_FRACTION_IDS_PER_TERM, "0.5",
            ConfigKeys.POPULAR_TERM_DISCARD_SCOPE, CANDIDATES_ONLY,
            ConfigKeys.CANDIDATE_GENERATOR,
            NamespaceConfig.CandidateGeneratorType.SPARS_MERGE.getParamValue());
    TermIndex index = new TermIndex(config("jaccard", params, "inverted_term"), rows, longObjectMap());

    // A conjunction cannot score the discarded terms, so the merge verifies through the comparator.
    List<RowNumAndSimilarity> results =
        index.getSimilarRowNums(0.4f, jaccard(new long[] {1, 2, 6}, 1, 1, 1), MetaFilter.empty());
    assertEquals(List.of(1L), rowNumsNearestFirst(results));
    assertEquals(2.0f / 3.0f, results.get(0).getSimilarity(), DELTA);
  }

  @Test
  void highFrequencyFilteringUsesTheObservedFractionOfRows() {
    // With 4 rows and a 0.5 cap, a term may occur in at most floor(4 * 0.5) = 2 rows: term 1
    // occurs in 3 and is discarded, term 2 in exactly 2 and is kept.
    LongObjectHashMap<LongTermsAndValues> rows = longObjectMap();
    rows.put(1, jaccard(new long[] {1, 2}, 1, 1));
    rows.put(2, jaccard(new long[] {1, 3}, 1, 1));
    rows.put(3, jaccard(new long[] {1, 4}, 1, 1));
    rows.put(4, jaccard(new long[] {2, 5}, 1, 1));

    TermIndex index =
        new TermIndex(
            config("jaccard", Map.of(ConfigKeys.MAX_FRACTION_IDS_PER_TERM, "0.5")),
            rows,
            longObjectMap());

    assertArrayEquals(new long[] {1}, index.getDiscardedTermsForTests());
  }

  @Test
  void nearestNeighborsDeduplicateCandidatesAndOmitRowsSharingNoTerm() {
    LongObjectHashMap<LongTermsAndValues> rows = longObjectMap();
    rows.put(1, jaccard(new long[] {1, 2}, 1, 1));
    rows.put(2, jaccard(new long[] {1, 3}, 1, 1));
    rows.put(3, jaccard(new long[] {4}, 1));
    rows.put(4, jaccard(new long[] {1, 2, 3}, 1, 1, 1));
    TermIndex index = new TermIndex(config("jaccard"), rows, longObjectMap());

    List<RowNumAndSimilarity> result =
        index.getNearestNeighborRowNums(4, jaccard(new long[] {1, 2}, 1, 1), MetaFilter.empty());

    // Row 3 shares no term with the query, so it is omitted even though k exceeds the match count.
    assertEquals(List.of(1L, 4L, 2L), rowNumsNearestFirst(result));
    LongFloatHashMap similarities = rowNumToSimilarity(result);
    assertEquals(1.0f, similarities.get(1), DELTA);
    assertEquals(2.0f / 3.0f, similarities.get(4), DELTA);
    assertEquals(1.0f / 3.0f, similarities.get(2), DELTA);
  }

  @Test
  void nearestNeighborsTightenMinSimilarityAndBreakTiesByLowestRowNum() {
    LongObjectHashMap<LongTermsAndValues> rows = longObjectMap();
    rows.put(20, jaccard(new long[] {1, 2}, 1, 1));
    rows.put(19, jaccard(new long[] {1, 2}, 1, 1));
    rows.put(30, jaccard(new long[] {2, 3}, 1, 1));
    rows.put(31, jaccard(new long[] {2, 4}, 1, 1));
    TermIndex index = new TermIndex(config("jaccard"), rows, longObjectMap());

    List<RowNumAndSimilarity> result =
        index.getNearestNeighborRowNums(1, jaccard(new long[] {1, 2}, 1, 1), MetaFilter.empty());

    assertEquals(List.of(19L), rowNumsNearestFirst(result));
  }

  @Test
  void similarRowsUseLengthAndPrefixFilteringWithoutDroppingMatches() {
    LongObjectHashMap<LongTermsAndValues> rows = longObjectMap();
    rows.put(10, jaccard(new long[] {1}, 1));
    rows.put(20, jaccard(new long[] {1, 2}, 1, 1));
    rows.put(40, jaccard(new long[] {1, 2, 3, 4}, 1, 1, 1, 1));
    TermIndex index = new TermIndex(config("jaccard"), rows, longObjectMap());
    long[] invertedList = index.getRowNumsForKeyForTests(ONLY_SHARD, 1);

    int first =
        index.getFirstMatchingUniValueForTests(invertedList, 2.0, 0.6, 0, invertedList.length);
    int last =
        index.getLastMatchingUniValueForTests(invertedList, 2.0, 0.6, first, invertedList.length);

    assertEquals(1, first);
    assertEquals(2, last);
    List<RowNumAndSimilarity> result =
        index.getSimilarRowNums(0.6f, jaccard(new long[] {1, 2}, 1, 1), MetaFilter.empty());
    assertEquals(List.of(20L), rowNumsNearestFirst(result));
  }

  @Test
  void longInvertedListsUseBinaryLengthBounds() {
    LongObjectHashMap<LongTermsAndValues> rows = longObjectMap();
    for (int numTerms = 1; numTerms <= 80; ++numTerms) {
      rows.put(numTerms, jaccard(sequentialTerms(numTerms), repeatedValue(1.0f, numTerms)));
    }
    TermIndex index = new TermIndex(config("jaccard"), rows, longObjectMap());
    long[] invertedList = index.getRowNumsForKeyForTests(ONLY_SHARD, 1);

    int first =
        index.getFirstMatchingUniValueForTests(invertedList, 40.0, 0.8, 0, invertedList.length);
    int last =
        index.getLastMatchingUniValueForTests(invertedList, 40.0, 0.8, first, invertedList.length);

    assertEquals(31, first);
    assertEquals(50, last);
    List<Long> expectedRowNums = new ArrayList<>();
    for (long rowNum = 33; rowNum <= 49; ++rowNum) {
      expectedRowNums.add(rowNum);
    }
    List<RowNumAndSimilarity> result =
        index.getSimilarRowNums(
            0.8f, jaccard(sequentialTerms(40), repeatedValue(1.0f, 40)), MetaFilter.empty());
    assertEquals(
        expectedRowNums, result.stream().map(RowNumAndSimilarity::getRowNum).sorted().toList());
  }

  @Test
  void zeroUniValueQueriesSearchTheTermIndex() {
    LongObjectHashMap<LongTermsAndValues> rows = longObjectMap();
    rows.put(1, jaccard(new long[] {1}, 0));
    rows.put(2, jaccard(new long[] {2}, 0));
    rows.put(3, jaccard(new long[] {2}, 1));
    TermIndex index = new TermIndex(config("jaccard"), rows, longObjectMap());

    List<RowNumAndSimilarity> result =
        index.getNearestNeighborRowNums(3, jaccard(new long[] {2}, 0), MetaFilter.empty());

    assertEquals(List.of(2L, 3L), rowNumsNearestFirst(result));
    LongFloatHashMap similarities = rowNumToSimilarity(result);
    assertEquals(1.0f, similarities.get(2), DELTA);
    assertEquals(0.0f, similarities.get(3), DELTA);
    assertTrue(
        index.getNearestNeighborRowNums(3, jaccard(new long[] {3}, 0), MetaFilter.empty()).isEmpty());
  }

  @Test
  void l2QueriesUseTheTermIndexAndRequireASharedTerm() {
    LongObjectHashMap<LongTermsAndValues> rows = longObjectMap();
    rows.put(1, l2(new long[] {1}, 1));
    rows.put(2, l2(new long[] {2}, 1));
    TermIndex index = new TermIndex(config("l2"), rows, longObjectMap());

    List<RowNumAndSimilarity> result =
        index.getNearestNeighborRowNums(2, l2(new long[] {1}, 1), MetaFilter.empty());

    // Row 2 shares no term, so it is omitted even though its L2 similarity is positive.
    assertEquals(List.of(1L), rowNumsNearestFirst(result));
    assertEquals(1.0f, result.get(0).getSimilarity(), DELTA);
    assertTrue(index.getNearestNeighborRowNums(1, l2(new long[] {3}, 1), MetaFilter.empty()).isEmpty());
  }

  @Test
  void metadataStrategiesAndDeletionAreApplied() {
    LongObjectHashMap<LongTermsAndValues> rows = longObjectMap();
    rows.put(1, jaccard(new long[] {1, 2}, 1, 1));
    rows.put(2, jaccard(new long[] {1}, 1));
    rows.put(3, jaccard(new long[] {2}, 1));
    LongObjectHashMap<LongMeta> metadata = longObjectMap();
    metadata.put(1, longMeta("city", "sf"));
    metadata.put(2, longMeta("city", "la"));
    metadata.put(3, longMeta("city", "ny"));
    TermIndex index =
        new TermIndex(
            config("jaccard", Map.of(Index.MAX_PRE_FILTERING_ROWS_RATIO, "0.34")), rows, metadata);

    MetaFilter sf = new MetaFilter(Map.of("city", List.of("sf")));
    List<RowNumAndSimilarity> result =
        index.getNearestNeighborRowNums(1, jaccard(new long[] {1, 2}, 1, 1), sf);

    assertEquals(List.of(1L), rowNumsNearestFirst(result));
    assertEquals(
        MetadataFilteringStrategy.PRE_FILTERING,
        index.getResolvedMetadataFilteringStrategyForLastSearchForTests());
    assertTrue(index.delete(1));
    assertFalse(index.delete(1));
    assertTrue(index.getNearestNeighborRowNums(3, jaccard(new long[] {1, 2}, 1, 1), sf).isEmpty());
    assertFalse(index.getAll().containsKey(1));
  }

  @Test
  void preFilteringFallsBackToInFilteringAndPostFilteringExpandsCandidatePool() {
    LongObjectHashMap<LongTermsAndValues> rows = longObjectMap();
    rows.put(1, jaccard(new long[] {1, 2}, 1, 1));
    rows.put(2, jaccard(new long[] {1}, 1));
    rows.put(3, jaccard(new long[] {2, 3}, 1, 1));
    LongObjectHashMap<LongMeta> metadata = longObjectMap();
    metadata.put(1, longMeta("city", "sf"));
    metadata.put(2, longMeta("city", "sf"));
    metadata.put(3, longMeta("city", "la"));
    MetaFilter sf = new MetaFilter(Map.of("city", List.of("sf")));
    TermIndex preFilteringIndex =
        new TermIndex(
            config(
                "jaccard",
                Map.of(
                    Index.METADATA_FILTERING_STRATEGY,
                    "pre_filtering",
                    Index.MAX_PRE_FILTERING_ROWS_RATIO,
                    "0.34")),
            rows,
            metadata);

    List<RowNumAndSimilarity> preFilteringResult =
        preFilteringIndex.getNearestNeighborRowNums(3, jaccard(new long[] {1, 2}, 1, 1), sf);

    assertEquals(List.of(1L, 2L), rowNumsNearestFirst(preFilteringResult));
    assertEquals(
        MetadataFilteringStrategy.IN_FILTERING,
        preFilteringIndex.getResolvedMetadataFilteringStrategyForLastSearchForTests());

    TermIndex postFilteringIndex =
        new TermIndex(
            config("jaccard", Map.of(Index.METADATA_FILTERING_STRATEGY, "post_filtering")),
            rows,
            metadata);
    List<RowNumAndSimilarity> postFilteringResult =
        postFilteringIndex.getNearestNeighborRowNums(
            1, jaccard(new long[] {1, 2}, 1, 1), new MetaFilter(Map.of("city", List.of("la"))));

    assertEquals(List.of(3L), rowNumsNearestFirst(postFilteringResult));
    assertEquals(
        MetadataFilteringStrategy.POST_FILTERING,
        postFilteringIndex.getResolvedMetadataFilteringStrategyForLastSearchForTests());
  }

  @Test
  void constructorRejectsInvalidSparseRecordsAndQueriesRejectInvalidParameters() {
    LongObjectHashMap<LongTermsAndValues> emptyTerms = longObjectMap();
    emptyTerms.put(1, LongTermsAndValuesTestFactory.create(new long[0], new float[] {1}, 1.0));
    LongObjectHashMap<LongTermsAndValues> unsortedTerms = longObjectMap();
    unsortedTerms.put(1, jaccard(new long[] {2, 1}, 1, 1));
    LongObjectHashMap<LongTermsAndValues> wrongUniValue = longObjectMap();
    wrongUniValue.put(
        1, LongTermsAndValuesTestFactory.create(new long[] {1}, new float[] {1}, 2.0));

    assertThrows(
        IndexCreationError.class,
        () -> new TermIndex(config("jaccard"), emptyTerms, longObjectMap()));
    assertThrows(
        IndexCreationError.class,
        () -> new TermIndex(config("jaccard"), unsortedTerms, longObjectMap()));
    assertThrows(
        IndexCreationError.class,
        () -> new TermIndex(config("jaccard"), wrongUniValue, longObjectMap()));

    TermIndex index =
        new TermIndex(
            config("jaccard"), longObjectMap(1, jaccard(new long[] {1}, 1)), longObjectMap());
    assertThrows(
        IllegalArgumentException.class,
        () -> index.getNearestNeighborRowNums(0, jaccard(new long[] {1}, 1), MetaFilter.empty()));
    assertThrows(
        IllegalArgumentException.class,
        () -> index.getSimilarRowNums(1.1f, jaccard(new long[] {1}, 1), MetaFilter.empty()));
  }

  @Test
  void invalidIndexParamsAndUniValueSearchRangesAreRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TermIndex(
                config(
                    "jaccard", Map.of(ConfigKeys.MAX_FRACTION_IDS_PER_TERM, "not-a-number")),
                longObjectMap(),
                longObjectMap()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new TermIndex(
                config("jaccard", Map.of(ConfigKeys.MAX_FRACTION_IDS_PER_TERM, "0")),
                longObjectMap(),
                longObjectMap()));

    TermIndex index =
        new TermIndex(
            config("jaccard"), longObjectMap(1, jaccard(new long[] {1}, 1)), longObjectMap());
    long[] invertedList = index.getRowNumsForKeyForTests(ONLY_SHARD, 1);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            index.getFirstMatchingUniValueForTests(
                invertedList, Comparator.UNSET_UNI_VALUE, 0.5, 0, 1));
    assertThrows(
        IndexOutOfBoundsException.class,
        () -> index.getLastMatchingUniValueForTests(invertedList, 1.0, 0.5, -1, 1));
  }

  @Test
  void uniValueBoundsHandleQueryAboveTheEntireInvertedListRange() {
    LongObjectHashMap<LongTermsAndValues> rows = longObjectMap();
    rows.put(1, jaccard(new long[] {1}, 1));
    rows.put(2, jaccard(new long[] {1, 2}, 1, 1));
    TermIndex index = new TermIndex(config("jaccard"), rows, longObjectMap());
    long[] invertedList = index.getRowNumsForKeyForTests(ONLY_SHARD, 1);

    assertEquals(
        invertedList.length,
        index.getFirstMatchingUniValueForTests(invertedList, 100.0, 0.9, 0, 2));
    assertEquals(
        invertedList.length, index.getLastMatchingUniValueForTests(invertedList, 100.0, 0.9, 0, 2));
  }

  @Test
  void emptyTermIndexReturnsNoResults() {
    TermIndex index = new TermIndex(config("jaccard"), longObjectMap(), longObjectMap());

    assertTrue(
        index.getNearestNeighborRowNums(1, jaccard(new long[] {1}, 1), MetaFilter.empty()).isEmpty());
  }

  @Test
  void invertedListSearchSkipsAnEmptyVerificationRow() throws ReflectiveOperationException {
    TermIndex index =
        new TermIndex(
            config("jaccard"), longObjectMap(1, jaccard(new long[] {1}, 1)), longObjectMap());
    verificationRows(index)
        .put(1, LongTermsAndValuesTestFactory.create(new long[0], new float[0], 0.0));

    assertTrue(
        index.getNearestNeighborRowNums(1, jaccard(new long[] {1}, 1), MetaFilter.empty()).isEmpty());
  }

  @Test
  void invalidKeyContributionIsRejectedDuringSearch() {
    InvalidContributionInvertedIndex index =
        new InvalidContributionInvertedIndex(
            config("jaccard"), longObjectMap(1, jaccard(new long[] {1}, 1)), longObjectMap());

    assertThrows(
        IllegalArgumentException.class,
        () -> index.getNearestNeighborRowNums(1, jaccard(new long[] {1}, 1), MetaFilter.empty()));
  }

  @Test
  void constructorRejectsNullRowsAndMismatchedSparseLengths() throws ReflectiveOperationException {
    LongObjectHashMap<LongTermsAndValues> nullRow = longObjectMap();
    nullRow.put(1, null);
    LongObjectHashMap<LongTermsAndValues> mismatchedLengths = longObjectMap();
    LongTermsAndValues malformed = jaccard(new long[] {1}, 1);
    Field values = LongTermsAndValues.class.getDeclaredField("values");
    values.setAccessible(true);
    values.set(malformed, new float[] {1, 2});
    mismatchedLengths.put(1, malformed);

    assertThrows(
        IndexCreationError.class, () -> new TermIndex(config("jaccard"), nullRow, longObjectMap()));
    assertThrows(
        IndexCreationError.class,
        () -> new TermIndex(config("jaccard"), mismatchedLengths, longObjectMap()));
  }

  @Test
  void uniValueLookupRejectsAStaleInvertedList() throws ReflectiveOperationException {
    TermIndex index =
        new TermIndex(
            config("jaccard"), longObjectMap(1, jaccard(new long[] {1}, 1)), longObjectMap());
    long[] invertedList = index.getRowNumsForKeyForTests(ONLY_SHARD, 1);
    rowNumToUniValues(index).remove(1);

    assertThrows(
        IllegalStateException.class,
        () ->
            index.getFirstMatchingUniValueForTests(invertedList, 2.0, 0.5, 0, invertedList.length));
  }

  @Test
  void prefixFilteringUsesTheUniValueOfTheKeyDomain() {
    LongObjectHashMap<LongTermsAndValues> rows = longObjectMap(1, jaccard(new long[] {7}, 1));
    CapturingInvertedIndex index = new CapturingInvertedIndex(config("jaccard"), rows, longObjectMap());

    assertEquals(
        List.of(1L),
        rowNumsNearestFirst(
            index.getNearestNeighborRowNums(1, jaccard(new long[] {7}, 1), MetaFilter.empty())));
    assertEquals(5.0, index.getLastKeysUniValue(), DELTA);
  }

  @Test
  void randomizedResultsMatchScanIndexForEverySparseComparator() {
    for (String comparatorType : List.of("jaccard", "ruzicka", "l2")) {
      Random random = new Random(826_366L + comparatorType.hashCode());
      LongObjectHashMap<LongTermsAndValues> rows = randomRows(random, comparatorType, 80);
      NamespaceConfig config = config(comparatorType);
      TermIndex termIndex = new TermIndex(config, rows, longObjectMap());
      ScanIndex scanIndex = new ScanIndex(config, rows, longObjectMap());

      for (int queryIndex = 0; queryIndex < 60; ++queryIndex) {
        LongTermsAndValues query = randomSparseRecord(random, comparatorType);
        int k = 1 + random.nextInt(10);
        float minSimilarity = new float[] {0.0f, 0.2f, 0.5f, 0.8f}[random.nextInt(4)];

        // The scan index scores every row, so restrict its results to rows sharing a term.
        assertEquivalent(
            comparatorType + " nearest queryIndex=" + queryIndex + " k=" + k + " query=" + query,
            restrictToRowsSharingATerm(
                scanIndex.getNearestNeighborRowNums(rows.size(), query, MetaFilter.empty()),
                rows,
                query,
                k),
            termIndex.getNearestNeighborRowNums(k, query, MetaFilter.empty()));
        assertEquivalent(
            comparatorType
                + " minimum similarity queryIndex="
                + queryIndex
                + " minSimilarity="
                + minSimilarity
                + " query="
                + query,
            restrictToRowsSharingATerm(
                scanIndex.getSimilarRowNums(minSimilarity, query, MetaFilter.empty()),
                rows,
                query,
                rows.size()),
            termIndex.getSimilarRowNums(minSimilarity, query, MetaFilter.empty()));
      }
    }
  }

  /** The merge generator must reach and score the same rows as the filtered scan. */
  @Test
  void randomizedMergeResultsMatchFilteredScanForEverySparseComparator() {
    for (String comparatorType : List.of("jaccard", "ruzicka", "l2")) {
      Random random = new Random(826_366L + comparatorType.hashCode());
      LongObjectHashMap<LongTermsAndValues> rows = randomRows(random, comparatorType, 80);
      TermIndex filteredScanIndex =
          new TermIndex(config(comparatorType, Map.of(), "inverted_term"), rows, longObjectMap());
      TermIndex mergeIndex = new TermIndex(mergeConfig(comparatorType), rows, longObjectMap());

      for (int queryIndex = 0; queryIndex < 60; ++queryIndex) {
        LongTermsAndValues query = randomSparseRecord(random, comparatorType);
        int k = 1 + random.nextInt(10);
        float minSimilarity = new float[] {0.0f, 0.2f, 0.5f, 0.8f}[random.nextInt(4)];

        assertEquivalent(
            comparatorType + " merge nearest queryIndex=" + queryIndex + " k=" + k,
            filteredScanIndex.getNearestNeighborRowNums(k, query, MetaFilter.empty()),
            mergeIndex.getNearestNeighborRowNums(k, query, MetaFilter.empty()));
        assertEquivalent(
            comparatorType
                + " merge minimum similarity queryIndex="
                + queryIndex
                + " minSimilarity="
                + minSimilarity,
            filteredScanIndex.getSimilarRowNums(minSimilarity, query, MetaFilter.empty()),
            mergeIndex.getSimilarRowNums(minSimilarity, query, MetaFilter.empty()));
      }
    }
  }

  /**
   * Metadata filtering wraps candidate generation, so the merge must agree with the filtered scan
   * under every strategy, including pre-filtering, which bypasses the generator.
   */
  @Test
  void mergeResultsMatchFilteredScanUnderEveryMetadataFilteringStrategy() {
    for (String comparatorType : List.of("jaccard", "l2")) {
      for (String strategy : List.of("auto", "pre_filtering", "in_filtering", "post_filtering")) {
        Random random = new Random(5_512L + strategy.hashCode() + comparatorType.hashCode());
        LongObjectHashMap<LongTermsAndValues> rows = randomRows(random, comparatorType, 40);
        LongObjectHashMap<LongMeta> metadata = longObjectMap();
        for (LongObjectCursor<LongTermsAndValues> row : rows) {
          metadata.put(row.key, longMeta("city", row.key % 2 == 0 ? "sf" : "la"));
        }
        Map<String, String> strategyParams =
            Map.of(
                Index.METADATA_FILTERING_STRATEGY, strategy,
                Index.MAX_PRE_FILTERING_ROWS_RATIO, "0.9");
        TermIndex filteredScanIndex =
            new TermIndex(config(comparatorType, strategyParams, "inverted_term"), rows, metadata);
        TermIndex mergeIndex =
            new TermIndex(
                config(comparatorType, withMergeParam(strategyParams), "inverted_term"), rows, metadata);
        MetaFilter sf = new MetaFilter(Map.of("city", List.of("sf")));

        for (int queryIndex = 0; queryIndex < 20; ++queryIndex) {
          LongTermsAndValues query = randomSparseRecord(random, comparatorType);
          int k = 1 + random.nextInt(8);
          assertEquivalent(
              comparatorType
                  + " "
                  + strategy
                  + " merge nearest queryIndex="
                  + queryIndex
                  + " k="
                  + k,
              filteredScanIndex.getNearestNeighborRowNums(k, query, sf),
              mergeIndex.getNearestNeighborRowNums(k, query, sf));
          assertEquivalent(
              comparatorType
                  + " "
                  + strategy
                  + " merge minimum similarity queryIndex="
                  + queryIndex,
              filteredScanIndex.getSimilarRowNums(0.2f, query, sf),
              mergeIndex.getSimilarRowNums(0.2f, query, sf));
        }
      }
    }
  }

  /**
   * Popularity filtering makes the verification rows differ from the indexed rows, so pre-filtering
   * agrees with the merge only if it scores the verification rows.
   */
  @Test
  void mergeResultsMatchFilteredScanWhenPopularTermsAreDropped() {
    Random random = new Random(9_713L);
    LongObjectHashMap<LongTermsAndValues> rows = randomRows(random, "jaccard", 40);
    LongObjectHashMap<LongMeta> metadata = longObjectMap();
    for (LongObjectCursor<LongTermsAndValues> row : rows) {
      metadata.put(row.key, longMeta("city", row.key % 2 == 0 ? "sf" : "la"));
    }
    Map<String, String> popularityParams =
        Map.of(
            ConfigKeys.MAX_FRACTION_IDS_PER_TERM, "0.2",
            Index.METADATA_FILTERING_STRATEGY, "pre_filtering",
            Index.MAX_PRE_FILTERING_ROWS_RATIO, "0.9");
    TermIndex filteredScanIndex =
        new TermIndex(config("jaccard", popularityParams, "inverted_term"), rows, metadata);
    TermIndex mergeIndex =
        new TermIndex(config("jaccard", withMergeParam(popularityParams), "inverted_term"), rows, metadata);
    assertTrue(mergeIndex.discardsPopularTerms());
    assertTrue(mergeIndex.getDiscardedTermsForTests().length > 0);
    MetaFilter sf = new MetaFilter(Map.of("city", List.of("sf")));

    for (int queryIndex = 0; queryIndex < 20; ++queryIndex) {
      LongTermsAndValues query = randomSparseRecord(random, "jaccard");
      int k = 1 + random.nextInt(8);
      assertEquivalent(
          "merge nearest queryIndex=" + queryIndex + " k=" + k,
          filteredScanIndex.getNearestNeighborRowNums(k, query, sf),
          mergeIndex.getNearestNeighborRowNums(k, query, sf));
      assertEquivalent(
          "merge minimum similarity queryIndex=" + queryIndex,
          filteredScanIndex.getSimilarRowNums(0.2f, query, sf),
          mergeIndex.getSimilarRowNums(0.2f, query, sf));
    }
  }

  /**
   * Rejected rather than returning the value at the insertion point, which would score the record
   * as carrying a term it does not have.
   */
  @Test
  void readingAValueAtAnAbsentKeyIsRejected() {
    LongObjectHashMap<LongTermsAndValues> rows = longObjectMap();
    rows.put(1, jaccard(new long[] {2, 4, 6}, 1, 1, 1));
    TermIndex index = new TermIndex(config("jaccard"), rows, longObjectMap());
    LongTermsAndValues record = rows.get(1);

    assertEquals(1.0f, index.getValueAtKey(record, 4), DELTA);
    for (long absentKey : new long[] {1, 3, 5, 7}) {
      assertThrows(
          IllegalArgumentException.class,
          () -> index.getValueAtKey(record, absentKey),
          "key " + absentKey);
    }
  }

  /** A query key the index has no list for contributes nothing and no frontier entry either. */
  @Test
  void mergeSkipsTheQueryKeysThatKeyNoInvertedList() {
    LongObjectHashMap<LongTermsAndValues> rows = longObjectMap();
    rows.put(1, jaccard(new long[] {1, 2}, 1, 1));
    rows.put(2, jaccard(new long[] {3, 4}, 1, 1));
    TermIndex index = new TermIndex(mergeConfig("jaccard"), rows, longObjectMap());

    List<RowNumAndSimilarity> results =
        index.getSimilarRowNums(0.2f, jaccard(new long[] {1, 2, 99}, 1, 1, 1), MetaFilter.empty());

    assertEquals(List.of(1L), rowNumsNearestFirst(results));
    assertEquals(2.0f / 3.0f, results.get(0).getSimilarity(), DELTA);
  }

  /** Four jaccard rows in which term 1 is popular enough to be discarded at a 0.5 cap. */
  private static LongObjectHashMap<LongTermsAndValues> popularTermRows() {
    LongObjectHashMap<LongTermsAndValues> rows = longObjectMap();
    rows.put(1, jaccard(new long[] {1, 2}, 1, 1));
    rows.put(2, jaccard(new long[] {1, 3}, 1, 1));
    rows.put(3, jaccard(new long[] {1, 4}, 1, 1));
    rows.put(4, jaccard(new long[] {5}, 1));
    return rows;
  }

  private static Map<String, String> withMergeParam(Map<String, String> indexParams) {
    Map<String, String> merged = new LinkedHashMap<>(indexParams);
    merged.put(
        ConfigKeys.CANDIDATE_GENERATOR,
        NamespaceConfig.CandidateGeneratorType.SPARS_MERGE.getParamValue());
    return merged;
  }

  private static NamespaceConfig mergeConfig(String comparatorType) {
    return config(
        comparatorType,
        Map.of(
            ConfigKeys.CANDIDATE_GENERATOR,
            NamespaceConfig.CandidateGeneratorType.SPARS_MERGE.getParamValue()),
        "inverted_term");
  }

  private static NamespaceConfig config(String comparatorType) {
    return config(comparatorType, Map.of());
  }

  private static NamespaceConfig config(String comparatorType, Map<String, String> indexParams) {
    return config(comparatorType, indexParams, "inverted_term");
  }

  private static NamespaceConfig config(
      String comparatorType, Map<String, String> indexParams, String indexType) {
    return NamespaceConfig.builder()
        .minTermsAndValuesLength(0)
        .maxTermsAndValuesLength(100)
        .maxCacheSize(100)
        .cacheType("scan")
        .indexType(indexType)
        .indexParams(indexParams)
        .comparatorType(comparatorType)
        .comparatorNormalizerType(comparatorType.equals("l2") ? "reciprocal" : "identity")
        .maxNumSearchableStructures(3)
        .maxNumSimilarities(100)
        .build();
  }

  private static LongTermsAndValues jaccard(long[] terms, float... values) {
    double uniValue = 0.0;
    for (float value : values) {
      uniValue += Math.abs(Math.signum(value));
    }
    return LongTermsAndValuesTestFactory.create(terms, values, uniValue);
  }

  private static LongTermsAndValues ruzicka(long[] terms, float... values) {
    double uniValue = 0.0;
    for (float value : values) {
      uniValue += Math.abs(value);
    }
    return LongTermsAndValuesTestFactory.create(terms, values, uniValue);
  }

  private static LongTermsAndValues l2(long[] terms, float... values) {
    double uniValue = 0.0;
    for (float value : values) {
      uniValue += (double) value * value;
    }
    return LongTermsAndValuesTestFactory.create(terms, values, uniValue);
  }

  @SuppressWarnings("unchecked")
  private static LongObjectHashMap<LongTermsAndValues> verificationRows(BaseInvertedIndex index)
      throws ReflectiveOperationException {
    Field field = BaseInvertedIndex.class.getDeclaredField("verificationRowNumToTermsAndValuesMap");
    field.setAccessible(true);
    return (LongObjectHashMap<LongTermsAndValues>) field.get(index);
  }

  @SuppressWarnings("unchecked")
  private static LongDoubleHashMap rowNumToUniValues(BaseInvertedIndex index)
      throws ReflectiveOperationException {
    Field field = BaseInvertedIndex.class.getDeclaredField("rowNumToUniValue");
    field.setAccessible(true);
    return (LongDoubleHashMap) field.get(index);
  }

  private static com.uber.ussi.comparator.Comparator comparator(BaseInvertedIndex index)
      throws ReflectiveOperationException {
    Field field =
        com.uber.ussi.searchablestructure.index.Index.class.getDeclaredField("comparator");
    field.setAccessible(true);
    return (com.uber.ussi.comparator.Comparator) field.get(index);
  }

  private static LongMeta longMeta(String key, String value) {
    return new LongMeta(Map.of(key, value), /* requireLongKeysAndValues */ false);
  }

  private static LongFloatHashMap rowNumToSimilarity(List<RowNumAndSimilarity> rows) {
    LongFloatHashMap similarities = new LongFloatHashMap(rows.size());
    for (RowNumAndSimilarity row : rows) {
      similarities.put(row.getRowNum(), row.getSimilarity());
    }
    return similarities;
  }

  private static List<Long> rowNumsNearestFirst(List<RowNumAndSimilarity> rows) {
    return rows.stream()
        .sorted(RowNumAndSimilarity.NEAREST_FIRST)
        .map(RowNumAndSimilarity::getRowNum)
        .toList();
  }

  private static List<RowNumAndSimilarity> restrictToRowsSharingATerm(
      List<RowNumAndSimilarity> results,
      LongObjectHashMap<LongTermsAndValues> rows,
      LongTermsAndValues query,
      int maxResults) {
    return results.stream()
        .filter(result -> query.sharesAnyTerm(rows.get(result.getRowNum())))
        .sorted(RowNumAndSimilarity.NEAREST_FIRST)
        .limit(maxResults)
        .toList();
  }

  private static LongObjectHashMap<LongTermsAndValues> randomRows(
      Random random, String comparatorType, int numRows) {
    LongObjectHashMap<LongTermsAndValues> rows = longObjectMap();
    for (int rowNum = 0; rowNum < numRows; ++rowNum) {
      rows.put(rowNum, randomSparseRecord(random, comparatorType));
    }
    return rows;
  }

  private static LongTermsAndValues randomSparseRecord(Random random, String comparatorType) {
    int numTerms = 1 + random.nextInt(6);
    TreeMap<Long, Float> valuesByTerm = new TreeMap<>();
    while (valuesByTerm.size() < numTerms) {
      long term = random.nextInt(24);
      float magnitude = 0.25f * (1 + random.nextInt(8));
      valuesByTerm.put(term, random.nextBoolean() ? magnitude : -magnitude);
    }
    long[] terms = new long[numTerms];
    float[] values = new float[numTerms];
    int index = 0;
    for (Map.Entry<Long, Float> entry : valuesByTerm.entrySet()) {
      terms[index] = entry.getKey();
      values[index] = entry.getValue();
      ++index;
    }
    return switch (comparatorType) {
      case "jaccard" -> jaccard(terms, values);
      case "ruzicka" -> ruzicka(terms, values);
      case "l2" -> l2(terms, values);
      default -> throw new IllegalArgumentException("Unsupported comparator " + comparatorType);
    };
  }

  private static long[] sequentialTerms(int numTerms) {
    long[] terms = new long[numTerms];
    for (int i = 0; i < numTerms; ++i) {
      terms[i] = i + 1L;
    }
    return terms;
  }

  private static float[] repeatedValue(float value, int count) {
    float[] values = new float[count];
    Arrays.fill(values, value);
    return values;
  }

  private static void assertEquivalent(
      String message, List<RowNumAndSimilarity> expected, List<RowNumAndSimilarity> actual) {
    List<RowNumAndSimilarity> sortedExpected = new ArrayList<>(expected);
    List<RowNumAndSimilarity> sortedActual = new ArrayList<>(actual);
    sortedExpected.sort(RowNumAndSimilarity.NEAREST_FIRST);
    sortedActual.sort(RowNumAndSimilarity.NEAREST_FIRST);
    assertEquals(
        sortedExpected.stream().map(RowNumAndSimilarity::getRowNum).toList(),
        sortedActual.stream().map(RowNumAndSimilarity::getRowNum).toList(),
        message + " expected=" + sortedExpected + " actual=" + sortedActual);
    for (int i = 0; i < sortedExpected.size(); ++i) {
      assertEquals(
          sortedExpected.get(i).getSimilarity(), sortedActual.get(i).getSimilarity(), DELTA);
    }
  }

  private static final class CapturingInvertedIndex extends BaseInvertedIndex {
    private double lastKeysUniValue;

    private CapturingInvertedIndex(
        NamespaceConfig namespaceConfig,
        LongObjectHashMap<LongTermsAndValues> rowNumToTermsAndValuesMap,
        LongObjectHashMap<LongMeta> rowNumToMetaMap) {
      super(namespaceConfig, rowNumToTermsAndValuesMap, rowNumToMetaMap, IndexType.INVERTED_TERM);
    }

    @Override
    protected double getMaxPrefixSum(
        double keysUniValue, double recordUniValue, double minSimilarity) {
      lastKeysUniValue = keysUniValue;
      return Double.POSITIVE_INFINITY;
    }

    @Override
    protected KeyAndUniTransformedValue[] getKeysAndUniTransformedValues(
        LongTermsAndValues termsAndValues) {
      return new KeyAndUniTransformedValue[] {
        new KeyAndUniTransformedValue(termsAndValues.getTerm(0), 5.0)
      };
    }

    @Override
    protected long[] getKeys(LongTermsAndValues termsAndValues) {
      return termsAndValues.getTerms();
    }

    @Override
    protected float getValueAtKey(LongTermsAndValues termsAndValues, long key) {
      return termsAndValues.getValue(0);
    }

    private double getLastKeysUniValue() {
      return lastKeysUniValue;
    }
  }

  private static final class InvalidContributionInvertedIndex extends BaseInvertedIndex {
    private InvalidContributionInvertedIndex(
        NamespaceConfig namespaceConfig,
        LongObjectHashMap<LongTermsAndValues> rowNumToTermsAndValuesMap,
        LongObjectHashMap<LongMeta> rowNumToMetaMap) {
      super(namespaceConfig, rowNumToTermsAndValuesMap, rowNumToMetaMap, IndexType.INVERTED_TERM);
    }

    @Override
    protected double getMaxPrefixSum(
        double keysUniValue, double recordUniValue, double minSimilarity) {
      return Double.POSITIVE_INFINITY;
    }

    @Override
    protected KeyAndUniTransformedValue[] getKeysAndUniTransformedValues(
        LongTermsAndValues termsAndValues) {
      return new KeyAndUniTransformedValue[] {
        new KeyAndUniTransformedValue(termsAndValues.getTerm(0), Double.NaN)
      };
    }

    @Override
    protected long[] getKeys(LongTermsAndValues termsAndValues) {
      return termsAndValues.getTerms();
    }

    @Override
    protected float getValueAtKey(LongTermsAndValues termsAndValues, long key) {
      return termsAndValues.getValue(0);
    }
  }

  /** The same index keyed by the terms of a sequence rather than of a sparse record. */
  @Nested
  class OnSequences {
    /** Test indexes hold far fewer rows than a shard's minimum, so they have one shard. */
    private static final int ONLY_SHARD = 0;

    private static final float DELTA = 1e-6f;
    private static final float[] NO_VALUES = new float[0];
    private static final List<String> COMPARATOR_TYPES = List.of("gld", "ngld");
    private static final float[] MIN_SIMILARITIES = {0.0f, 0.25f, 0.5f, 0.75f, 0.9f};

    @Test
    void theInvertedListsAreKeyedByDistinctTermsWhileScoringKeepsTheSequences() {
      LongObjectHashMap<LongTermsAndValues> rows = longObjectMap();
      // Term 1 occurs three times in one row, so it keys one list entry, not three.
      rows.put(7, sequence(1, 1, 2, 1));
      rows.put(8, sequence(2, 3));

      TermIndex index = new TermIndex(config("ngld"), rows, longObjectMap());

      assertEquals(2, index.size());
      assertEquals(3, index.getNumIndexedKeysForTests(ONLY_SHARD));
      assertArrayEquals(new long[] {7}, index.getRowNumsForKeyForTests(ONLY_SHARD, 1));
      assertArrayEquals(new long[] {8, 7}, index.getRowNumsForKeyForTests(ONLY_SHARD, 2));
      // The indexed form is the multiset: ascending, distinct, counts as values.
      assertArrayEquals(new long[] {1, 2}, index.getIndexedRow(7).getTerms());
      assertArrayEquals(new float[] {3.0f, 1.0f}, index.getIndexedRow(7).getValues());
      // The scored form is the sequence as it arrived, ordered and with repeats.
      assertArrayEquals(new long[] {1, 1, 2, 1}, index.getVerificationRow(7).getTerms());
      assertEquals(0, index.getVerificationRow(7).valuesLength());
      // A sequence and its multiset agree on the Uni value, so length filtering reads one bound.
      assertEquals(4.0, index.getIndexedRow(7).getUniValue(), DELTA);
      assertEquals(4.0, index.getVerificationRow(7).getUniValue(), DELTA);
    }

    @Test
    void similarSearchMatchesABruteForceScan() {
      Random random = new Random(7_311L);
      for (String comparatorType : COMPARATOR_TYPES) {
        LongObjectHashMap<LongTermsAndValues> rows = randomRows(random, 60);
        TermIndex index = new TermIndex(config(comparatorType), rows, longObjectMap());
        ScanIndex bruteForce =
            new ScanIndex(config(comparatorType, Map.of(), "scan"), rows, longObjectMap());

        for (int trial = 0; trial < 40; ++trial) {
          LongTermsAndValues query = randomSequence(random);
          for (float minSimilarity : MIN_SIMILARITIES) {
            List<RowNumAndSimilarity> expected =
                restrictToRowsSharingAnTerm(
                    bruteForce.getSimilarRowNums(minSimilarity, query, null), rows, query);

            assertEquivalent(
                String.format(
                    "%s trial=%d minSimilarity=%s query=%s",
                    comparatorType, trial, minSimilarity, Arrays.toString(query.getTerms())),
                expected,
                index.getSimilarRowNums(minSimilarity, query, null));
          }
        }
      }
    }

    @Test
    void nearestNeighborSearchMatchesABruteForceScan() {
      Random random = new Random(5_150L);
      for (String comparatorType : COMPARATOR_TYPES) {
        LongObjectHashMap<LongTermsAndValues> rows = randomRows(random, 40);
        TermIndex index = new TermIndex(config(comparatorType), rows, longObjectMap());
        ScanIndex bruteForce =
            new ScanIndex(config(comparatorType, Map.of(), "scan"), rows, longObjectMap());

        for (int trial = 0; trial < 25; ++trial) {
          LongTermsAndValues query = randomSequence(random);
          int k = 1 + random.nextInt(5);
          List<RowNumAndSimilarity> expected =
              restrictToRowsSharingAnTerm(
                  bruteForce.getSimilarRowNums(0.0f, query, null), rows, query);
          expected.sort(RowNumAndSimilarity.NEAREST_FIRST);

          List<RowNumAndSimilarity> actual =
              new ArrayList<>(index.getNearestNeighborRowNums(k, query, null));

          // Tied rows are interchangeable in a top-k, so the similarities must agree, not the choice.
          assertEquals(
              Math.min(k, expected.size()),
              actual.size(),
              comparatorType + " trial=" + trial + " result count");
          actual.sort(RowNumAndSimilarity.NEAREST_FIRST);
          for (int i = 0; i < actual.size(); ++i) {
            assertEquals(
                expected.get(i).getSimilarity(),
                actual.get(i).getSimilarity(),
                DELTA,
                comparatorType + " trial=" + trial + " similarity at " + i);
          }
        }
      }
    }

    @Test
    void aQueryFindsARowItSharesOnlyRepeatedTermsWith() {
      LongObjectHashMap<LongTermsAndValues> rows = longObjectMap();
      rows.put(1, sequence(4, 4, 4, 4));
      TermIndex index = new TermIndex(config("ngld"), rows, longObjectMap());

      List<RowNumAndSimilarity> results = index.getSimilarRowNums(0.5f, sequence(4, 4, 4), null);

      assertEquals(1, results.size(), results.toString());
      // One deletion over a combined length of seven: NGLD = 2/8, so the similarity is 0.75.
      assertEquals(0.75f, results.get(0).getSimilarity(), DELTA);
    }

    @Test
    void orderIsWhatSeparatesTwoRowsWithTheSameTerms() {
      LongObjectHashMap<LongTermsAndValues> rows = longObjectMap();
      rows.put(1, sequence(1, 2, 3, 4));
      rows.put(2, sequence(4, 3, 2, 1));
      TermIndex index = new TermIndex(config("ngld"), rows, longObjectMap());

      List<RowNumAndSimilarity> results = index.getSimilarRowNums(0.0f, sequence(1, 2, 3, 4), null);

      assertEquals(2, results.size(), results.toString());
      // Identical multisets, so a multiset measure would score these two alike. The order does not.
      assertEquals(1.0f, similarityOf(results, 1), DELTA);
      assertTrue(
          similarityOf(results, 2) < 1.0f,
          "the reversed row should not be an exact match: " + results);
    }

    @Test
    void aRecordCarryingValuesIsRejected() {
      LongObjectHashMap<LongTermsAndValues> rows = longObjectMap();
      rows.put(1, LongTermsAndValuesTestFactory.create(new long[] {1, 2}, new float[] {1f, 1f}, 2.0));

      IndexCreationError error =
          assertThrows(
              IndexCreationError.class, () -> new TermIndex(config("ngld"), rows, longObjectMap()));

      assertTrue(error.getMessage().contains("must have no values"), error.getMessage());
    }

    /** Paired with a comparator that reads sparse records, these rows are short of values. */
    @Test
    void sequenceRowsAreRejectedForAComparatorThatReadsSparseRecords() {
      LongObjectHashMap<LongTermsAndValues> rows = longObjectMap();
      rows.put(1, sequence(1, 2));
      NamespaceConfig config = config("jaccard");

      IndexCreationError error =
          assertThrows(
              IndexCreationError.class, () -> new TermIndex(config, rows, longObjectMap()));

      assertTrue(
          error.getMessage().contains("must have equal non-empty terms and values lengths"),
          error.getMessage());
    }

    @Test
    void aPopularTermIsDroppedFromTheSequencesAndNotOnlyFromTheInvertedLists() {
      LongObjectHashMap<LongTermsAndValues> rows = longObjectMap();
      // Term 9 is in all four rows, so a 0.75 cap makes it the only popular one.
      rows.put(1, sequence(9, 1, 2));
      rows.put(2, sequence(1, 9, 2));
      rows.put(3, sequence(9, 3, 4));
      rows.put(4, sequence(9, 5, 6));

      TermIndex index =
          new TermIndex(
              config("ngld", Map.of(ConfigKeys.MAX_FRACTION_IDS_PER_TERM, "0.75"), "inverted_term"),
              rows,
              longObjectMap());

      assertEquals(0, index.getRowNumsForKeyForTests(ONLY_SHARD, 9).length);
      long[] rowNumsForTermTwo = index.getRowNumsForKeyForTests(ONLY_SHARD, 2).clone();
      Arrays.sort(rowNumsForTermTwo);
      assertArrayEquals(new long[] {1, 2}, rowNumsForTermTwo);
      // The term is gone from the scored sequence too, so rows 1 and 2 become identical.
      assertArrayEquals(new long[] {1, 2}, index.getVerificationRow(1).getTerms());
      assertArrayEquals(new long[] {1, 2}, index.getVerificationRow(2).getTerms());
      assertEquals(2.0, index.getVerificationRow(1).getUniValue(), DELTA);

      List<RowNumAndSimilarity> results = index.getSimilarRowNums(1.0f, sequence(9, 1, 2), null);

      assertEquals(2, results.size(), results.toString());
      for (RowNumAndSimilarity result : results) {
        assertEquals(1.0f, result.getSimilarity(), DELTA, results.toString());
      }
    }

    @Test
    void candidatesOnlyKeepsThePopularTermInTheScoredSequences() {
      LongObjectHashMap<LongTermsAndValues> rows = longObjectMap();
      rows.put(1, sequence(9, 1, 2));
      rows.put(2, sequence(1, 9, 2));
      rows.put(3, sequence(9, 3, 4));
      rows.put(4, sequence(9, 5, 6));

      TermIndex index =
          new TermIndex(
              config(
                  "ngld",
                  Map.of(
                      ConfigKeys.MAX_FRACTION_IDS_PER_TERM,
                      "0.75",
                      ConfigKeys.POPULAR_TERM_DISCARD_SCOPE,
                      PopularTermDiscardScope.CANDIDATES_ONLY.getParamValue()),
                  "inverted_term"),
              rows,
              longObjectMap());

      assertEquals(0, index.getRowNumsForKeyForTests(ONLY_SHARD, 9).length);
      assertEquals(2, index.getIndexedRow(1).termsLength());
      // The scored sequences keep the discarded term, so rows 1 and 2 are transpositions apart.
      assertArrayEquals(new long[] {9, 1, 2}, index.getVerificationRow(1).getTerms());
      assertArrayEquals(new long[] {1, 9, 2}, index.getVerificationRow(2).getTerms());

      List<RowNumAndSimilarity> results = index.getSimilarRowNums(1.0f, sequence(9, 1, 2), null);

      assertEquals(1, results.size(), results.toString());
      assertEquals(1, results.get(0).getRowNum(), results.toString());
      assertEquals(1.0f, results.get(0).getSimilarity(), DELTA, results.toString());
    }

    @Test
    void aSequenceLeftEmptyByTheDiscardIsSimplyNotIndexed() {
      LongObjectHashMap<LongTermsAndValues> rows = longObjectMap();
      // Both of row 1's terms are popular, so nothing of it survives to be indexed or scored.
      rows.put(1, sequence(8, 9));
      rows.put(2, sequence(8, 9, 1));
      rows.put(3, sequence(9, 8, 2));
      rows.put(4, sequence(8, 9, 3));

      TermIndex index =
          new TermIndex(
              config("ngld", Map.of(ConfigKeys.MAX_FRACTION_IDS_PER_TERM, "0.75"), "inverted_term"),
              rows,
              longObjectMap());

      assertEquals(0, index.getVerificationRow(1).termsLength());
      assertEquals(1, index.getSimilarRowNums(0.5f, sequence(8, 9, 1), null).size());
      // A query that does not survive the discard matches nothing rather than everything.
      assertTrue(index.getSimilarRowNums(0.0f, sequence(8, 9), null).isEmpty());
    }

    @Test
    void aDeletedRowIsExcludedFromResults() {
      LongObjectHashMap<LongTermsAndValues> rows = longObjectMap();
      rows.put(1, sequence(1, 2, 3));
      rows.put(2, sequence(1, 2, 3));
      TermIndex index = new TermIndex(config("ngld"), rows, longObjectMap());

      assertTrue(index.delete(1));
      List<RowNumAndSimilarity> results = index.getSimilarRowNums(0.5f, sequence(1, 2, 3), null);

      assertEquals(1, results.size(), results.toString());
      assertEquals(2, results.get(0).getRowNum());
    }

    @Test
    void metadataFilteringExcludesRowsThatDoNotMatch() {
      LongObjectHashMap<LongTermsAndValues> rows = longObjectMap();
      rows.put(1, sequence(1, 2, 3));
      rows.put(2, sequence(1, 2, 3));
      LongObjectHashMap<LongMeta> metadata = longObjectMap();
      metadata.put(1, longMeta("city", "sf"));
      metadata.put(2, longMeta("city", "la"));
      TermIndex index = new TermIndex(config("ngld"), rows, metadata);

      List<RowNumAndSimilarity> results =
          index.getSimilarRowNums(
              0.5f, sequence(1, 2, 3), new MetaFilter(Map.of("city", List.of("sf"))));

      assertEquals(1, results.size(), results.toString());
      assertEquals(1, results.get(0).getRowNum());
    }

    private static LongMeta longMeta(String key, String value) {
      return new LongMeta(Map.of(key, value), /* requireLongKeysAndValues */ false);
    }

    private static float similarityOf(List<RowNumAndSimilarity> results, long rowNum) {
      for (RowNumAndSimilarity result : results) {
        if (result.getRowNum() == rowNum) {
          return result.getSimilarity();
        }
      }
      throw new AssertionError("rowNum " + rowNum + " is absent from " + results);
    }

    /** Drops the rows sharing no term with the query, which the inverted lists cannot reach. */
    private static List<RowNumAndSimilarity> restrictToRowsSharingAnTerm(
        List<RowNumAndSimilarity> results,
        LongObjectHashMap<LongTermsAndValues> rows,
        LongTermsAndValues query) {
      LongHashSet queryTerms = LongHashSet.from(query.getTerms());
      List<RowNumAndSimilarity> restricted = new ArrayList<>(results.size());
      for (RowNumAndSimilarity result : results) {
        LongTermsAndValues row = rows.get(result.getRowNum());
        for (int index = 0; index < row.termsLength(); ++index) {
          if (queryTerms.contains(row.getTerm(index))) {
            restricted.add(result);
            break;
          }
        }
      }
      return restricted;
    }

    private static LongObjectHashMap<LongTermsAndValues> randomRows(Random random, int numRows) {
      LongObjectHashMap<LongTermsAndValues> rows = longObjectMap();
      for (int rowNum = 1; rowNum <= numRows; ++rowNum) {
        rows.put(rowNum, randomSequence(random));
      }
      return rows;
    }

    /** A short sequence over a small alphabet, so repeats and shared terms are both common. */
    private static LongTermsAndValues randomSequence(Random random) {
      long[] terms = new long[1 + random.nextInt(7)];
      for (int index = 0; index < terms.length; ++index) {
        terms[index] = random.nextInt(5);
      }
      return LongTermsAndValuesTestFactory.create(terms, NO_VALUES, terms.length);
    }

    private static LongTermsAndValues sequence(long... terms) {
      return LongTermsAndValuesTestFactory.create(terms, NO_VALUES, terms.length);
    }

    private static NamespaceConfig config(String comparatorType) {
      return config(comparatorType, Map.of(), "inverted_term");
    }

    private static NamespaceConfig config(
        String comparatorType, Map<String, String> indexParams, String indexType) {
      return NamespaceConfig.builder()
          .minTermsAndValuesLength(0)
          .maxTermsAndValuesLength(100)
          .maxCacheSize(100)
          .cacheType("scan")
          .indexType(indexType)
          .indexParams(indexParams)
          .comparatorType(comparatorType)
          // GLD reports an unbounded distance, so it needs a normalizer that maps one onto [0, 1].
          .comparatorNormalizerType(comparatorType.equals("gld") ? "reciprocal" : "complement")
          .maxNumSearchableStructures(3)
          .maxNumSimilarities(100)
          .build();
    }

    private static void assertEquivalent(
        String message, List<RowNumAndSimilarity> expected, List<RowNumAndSimilarity> actual) {
      List<RowNumAndSimilarity> sortedExpected = new ArrayList<>(expected);
      List<RowNumAndSimilarity> sortedActual = new ArrayList<>(actual);
      sortedExpected.sort(RowNumAndSimilarity.NEAREST_FIRST);
      sortedActual.sort(RowNumAndSimilarity.NEAREST_FIRST);
      assertEquals(
          sortedExpected.stream().map(RowNumAndSimilarity::getRowNum).sorted().toList(),
          sortedActual.stream().map(RowNumAndSimilarity::getRowNum).sorted().toList(),
          message + " expected=" + sortedExpected + " actual=" + sortedActual);
      for (int i = 0; i < sortedExpected.size(); ++i) {
        assertEquals(
            sortedExpected.get(i).getSimilarity(), sortedActual.get(i).getSimilarity(), DELTA);
      }
    }

    /** Guards the randomized cases against silently degenerating into empty result sets. */
    @Test
    void theRandomizedCorpusProducesMatchesToCompare() {
      Random random = new Random(7_311L);
      LongObjectHashMap<LongTermsAndValues> rows = randomRows(random, 60);
      TermIndex index = new TermIndex(config("ngld"), rows, longObjectMap());
      int numMatches = 0;
      for (LongObjectCursor<LongTermsAndValues> row : rows) {
        numMatches += index.getSimilarRowNums(0.5f, row.value, null).size();
      }

      assertTrue(numMatches > 100, "expected the corpus to produce matches, got " + numMatches);
    }
  }
}
