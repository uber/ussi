package com.uber.ussi.searchablestructure.index.inverted;

import static com.uber.ussi.TestLongObjectMaps.longObjectMap;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carrotsearch.hppc.LongDoubleHashMap;
import com.carrotsearch.hppc.LongFloatHashMap;
import com.carrotsearch.hppc.LongObjectHashMap;
import com.carrotsearch.hppc.cursors.LongObjectCursor;
import com.uber.ussi.comparator.Comparator;
import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.entity.meta.LongMeta;
import com.uber.ussi.entity.meta.MetaFilter;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValuesTestFactory;
import com.uber.ussi.error.IndexCreationError;
import com.uber.ussi.searchablestructure.RowNumAndSimilarity;
import com.uber.ussi.searchablestructure.index.Index;
import com.uber.ussi.searchablestructure.index.IndexType;
import com.uber.ussi.searchablestructure.index.scan.ScanIndex;
import com.uber.ussi.searchablestructure.metadata.MetadataFilteringStrategy;
import com.uber.ussi.utils.ConfigKeys;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class TermIndexTest {
  private static final float DELTA = 1e-6f;
  private static final String CANDIDATES_ONLY =
      NamespaceConfig.PopularTermDiscardScope.CANDIDATES_ONLY.getParamValue();

  @Test
  void constructorBuildsForwardAndUniValueSortedInvertedLists() {
    LongObjectHashMap<LongTermsAndValues> rows = longObjectMap();
    rows.put(12, jaccard(new long[] {1, 2, 3}, 1, 1, 1));
    rows.put(10, jaccard(new long[] {1}, 1));
    rows.put(9, jaccard(new long[] {1}, -1));
    rows.put(11, jaccard(new long[] {1, 2}, 1, 1));

    TermIndex index = new TermIndex(config("jaccard"), rows, longObjectMap());

    assertEquals(4, index.size());
    assertEquals(3, index.getNumIndexedKeysForTests());
    assertArrayEquals(new long[] {9, 10, 11, 12}, index.getRowNumsForKeyForTests(1));
    assertArrayEquals(new long[] {11, 12}, index.getRowNumsForKeyForTests(2));
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
    assertArrayEquals(new long[0], index.getRowNumsForKeyForTests(1));
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
    assertArrayEquals(new long[0], index.getRowNumsForKeyForTests(1));
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
  void nearestNeighborsTightenThresholdAndBreakTiesByLowestRowNum() {
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
    long[] invertedList = index.getRowNumsForKeyForTests(1);

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
    long[] invertedList = index.getRowNumsForKeyForTests(1);

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
    long[] invertedList = index.getRowNumsForKeyForTests(1);
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
    long[] invertedList = index.getRowNumsForKeyForTests(1);

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
    long[] invertedList = index.getRowNumsForKeyForTests(1);
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
                + " threshold queryIndex="
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
                + " merge threshold queryIndex="
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
              comparatorType + " " + strategy + " merge threshold queryIndex=" + queryIndex,
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
          "merge threshold queryIndex=" + queryIndex,
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
}
