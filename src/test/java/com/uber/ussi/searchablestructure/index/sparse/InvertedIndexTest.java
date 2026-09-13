package com.uber.ussi.searchablestructure.index.sparse;

import static com.uber.ussi.TestLongObjectMaps.longObjectMap;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carrotsearch.hppc.LongDoubleHashMap;
import com.carrotsearch.hppc.LongFloatHashMap;
import com.carrotsearch.hppc.LongObjectHashMap;
import com.carrotsearch.hppc.cursors.LongObjectCursor;
import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.entity.meta.LongMeta;
import com.uber.ussi.entity.meta.MetaFilter;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValuesTestFactory;
import com.uber.ussi.error.IndexCreationError;
import com.uber.ussi.searchablestructure.RowNumAndSimilarity;
import com.uber.ussi.searchablestructure.index.Index;
import com.uber.ussi.searchablestructure.index.generic.GenericIndex;
import com.uber.ussi.searchablestructure.metadata.MetadataFilteringStrategy;
import com.uber.ussi.searchablestructure.sparse.SparseKeyAndPrefixFilteringData;
import com.uber.ussi.utils.Constants;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class InvertedIndexTest {
  private static final float DELTA = 1e-6f;
  private static final String CANDIDATES_ONLY =
      NamespaceConfig.PopularTermDiscardScope.CANDIDATES_ONLY.getParamValue();

  @Test
  void constructorBuildsForwardAndUniValueSortedInvertedIndexes() {
    LongObjectHashMap<LongTermsAndValues> rows = longObjectMap();
    rows.put(12, jaccard(new long[] {1, 2, 3}, 1, 1, 1));
    rows.put(10, jaccard(new long[] {1}, 1));
    rows.put(9, jaccard(new long[] {1}, -1));
    rows.put(11, jaccard(new long[] {1, 2}, 1, 1));

    InvertedIndex index = new InvertedIndex(config("jaccard"), rows, longObjectMap());

    assertEquals(4, index.size());
    assertEquals(3, index.getNumIndexedSparseKeysForTests());
    assertArrayEquals(new long[] {9, 10, 11, 12}, index.getRowNumsForSparseKeyForTests(1));
    assertArrayEquals(new long[] {11, 12}, index.getRowNumsForSparseKeyForTests(2));
    assertTrue(index.getAll().containsKey(12));
    assertTrue(index.supportsInFiltering());
  }

  @Test
  void highFrequencyTermsAreRemovedFromVerificationRowsButNotConsolidationRows() {
    LongObjectHashMap<LongTermsAndValues> rows = longObjectMap();
    rows.put(1, jaccard(new long[] {1, 2}, 1, 1));
    rows.put(2, jaccard(new long[] {1, 3}, 1, 1));
    rows.put(3, jaccard(new long[] {1, 4}, 1, 1));
    rows.put(4, jaccard(new long[] {5}, 1));
    InvertedIndex index =
        new InvertedIndex(
            config("jaccard", Map.of(Constants.MAX_FRACTION_IDS_PER_SPARSE_KEY, "0.5")),
            rows,
            longObjectMap());

    assertArrayEquals(new long[] {1}, index.getDiscardedTermsForTests());
    assertArrayEquals(new long[0], index.getRowNumsForSparseKeyForTests(1));
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
            Constants.MAX_FRACTION_IDS_PER_SPARSE_KEY, "0.5",
            Constants.POPULAR_TERM_DISCARD_SCOPE, CANDIDATES_ONLY);
    InvertedIndex index = new InvertedIndex(config("jaccard", params), rows, longObjectMap());

    // Candidate generation is unchanged: the discarded term still keys no list.
    assertArrayEquals(new long[] {1}, index.getDiscardedTermsForTests());
    assertArrayEquals(new long[0], index.getRowNumsForSparseKeyForTests(1));
    // Scoring is what changes: the rows keep the discarded term.
    assertArrayEquals(new long[] {1, 2}, index.getVerificationRow(1).getTerms());

    /*
     * Row 1 is reached through term 2 and then scored as the caller supplied it, so it is two
     * terms out of three rather than the one out of two that the discard would have made it.
     */
    List<RowNumAndSimilarity> results =
        index.getSimilarRowNums(0.4f, jaccard(new long[] {1, 2, 6}, 1, 1, 1), MetaFilter.empty());
    assertEquals(List.of(1L), rowNumsNearestFirst(results));
    assertEquals(2.0f / 3.0f, results.get(0).getSimilarity(), DELTA);
  }

  @Test
  void candidatesAndVerificationScoresTheSameQueryWithoutTheHighFrequencyTerms() {
    LongObjectHashMap<LongTermsAndValues> rows = popularTermRows();
    InvertedIndex index =
        new InvertedIndex(
            config("jaccard", Map.of(Constants.MAX_FRACTION_IDS_PER_SPARSE_KEY, "0.5")),
            rows,
            longObjectMap());

    // The same row and query as above, scored without term 1 on either side: one term out of two.
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
            Constants.MAX_FRACTION_IDS_PER_SPARSE_KEY, "0.5",
            Constants.POPULAR_TERM_DISCARD_SCOPE, CANDIDATES_ONLY);
    InvertedIndex index = new InvertedIndex(config("jaccard", params), rows, longObjectMap());

    /*
     * Rows 1, 2 and 3 all share term 1 with this query, so scored as supplied each is one term out
     * of two. None of them is reachable, because term 1 is the only key the query has and its list
     * is the one the discard emptied. This is the recall the scope trades away.
     */
    assertTrue(
        index
            .getSimilarRowNums(0.4f, jaccard(new long[] {1}, 1), MetaFilter.empty())
            .isEmpty());
  }

  @Test
  void candidatesOnlyStillPrunesOnTheSimilarityThatExcludesTheDiscardedTerms() {
    LongObjectHashMap<LongTermsAndValues> rows = popularTermRows();
    Map<String, String> params =
        Map.of(
            Constants.MAX_FRACTION_IDS_PER_SPARSE_KEY, "0.5",
            Constants.POPULAR_TERM_DISCARD_SCOPE, CANDIDATES_ONLY);
    InvertedIndex index = new InvertedIndex(config("jaccard", params), rows, longObjectMap());

    /*
     * Scored as supplied, row 1 is two terms out of three against this query, which clears a 0.6
     * threshold. It is still pruned, because length filtering can only bound the similarity that
     * excludes term 1, and that bound is one term out of two. A threshold above what the surviving
     * terms alone can reach therefore rejects rows the verification would have accepted, which is
     * the same recall trade seen from the threshold's side rather than the query's.
     */
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
            Constants.MAX_FRACTION_IDS_PER_SPARSE_KEY, "0.5",
            Constants.POPULAR_TERM_DISCARD_SCOPE, CANDIDATES_ONLY,
            Constants.SPARSE_CANDIDATE_GENERATOR,
                NamespaceConfig.SparseCandidateGenerator.SPARS_MERGE.getParamValue());
    InvertedIndex index =
        new InvertedIndex(config("jaccard", params, "inverted"), rows, longObjectMap());

    /*
     * A conjunction accumulated from the inverted lists can only report the similarity that
     * excludes the discarded terms, so under this scope the merge has to verify each candidate
     * through the comparator instead. It therefore reports the same score the filtered scan does.
     */
    List<RowNumAndSimilarity> results =
        index.getSimilarRowNums(0.4f, jaccard(new long[] {1, 2, 6}, 1, 1, 1), MetaFilter.empty());
    assertEquals(List.of(1L), rowNumsNearestFirst(results));
    assertEquals(2.0f / 3.0f, results.get(0).getSimilarity(), DELTA);
  }

  @Test
  void highFrequencyFilteringUsesTheObservedFractionOfRows() {
    /*
     * With 4 rows and a 0.5 max fraction, a term may occur in at most floor(4 * 0.5) = 2 rows. Term 1
     * occurs in 3 rows and is discarded; term 2 occurs in exactly 2 rows and is kept.
     */
    LongObjectHashMap<LongTermsAndValues> rows = longObjectMap();
    rows.put(1, jaccard(new long[] {1, 2}, 1, 1));
    rows.put(2, jaccard(new long[] {1, 3}, 1, 1));
    rows.put(3, jaccard(new long[] {1, 4}, 1, 1));
    rows.put(4, jaccard(new long[] {2, 5}, 1, 1));

    InvertedIndex index =
        new InvertedIndex(
            config("jaccard", Map.of(Constants.MAX_FRACTION_IDS_PER_SPARSE_KEY, "0.5")),
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
    InvertedIndex index = new InvertedIndex(config("jaccard"), rows, longObjectMap());

    List<RowNumAndSimilarity> result =
        index.getNearestNeighborRowNums(4, jaccard(new long[] {1, 2}, 1, 1), MetaFilter.empty());

    /*
     * Row 3 shares no term with the query, so it is omitted even though k exceeds the number of rows
     * sharing a term.
     */
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
    InvertedIndex index = new InvertedIndex(config("jaccard"), rows, longObjectMap());

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
    InvertedIndex index = new InvertedIndex(config("jaccard"), rows, longObjectMap());
    long[] invertedList = index.getRowNumsForSparseKeyForTests(1);

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
    InvertedIndex index = new InvertedIndex(config("jaccard"), rows, longObjectMap());
    long[] invertedList = index.getRowNumsForSparseKeyForTests(1);

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
  void zeroUniValueQueriesSearchTheInvertedIndex() {
    LongObjectHashMap<LongTermsAndValues> rows = longObjectMap();
    rows.put(1, jaccard(new long[] {1}, 0));
    rows.put(2, jaccard(new long[] {2}, 0));
    rows.put(3, jaccard(new long[] {2}, 1));
    InvertedIndex index = new InvertedIndex(config("jaccard"), rows, longObjectMap());

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
  void l2QueriesUseTheInvertedIndexAndRequireASharedTerm() {
    LongObjectHashMap<LongTermsAndValues> rows = longObjectMap();
    rows.put(1, l2(new long[] {1}, 1));
    rows.put(2, l2(new long[] {2}, 1));
    InvertedIndex index = new InvertedIndex(config("l2"), rows, longObjectMap());

    List<RowNumAndSimilarity> result =
        index.getNearestNeighborRowNums(2, l2(new long[] {1}, 1), MetaFilter.empty());

    /*
     * Row 2 shares no term with the query and is omitted, per the documented sparse-index
     * assumption, even though its L2 similarity to the query is positive.
     */
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
    InvertedIndex index =
        new InvertedIndex(
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
    InvertedIndex preFilteringIndex =
        new InvertedIndex(
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

    InvertedIndex postFilteringIndex =
        new InvertedIndex(
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
        () -> new InvertedIndex(config("jaccard"), emptyTerms, longObjectMap()));
    assertThrows(
        IndexCreationError.class,
        () -> new InvertedIndex(config("jaccard"), unsortedTerms, longObjectMap()));
    assertThrows(
        IndexCreationError.class,
        () -> new InvertedIndex(config("jaccard"), wrongUniValue, longObjectMap()));

    InvertedIndex index =
        new InvertedIndex(
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
            new InvertedIndex(
                config(
                    "jaccard", Map.of(Constants.MAX_FRACTION_IDS_PER_SPARSE_KEY, "not-a-number")),
                longObjectMap(),
                longObjectMap()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new InvertedIndex(
                config("jaccard", Map.of(Constants.MAX_FRACTION_IDS_PER_SPARSE_KEY, "0")),
                longObjectMap(),
                longObjectMap()));

    InvertedIndex index =
        new InvertedIndex(
            config("jaccard"), longObjectMap(1, jaccard(new long[] {1}, 1)), longObjectMap());
    long[] invertedList = index.getRowNumsForSparseKeyForTests(1);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            index.getFirstMatchingUniValueForTests(
                invertedList, Constants.UNSET_UNI_VALUE, 0.5, 0, 1));
    assertThrows(
        IndexOutOfBoundsException.class,
        () -> index.getLastMatchingUniValueForTests(invertedList, 1.0, 0.5, -1, 1));
  }

  @Test
  void uniValueBoundsHandleQueryAboveTheEntireInvertedListRange() {
    LongObjectHashMap<LongTermsAndValues> rows = longObjectMap();
    rows.put(1, jaccard(new long[] {1}, 1));
    rows.put(2, jaccard(new long[] {1, 2}, 1, 1));
    InvertedIndex index = new InvertedIndex(config("jaccard"), rows, longObjectMap());
    long[] invertedList = index.getRowNumsForSparseKeyForTests(1);

    assertEquals(
        invertedList.length,
        index.getFirstMatchingUniValueForTests(invertedList, 100.0, 0.9, 0, 2));
    assertEquals(
        invertedList.length, index.getLastMatchingUniValueForTests(invertedList, 100.0, 0.9, 0, 2));
  }

  @Test
  void emptySparseIndexReturnsNoResults() {
    InvertedIndex index = new InvertedIndex(config("jaccard"), longObjectMap(), longObjectMap());

    assertTrue(
        index.getNearestNeighborRowNums(1, jaccard(new long[] {1}, 1), MetaFilter.empty()).isEmpty());
  }

  @Test
  void invertedSearchSkipsAnEmptyVerificationRow() throws ReflectiveOperationException {
    InvertedIndex index =
        new InvertedIndex(
            config("jaccard"), longObjectMap(1, jaccard(new long[] {1}, 1)), longObjectMap());
    verificationRows(index)
        .put(1, LongTermsAndValuesTestFactory.create(new long[0], new float[0], 0.0));

    assertTrue(
        index.getNearestNeighborRowNums(1, jaccard(new long[] {1}, 1), MetaFilter.empty()).isEmpty());
  }

  @Test
  void invalidSparseKeyContributionIsRejectedDuringSearch() {
    InvalidContributionSparseIndex index =
        new InvalidContributionSparseIndex(
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
        IndexCreationError.class,
        () -> new InvertedIndex(config("jaccard"), nullRow, longObjectMap()));
    assertThrows(
        IndexCreationError.class,
        () -> new InvertedIndex(config("jaccard"), mismatchedLengths, longObjectMap()));
  }

  @Test
  void uniValueLookupRejectsAStaleInvertedList() throws ReflectiveOperationException {
    InvertedIndex index =
        new InvertedIndex(
            config("jaccard"), longObjectMap(1, jaccard(new long[] {1}, 1)), longObjectMap());
    long[] invertedList = index.getRowNumsForSparseKeyForTests(1);
    rowNumToUniValues(index).remove(1);

    assertThrows(
        IllegalStateException.class,
        () ->
            index.getFirstMatchingUniValueForTests(invertedList, 2.0, 0.5, 0, invertedList.length));
  }

  @Test
  void candidateIteratorDefendsItsThresholdAndInvertedListInvariants()
      throws ReflectiveOperationException {
    InvertedIndex index =
        new InvertedIndex(
            config("jaccard"), longObjectMap(1, jaccard(new long[] {1}, 1)), longObjectMap());

    Object thresholdIterator =
        newCandidateIterator(index, new SparseKeyAndPrefixFilteringData[0], 0.5);
    invokeSetMinSimilarity(thresholdIterator, 0.5);
    InvocationTargetException lowerThresholdError =
        assertThrows(
            InvocationTargetException.class, () -> invokeSetMinSimilarity(thresholdIterator, 0.4));
    assertTrue(lowerThresholdError.getCause() instanceof IllegalArgumentException);

    Object exhaustedPrefixIterator =
        newCandidateIterator(index, new SparseKeyAndPrefixFilteringData[0], 0.0);
    setField(exhaustedPrefixIterator, "sparseKeyIndex", 0);
    setField(exhaustedPrefixIterator, "currentRowStartIndex", 0);
    setField(exhaustedPrefixIterator, "currentRowEndIndex", 1);
    setField(exhaustedPrefixIterator, "currentSparseKeyPrefixSum", 1.0);
    invokeSetMinSimilarity(exhaustedPrefixIterator, 0.9);
    assertEquals(1, getIntField(exhaustedPrefixIterator, "currentRowStartIndex"));

    SparseKeyAndPrefixFilteringData[] inconsistentInvertedList = {
      new SparseKeyAndPrefixFilteringData(1, 2, 1.0)
    };
    Object inconsistentInvertedListIterator =
        newCandidateIterator(index, inconsistentInvertedList, 0.0);
    InvocationTargetException invertedListError =
        assertThrows(
            InvocationTargetException.class, () -> invokeHasNext(inconsistentInvertedListIterator));
    assertTrue(invertedListError.getCause() instanceof IllegalStateException);

    Object emptyIterator = newCandidateIterator(index, new SparseKeyAndPrefixFilteringData[0], 0.0);
    InvocationTargetException exhaustedError =
        assertThrows(InvocationTargetException.class, () -> invokeNext(emptyIterator));
    assertTrue(exhaustedError.getCause() instanceof NoSuchElementException);
  }

  @Test
  void sparseValueObjectsHaveDeterministicOrderingAndAccessors() {
    SparseKeyAndPrefixFilteringData lowFrequency = new SparseKeyAndPrefixFilteringData(2, 1, 1.0);
    SparseKeyAndPrefixFilteringData highContribution =
        new SparseKeyAndPrefixFilteringData(3, 2, 2.0);
    SparseKeyAndPrefixFilteringData lowContribution =
        new SparseKeyAndPrefixFilteringData(1, 2, 1.0);
    SparseKeyAndPrefixFilteringData[] data = {lowContribution, highContribution, lowFrequency};

    Arrays.sort(data);

    assertSame(lowFrequency, data[0]);
    assertSame(highContribution, data[1]);
    assertSame(lowContribution, data[2]);
    assertEquals(2, lowFrequency.getSparseKey());
    assertEquals(1, lowFrequency.getNumRows());
    assertEquals(1.0, lowFrequency.getUniTransformedValue());

    SparseKeyAndUniTransformedValue sparseKey = new SparseKeyAndUniTransformedValue(7, 3.0);
    assertEquals(7, sparseKey.getSparseKey());
    assertEquals(3.0, sparseKey.getUniTransformedValue());

    RowNumAndUniValue[] rows = {new RowNumAndUniValue(2, 1.0), new RowNumAndUniValue(1, 1.0)};
    Arrays.sort(rows);
    assertEquals(1, rows[0].getRowNum());
  }

  @Test
  void prefixFilteringUsesTheUniValueOfTheSparseKeyDomain() {
    LongObjectHashMap<LongTermsAndValues> rows = longObjectMap(1, jaccard(new long[] {7}, 1));
    CapturingSparseIndex index = new CapturingSparseIndex(config("jaccard"), rows, longObjectMap());

    assertEquals(
        List.of(1L),
        rowNumsNearestFirst(
            index.getNearestNeighborRowNums(1, jaccard(new long[] {7}, 1), MetaFilter.empty())));
    assertEquals(5.0, index.getLastSparseKeysUniValue(), DELTA);
  }

  @Test
  void randomizedResultsMatchGenericIndexForEverySparseComparator() {
    for (String comparatorType : List.of("jaccard", "ruzicka", "l2")) {
      Random random = new Random(826_366L + comparatorType.hashCode());
      LongObjectHashMap<LongTermsAndValues> rows = randomRows(random, comparatorType, 80);
      NamespaceConfig config = config(comparatorType);
      InvertedIndex invertedIndex = new InvertedIndex(config, rows, longObjectMap());
      GenericIndex genericIndex = new GenericIndex(config, rows, longObjectMap());

      for (int queryIndex = 0; queryIndex < 60; ++queryIndex) {
        LongTermsAndValues query = randomSparseRecord(random, comparatorType);
        int k = 1 + random.nextInt(10);
        float minSimilarity = new float[] {0.0f, 0.2f, 0.5f, 0.8f}[random.nextInt(4)];

        /*
         * The generic index scores every row, so its results are restricted to the rows sharing a
         * term with the query before comparing them against the sparse index results.
         */
        assertEquivalent(
            comparatorType + " nearest queryIndex=" + queryIndex + " k=" + k + " query=" + query,
            restrictToRowsSharingATerm(
                genericIndex.getNearestNeighborRowNums(rows.size(), query, MetaFilter.empty()),
                rows,
                query,
                k),
            invertedIndex.getNearestNeighborRowNums(k, query, MetaFilter.empty()));
        assertEquivalent(
            comparatorType
                + " threshold queryIndex="
                + queryIndex
                + " minSimilarity="
                + minSimilarity
                + " query="
                + query,
            restrictToRowsSharingATerm(
                genericIndex.getSimilarRowNums(minSimilarity, query, MetaFilter.empty()),
                rows,
                query,
                rows.size()),
            invertedIndex.getSimilarRowNums(minSimilarity, query, MetaFilter.empty()));
      }
    }
  }

  /**
   * The merge generator reaches the same rows as the filtered scan and scores them identically, so
   * any divergence between the two is a bug in one of them.
   */
  @Test
  void randomizedMergeResultsMatchFilteredScanForEverySparseComparator() {
    for (String comparatorType : List.of("jaccard", "ruzicka", "l2")) {
      Random random = new Random(826_366L + comparatorType.hashCode());
      LongObjectHashMap<LongTermsAndValues> rows = randomRows(random, comparatorType, 80);
      InvertedIndex filteredScanIndex =
          new InvertedIndex(config(comparatorType, Map.of(), "inverted"), rows, longObjectMap());
      InvertedIndex mergeIndex =
          new InvertedIndex(mergeConfig(comparatorType), rows, longObjectMap());

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
   * Metadata filtering wraps candidate generation, so the merge has to agree with the filtered scan
   * under every strategy, including the pre-filtering path that bypasses the generator entirely.
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
        InvertedIndex filteredScanIndex =
            new InvertedIndex(config(comparatorType, strategyParams, "inverted"), rows, metadata);
        InvertedIndex mergeIndex =
            new InvertedIndex(
                config(comparatorType, withMergeParam(strategyParams), "inverted"), rows, metadata);
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
   * Sparse-key popularity filtering makes the verification rows differ from the indexed rows, so
   * the metadata pre-filtering path can only agree with the merge path if it scores the
   * verification rows too.
   */
  @Test
  void mergeResultsMatchFilteredScanWhenPopularSparseKeysAreDropped() {
    Random random = new Random(9_713L);
    LongObjectHashMap<LongTermsAndValues> rows = randomRows(random, "jaccard", 40);
    LongObjectHashMap<LongMeta> metadata = longObjectMap();
    for (LongObjectCursor<LongTermsAndValues> row : rows) {
      metadata.put(row.key, longMeta("city", row.key % 2 == 0 ? "sf" : "la"));
    }
    Map<String, String> popularityParams =
        Map.of(
            Constants.MAX_FRACTION_IDS_PER_SPARSE_KEY, "0.2",
            Index.METADATA_FILTERING_STRATEGY, "pre_filtering",
            Index.MAX_PRE_FILTERING_ROWS_RATIO, "0.9");
    InvertedIndex filteredScanIndex =
        new InvertedIndex(config("jaccard", popularityParams, "inverted"), rows, metadata);
    InvertedIndex mergeIndex =
        new InvertedIndex(
            config("jaccard", withMergeParam(popularityParams), "inverted"), rows, metadata);
    assertTrue(mergeIndex.discardsPopularSparseKeys());
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
   * Every caller asks for a key the record it hands over actually has, so this only fires if one
   * stops doing that. It is checked rather than returning whatever value happens to sit at the
   * insertion point, which would be scored as if the record carried a term it does not.
   */
  @Test
  void readingAValueAtAnAbsentSparseKeyIsRejected() {
    LongObjectHashMap<LongTermsAndValues> rows = longObjectMap();
    rows.put(1, jaccard(new long[] {2, 4, 6}, 1, 1, 1));
    InvertedIndex index = new InvertedIndex(config("jaccard"), rows, longObjectMap());
    LongTermsAndValues record = rows.get(1);

    assertEquals(1.0f, index.getValueAtSparseKey(record, 4), DELTA);
    for (long absentKey : new long[] {1, 3, 5, 7}) {
      assertThrows(
          IllegalArgumentException.class,
          () -> index.getValueAtSparseKey(record, absentKey),
          "key " + absentKey);
    }
  }

  /** A query key the index has no list for contributes nothing and no frontier entry either. */
  @Test
  void mergeSkipsTheQueryKeysThatKeyNoInvertedList() {
    LongObjectHashMap<LongTermsAndValues> rows = longObjectMap();
    rows.put(1, jaccard(new long[] {1, 2}, 1, 1));
    rows.put(2, jaccard(new long[] {3, 4}, 1, 1));
    InvertedIndex index = new InvertedIndex(mergeConfig("jaccard"), rows, longObjectMap());

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
        Constants.SPARSE_CANDIDATE_GENERATOR,
        NamespaceConfig.SparseCandidateGenerator.SPARS_MERGE.getParamValue());
    return merged;
  }

  private static NamespaceConfig mergeConfig(String comparatorType) {
    return config(
        comparatorType,
        Map.of(
            Constants.SPARSE_CANDIDATE_GENERATOR,
            NamespaceConfig.SparseCandidateGenerator.SPARS_MERGE.getParamValue()),
        "inverted");
  }

  private static NamespaceConfig config(String comparatorType) {
    return config(comparatorType, Map.of());
  }

  private static NamespaceConfig config(String comparatorType, Map<String, String> indexParams) {
    return config(comparatorType, indexParams, "sparse");
  }

  private static NamespaceConfig config(
      String comparatorType, Map<String, String> indexParams, String indexType) {
    return NamespaceConfig.builder()
        .minTermsAndValuesLength(0)
        .maxTermsAndValuesLength(100)
        .maxCacheSize(100)
        .cacheType("generic")
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
  private static LongObjectHashMap<LongTermsAndValues> verificationRows(BaseSparseIndex index)
      throws ReflectiveOperationException {
    Field field = BaseSparseIndex.class.getDeclaredField("verificationRowNumToTermsAndValuesMap");
    field.setAccessible(true);
    return (LongObjectHashMap<LongTermsAndValues>) field.get(index);
  }

  @SuppressWarnings("unchecked")
  private static LongDoubleHashMap rowNumToUniValues(BaseSparseIndex index)
      throws ReflectiveOperationException {
    Field field = BaseSparseIndex.class.getDeclaredField("rowNumToUniValue");
    field.setAccessible(true);
    return (LongDoubleHashMap) field.get(index);
  }

  private static com.uber.ussi.comparator.Comparator comparator(BaseSparseIndex index)
      throws ReflectiveOperationException {
    Field field =
        com.uber.ussi.searchablestructure.index.Index.class.getDeclaredField("comparator");
    field.setAccessible(true);
    return (com.uber.ussi.comparator.Comparator) field.get(index);
  }

  private static Object newCandidateIterator(
      BaseSparseIndex index, SparseKeyAndPrefixFilteringData[] sparseKeyData, double minSimilarity)
      throws ReflectiveOperationException {
    Constructor<?> constructor =
        SparseFilteredSearch.CandidateIterator.class.getDeclaredConstructor(
            com.uber.ussi.comparator.Comparator.class,
            SparseFilteredSearch.Context.class,
            SparseKeyAndPrefixFilteringData[].class,
            double.class,
            double.class);
    constructor.setAccessible(true);
    return constructor.newInstance(
        comparator(index), index.getSearchContext(), sparseKeyData, 1.0, minSimilarity);
  }

  private static void invokeSetMinSimilarity(Object iterator, double minSimilarity)
      throws ReflectiveOperationException {
    Method method = iterator.getClass().getDeclaredMethod("setMinSimilarity", double.class);
    method.setAccessible(true);
    method.invoke(iterator, minSimilarity);
  }

  private static boolean invokeHasNext(Object iterator) throws ReflectiveOperationException {
    Method method = iterator.getClass().getDeclaredMethod("hasNext");
    method.setAccessible(true);
    return (boolean) method.invoke(iterator);
  }

  private static long invokeNext(Object iterator) throws ReflectiveOperationException {
    Method method = iterator.getClass().getDeclaredMethod("next");
    method.setAccessible(true);
    return (long) method.invoke(iterator);
  }

  private static void setField(Object target, String fieldName, Object value)
      throws ReflectiveOperationException {
    Field field = target.getClass().getDeclaredField(fieldName);
    field.setAccessible(true);
    field.set(target, value);
  }

  private static int getIntField(Object target, String fieldName)
      throws ReflectiveOperationException {
    Field field = target.getClass().getDeclaredField(fieldName);
    field.setAccessible(true);
    return field.getInt(target);
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

  private static final class CapturingSparseIndex extends BaseSparseIndex {
    private double lastSparseKeysUniValue;

    private CapturingSparseIndex(
        NamespaceConfig namespaceConfig,
        LongObjectHashMap<LongTermsAndValues> rowNumToTermsAndValuesMap,
        LongObjectHashMap<LongMeta> rowNumToMetaMap) {
      super(
          namespaceConfig, rowNumToTermsAndValuesMap, rowNumToMetaMap, SparseKeyType.EXACT_TERM);
    }

    @Override
    protected void validateRecordType(LongTermsAndValues termsAndValues, String source) {
      validateSparseRecordType(termsAndValues, source);
    }

    @Override
    protected double getMinPrefixSum(double sparseKeysUniValue, double minSimilarity) {
      lastSparseKeysUniValue = sparseKeysUniValue;
      return Double.POSITIVE_INFINITY;
    }

    @Override
    protected SparseKeyAndUniTransformedValue[] getSparseKeysAndUniTransformedValues(
        LongTermsAndValues termsAndValues) {
      return new SparseKeyAndUniTransformedValue[] {
        new SparseKeyAndUniTransformedValue(termsAndValues.getTerm(0), 5.0)
      };
    }

    @Override
    protected long[] getSparseKeys(LongTermsAndValues termsAndValues) {
      return termsAndValues.getTerms();
    }

    @Override
    protected float getValueAtSparseKey(LongTermsAndValues termsAndValues, long sparseKey) {
      return termsAndValues.getValue(0);
    }

    private double getLastSparseKeysUniValue() {
      return lastSparseKeysUniValue;
    }
  }

  private static final class InvalidContributionSparseIndex extends BaseSparseIndex {
    private InvalidContributionSparseIndex(
        NamespaceConfig namespaceConfig,
        LongObjectHashMap<LongTermsAndValues> rowNumToTermsAndValuesMap,
        LongObjectHashMap<LongMeta> rowNumToMetaMap) {
      super(
          namespaceConfig, rowNumToTermsAndValuesMap, rowNumToMetaMap, SparseKeyType.EXACT_TERM);
    }

    @Override
    protected void validateRecordType(LongTermsAndValues termsAndValues, String source) {
      validateSparseRecordType(termsAndValues, source);
    }

    @Override
    protected double getMinPrefixSum(double sparseKeysUniValue, double minSimilarity) {
      return Double.POSITIVE_INFINITY;
    }

    @Override
    protected SparseKeyAndUniTransformedValue[] getSparseKeysAndUniTransformedValues(
        LongTermsAndValues termsAndValues) {
      return new SparseKeyAndUniTransformedValue[] {
        new SparseKeyAndUniTransformedValue(termsAndValues.getTerm(0), Double.NaN)
      };
    }

    @Override
    protected long[] getSparseKeys(LongTermsAndValues termsAndValues) {
      return termsAndValues.getTerms();
    }

    @Override
    protected float getValueAtSparseKey(LongTermsAndValues termsAndValues, long sparseKey) {
      return termsAndValues.getValue(0);
    }
  }
}
