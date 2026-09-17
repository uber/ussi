package com.uber.ussi.searchablestructure.cache;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carrotsearch.hppc.LongArrayList;
import com.carrotsearch.hppc.LongFloatHashMap;
import com.carrotsearch.hppc.LongObjectHashMap;
import com.uber.ussi.comparator.Comparator;
import com.uber.ussi.comparatornormalizer.IdentityComparatorNormalizer;
import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.entity.meta.MetaFilter;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;
import com.uber.ussi.entity.termsandvalues.RecordType;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValuesTestFactory;
import com.uber.ussi.searchablestructure.RowNumAndSimilarity;
import com.uber.ussi.utils.ConfigKeys;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class InvertedTermCacheTest {
  private static final float DELTA = 1e-6f;
  private static final String CANDIDATES_ONLY =
      NamespaceConfig.PopularTermDiscardScope.CANDIDATES_ONLY.getParamValue();
  private static final String CANDIDATES_AND_VERIFICATION =
      NamespaceConfig.PopularTermDiscardScope.CANDIDATES_AND_VERIFICATION.getParamValue();

  @Test
  void insertedRowsAreSearchableAndRowsSharingNoTermAreOmitted() {
    InvertedTermCache cache = new InvertedTermCache(config("jaccard"));
    long shared12 = cache.insert(jaccard(new long[] {1, 2}, 1, 1), Map.of());
    long shared13 = cache.insert(jaccard(new long[] {1, 3}, 1, 1), Map.of());
    long disjoint = cache.insert(jaccard(new long[] {4}, 1), Map.of());
    long shared123 = cache.insert(jaccard(new long[] {1, 2, 3}, 1, 1, 1), Map.of());

    List<RowNumAndSimilarity> result =
        cache.getNearestNeighborRowNums(4, jaccard(new long[] {1, 2}, 1, 1), MetaFilter.empty());

    assertEquals(List.of(shared12, shared123, shared13), rowNumsNearestFirst(result));
    LongFloatHashMap similarities = rowNumToSimilarity(result);
    assertEquals(1.0f, similarities.get(shared12), DELTA);
    assertEquals(2.0f / 3.0f, similarities.get(shared123), DELTA);
    assertEquals(1.0f / 3.0f, similarities.get(shared13), DELTA);
    assertFalse(similarities.containsKey(disjoint));
    assertEquals(
        List.of(shared12, shared123),
        rowNumsNearestFirst(
            cache.getSimilarRowNums(0.6f, jaccard(new long[] {1, 2}, 1, 1), MetaFilter.empty())));
  }

  @Test
  void updateAndDeleteKeepInvertedListsConsistentInInsertionOrder() {
    InvertedTermCache cache = new InvertedTermCache(config("jaccard"));
    long updated = cache.insert(jaccard(new long[] {1, 2}, 1, 1), Map.of());
    long deleted = cache.insert(jaccard(new long[] {2, 3}, 1, 1), Map.of());
    assertArrayEquals(new long[] {updated, deleted}, cache.getInvertedListForTests(2));

    assertTrue(cache.update(updated, jaccard(new long[] {3, 4}, 1, 1), Map.of()));

    assertArrayEquals(new long[0], cache.getInvertedListForTests(1));
    assertArrayEquals(new long[] {deleted}, cache.getInvertedListForTests(2));
    assertArrayEquals(new long[] {deleted, updated}, cache.getInvertedListForTests(3));
    assertArrayEquals(new long[] {updated}, cache.getInvertedListForTests(4));

    assertTrue(cache.delete(deleted));

    assertArrayEquals(new long[0], cache.getInvertedListForTests(2));
    assertArrayEquals(new long[] {updated}, cache.getInvertedListForTests(3));
    assertTrue(
        cache.getNearestNeighborRowNums(1, jaccard(new long[] {2}, 1), MetaFilter.empty()).isEmpty());
    List<RowNumAndSimilarity> result =
        cache.getNearestNeighborRowNums(2, jaccard(new long[] {3, 4}, 1, 1), MetaFilter.empty());
    assertEquals(List.of(updated), rowNumsNearestFirst(result));
    assertEquals(1.0f, result.get(0).getSimilarity(), DELTA);
  }

  @Test
  void popularTermsAreFilteredOnTheFlyAndReadmittedAsTheCacheChanges() {
    // A 0.5 confidence makes the bound the observed popularity: term 1 is filtered above half.
    InvertedTermCache cache =
        new InvertedTermCache(
            config(
                "jaccard",
                Map.of(
                    ConfigKeys.MAX_FRACTION_IDS_PER_TERM,
                    "0.5",
                    ConfigKeys.MAX_FRACTION_IDS_PER_TERM_CONFIDENCE,
                    "0.5")));
    long first = cache.insert(jaccard(new long[] {1, 101}, 1, 1), Map.of());
    long second = cache.insert(jaccard(new long[] {1, 102}, 1, 1), Map.of());

    assertArrayEquals(new long[] {1}, cache.getDiscardedTermsForTests());
    assertTrue(
        cache.getNearestNeighborRowNums(2, jaccard(new long[] {1}, 1), MetaFilter.empty()).isEmpty());
    List<RowNumAndSimilarity> filteredQueryResult =
        cache.getNearestNeighborRowNums(2, jaccard(new long[] {1, 101}, 1, 1), MetaFilter.empty());
    assertEquals(List.of(first), rowNumsNearestFirst(filteredQueryResult));
    assertEquals(1.0f, filteredQueryResult.get(0).getSimilarity(), DELTA);

    long third = cache.insert(jaccard(new long[] {103, 104}, 1, 1), Map.of());
    long fourth = cache.insert(jaccard(new long[] {105, 106}, 1, 1), Map.of());

    assertArrayEquals(new long[0], cache.getDiscardedTermsForTests());
    List<RowNumAndSimilarity> readmittedResult =
        cache.getNearestNeighborRowNums(4, jaccard(new long[] {1}, 1), MetaFilter.empty());
    assertEquals(List.of(first, second), rowNumsNearestFirst(readmittedResult));
    assertEquals(0.5f, readmittedResult.get(0).getSimilarity(), DELTA);

    assertTrue(cache.delete(third));
    // Term 1 is unaffected by this deletion, but its popularity increases from 2/4 to 2/3.
    assertArrayEquals(new long[] {1}, cache.getDiscardedTermsForTests());
    assertTrue(cache.delete(fourth));

    assertArrayEquals(new long[] {1}, cache.getDiscardedTermsForTests());
    assertTrue(
        cache.getNearestNeighborRowNums(2, jaccard(new long[] {1}, 1), MetaFilter.empty()).isEmpty());
  }

  @Test
  void candidatesOnlyScoresTheCachedRowsWithTheirPopularTerms() {
    InvertedTermCache candidatesOnlyCache = popularTermCache(CANDIDATES_ONLY);
    InvertedTermCache defaultScopeCache = popularTermCache(CANDIDATES_AND_VERIFICATION);
    for (InvertedTermCache cache : List.of(candidatesOnlyCache, defaultScopeCache)) {
      cache.insert(jaccard(new long[] {1, 101}, 1, 1), Map.of());
      cache.insert(jaccard(new long[] {1, 102}, 1, 1), Map.of());
      assertArrayEquals(new long[] {1}, cache.getDiscardedTermsForTests());
    }
    LongTermsAndValues query = jaccard(new long[] {1, 101, 999}, 1, 1, 1);

    List<RowNumAndSimilarity> candidatesOnlyResult =
        candidatesOnlyCache.getNearestNeighborRowNums(2, query, MetaFilter.empty());
    List<RowNumAndSimilarity> defaultScopeResult =
        defaultScopeCache.getNearestNeighborRowNums(2, query, MetaFilter.empty());

    // Both scopes reach the same row but disagree on its worth: two terms of three as supplied,
    // one of two without term 1.
    assertEquals(1, candidatesOnlyResult.size(), candidatesOnlyResult.toString());
    assertEquals(2.0f / 3.0f, candidatesOnlyResult.get(0).getSimilarity(), DELTA);
    assertEquals(1, defaultScopeResult.size(), defaultScopeResult.toString());
    assertEquals(0.5f, defaultScopeResult.get(0).getSimilarity(), DELTA);
    // Neither scope reaches a row through a popular term, so such a query matches nothing.
    assertTrue(
        candidatesOnlyCache
            .getNearestNeighborRowNums(2, jaccard(new long[] {1}, 1), MetaFilter.empty())
            .isEmpty());
  }

  @Test
  void deletionReevaluatesIncrementallyUntilCacheShrinkTriggersAFullReevaluation() {
    InvertedTermCache cache =
        new InvertedTermCache(
            config(
                "jaccard",
                Map.of(
                    ConfigKeys.MAX_FRACTION_IDS_PER_TERM,
                    "0.54",
                    ConfigKeys.MAX_FRACTION_IDS_PER_TERM_CONFIDENCE,
                    "0.5")));
    for (int row = 0; row < 10; ++row) {
      cache.insert(jaccard(new long[] {1, 2}, 1, 1), Map.of());
    }
    long termOneOnlyRow1 = cache.insert(jaccard(new long[] {1}, 1), Map.of());
    List<Long> fillerRows = new ArrayList<>();
    for (int row = 0; row < 9; ++row) {
      fillerRows.add(cache.insert(jaccard(new long[] {100 + row}, 1), Map.of()));
    }

    assertArrayEquals(new long[] {1}, cache.getDiscardedTermsForTests());

    assertTrue(cache.delete(termOneOnlyRow1));
    // A 5% decrease uses partial reevaluation and readmits term 1 at 10/19 popularity.
    assertArrayEquals(new long[0], cache.getDiscardedTermsForTests());

    assertTrue(cache.delete(fillerRows.get(0)));
    // At exactly 10% shrink, the rebuild finds both untouched terms at 10/18 popularity.
    assertArrayEquals(new long[] {1, 2}, cache.getDiscardedTermsForTests());
  }

  @Test
  void exactlyTenPercentCacheShrinkTriggersAFullPopularityReevaluation() {
    InvertedTermCache cache =
        new InvertedTermCache(
            config(
                "jaccard",
                Map.of(
                    ConfigKeys.MAX_FRACTION_IDS_PER_TERM,
                    "0.5",
                    ConfigKeys.MAX_FRACTION_IDS_PER_TERM_CONFIDENCE,
                    "0.5")));
    for (int row = 0; row < 46; ++row) {
      cache.insert(jaccard(new long[] {1}, 1), Map.of());
    }
    List<Long> unrelatedRows = new ArrayList<>();
    for (int row = 0; row < 54; ++row) {
      unrelatedRows.add(cache.insert(jaccard(new long[] {100 + row}, 1), Map.of()));
    }

    assertArrayEquals(new long[0], cache.getDiscardedTermsForTests());
    for (int row = 0; row < 9; ++row) {
      assertTrue(cache.delete(unrelatedRows.get(row)));
    }
    // Term 1 is stale at 46/91 because less than 10% of the baseline has been deleted.
    assertArrayEquals(new long[0], cache.getDiscardedTermsForTests());

    assertTrue(cache.delete(unrelatedRows.get(9)));

    assertArrayEquals(new long[] {1}, cache.getDiscardedTermsForTests());
  }

  @Test
  void configuredCacheShrinkFractionControlsFullPopularityReevaluation() {
    InvertedTermCache cache =
        new InvertedTermCache(
            config(
                "jaccard",
                Map.of(
                    ConfigKeys.MAX_FRACTION_IDS_PER_TERM,
                    "0.5",
                    ConfigKeys.MAX_FRACTION_IDS_PER_TERM_CONFIDENCE,
                    "0.5",
                    ConfigKeys.FULL_REEVALUATION_CACHE_SIZE_DECREASE_FRACTION,
                    "0.2")));
    for (int row = 0; row < 5; ++row) {
      cache.insert(jaccard(new long[] {1}, 1), Map.of());
    }
    List<Long> unrelatedRows = new ArrayList<>();
    for (int row = 0; row < 5; ++row) {
      unrelatedRows.add(cache.insert(jaccard(new long[] {100 + row}, 1), Map.of()));
    }

    assertArrayEquals(new long[0], cache.getDiscardedTermsForTests());
    assertTrue(cache.delete(unrelatedRows.get(0)));
    // A 10% decrease does not trigger the configured 20% full reevaluation.
    assertArrayEquals(new long[0], cache.getDiscardedTermsForTests());

    assertTrue(cache.delete(unrelatedRows.get(1)));

    assertArrayEquals(new long[] {1}, cache.getDiscardedTermsForTests());
  }

  @Test
  void deletingTheLastRowClearsPopularityFilteringState() {
    InvertedTermCache cache =
        new InvertedTermCache(
            config(
                "jaccard",
                Map.of(
                    ConfigKeys.MAX_FRACTION_IDS_PER_TERM,
                    "0.5",
                    ConfigKeys.MAX_FRACTION_IDS_PER_TERM_CONFIDENCE,
                    "0.5")));
    long rowNum = cache.insert(jaccard(new long[] {1}, 1), Map.of());
    assertArrayEquals(new long[] {1}, cache.getDiscardedTermsForTests());

    assertTrue(cache.delete(rowNum));

    assertArrayEquals(new long[0], cache.getDiscardedTermsForTests());
  }

  @Test
  void smallSamplesErrTowardFilteringWithTheDefaultConfidence() {
    // Term 1 is in half the rows, below the 0.7 cap, but at 4 rows the 95% upper bound (0.91)
    // exceeds it; at 40 rows the bound tightens to 0.63 and the term is readmitted.
    InvertedTermCache cache =
        new InvertedTermCache(
            config("jaccard", Map.of(ConfigKeys.MAX_FRACTION_IDS_PER_TERM, "0.7")));
    cache.insert(jaccard(new long[] {1, 101}, 1, 1), Map.of());
    cache.insert(jaccard(new long[] {1, 102}, 1, 1), Map.of());
    cache.insert(jaccard(new long[] {103}, 1), Map.of());
    cache.insert(jaccard(new long[] {104}, 1), Map.of());

    assertArrayEquals(new long[] {1}, cache.getDiscardedTermsForTests());

    for (long extraRow = 0; extraRow < 18; ++extraRow) {
      cache.insert(jaccard(new long[] {1, 200 + extraRow}, 1, 1), Map.of());
      cache.insert(jaccard(new long[] {300 + extraRow}, 1), Map.of());
    }

    assertEquals(40, cache.size());
    assertArrayEquals(new long[0], cache.getDiscardedTermsForTests());
  }

  @Test
  void metadataPreFilteringFallsBackToABruteForceScanOfTheMatchingRows() {
    InvertedTermCache cache = new InvertedTermCache(config("jaccard"));
    for (long filler = 0; filler < 198; ++filler) {
      cache.insert(jaccard(new long[] {1000 + filler}, 1), Map.of("city", "sf"));
    }
    long sharing = cache.insert(jaccard(new long[] {1, 2}, 1, 1), Map.of("city", "la"));
    cache.insert(jaccard(new long[] {5}, 1), Map.of("city", "la"));

    MetaFilter la = new MetaFilter(Map.of("city", List.of("la")));
    List<RowNumAndSimilarity> result =
        cache.getNearestNeighborRowNums(5, jaccard(new long[] {1, 2}, 1, 1), la);

    // The two matching rows are under the 1% brute-force limit of the 200-row cache, and the row
    // sharing no term is still omitted.
    assertTrue(cache.getLastSearchUsedPreFilteringBruteForceForTests());
    assertEquals(List.of(sharing), rowNumsNearestFirst(result));
    assertEquals(1.0f, result.get(0).getSimilarity(), DELTA);

    List<RowNumAndSimilarity> unfilteredResult =
        cache.getNearestNeighborRowNums(5, jaccard(new long[] {1, 2}, 1, 1), MetaFilter.empty());
    assertFalse(cache.getLastSearchUsedPreFilteringBruteForceForTests());
    assertEquals(List.of(sharing), rowNumsNearestFirst(unfilteredResult));
  }

  @Test
  void searchValidatesParametersAndHandlesEmptyCaches() {
    InvertedTermCache cache = new InvertedTermCache(config("jaccard"));
    assertThrows(
        IllegalArgumentException.class,
        () -> cache.getNearestNeighborRowNums(0, jaccard(new long[] {1}, 1), MetaFilter.empty()));
    assertThrows(
        IllegalArgumentException.class,
        () -> cache.getSimilarRowNums(1.1f, jaccard(new long[] {1}, 1), MetaFilter.empty()));
    assertTrue(
        cache.getNearestNeighborRowNums(1, jaccard(new long[] {1}, 1), MetaFilter.empty()).isEmpty());
  }

  @Test
  void invalidCacheParamsAreRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new InvertedTermCache(
                config("jaccard", Map.of(ConfigKeys.MAX_FRACTION_IDS_PER_TERM, "0"))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new InvertedTermCache(
                config(
                    "jaccard", Map.of(ConfigKeys.MAX_FRACTION_IDS_PER_TERM, "not-a-number"))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new InvertedTermCache(
                config(
                    "jaccard",
                    Map.of(ConfigKeys.MAX_FRACTION_IDS_PER_TERM_CONFIDENCE, "0.4"))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new InvertedTermCache(
                config(
                    "jaccard",
                    Map.of(ConfigKeys.MAX_FRACTION_IDS_PER_TERM_CONFIDENCE, "1.1"))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new InvertedTermCache(
                config(
                    "jaccard",
                    Map.of(ConfigKeys.MAX_FRACTION_IDS_PER_TERM_CONFIDENCE, "not-a-number"))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new InvertedTermCache(
                config(
                    "jaccard",
                    Map.of(
                        ConfigKeys.FULL_REEVALUATION_CACHE_SIZE_DECREASE_FRACTION,
                        "not-a-number"))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new InvertedTermCache(
                config(
                    "jaccard",
                    Map.of(ConfigKeys.FULL_REEVALUATION_CACHE_SIZE_DECREASE_FRACTION, "-0.1"))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new InvertedTermCache(
                config(
                    "jaccard",
                    Map.of(ConfigKeys.FULL_REEVALUATION_CACHE_SIZE_DECREASE_FRACTION, "1.1"))));
  }

  @Test
  void deletionToleratesAMissingInvertedList() throws ReflectiveOperationException {
    InvertedTermCache cache = new InvertedTermCache(config("jaccard"));
    long rowNum = cache.insert(jaccard(new long[] {1}, 1), Map.of());
    invertedLists(cache).remove(1);

    assertTrue(cache.delete(rowNum));
  }

  @Test
  void invertedListSearchSkipsRowsThatDoNotMatchMetadata() {
    InvertedTermCache cache = new InvertedTermCache(config("jaccard"));
    long matching = cache.insert(jaccard(new long[] {1}, 1), Map.of("city", "sf"));
    cache.insert(jaccard(new long[] {1}, 1), Map.of("city", "la"));

    List<RowNumAndSimilarity> results =
        cache.getNearestNeighborRowNums(
            2, jaccard(new long[] {1}, 1), new MetaFilter(Map.of("city", List.of("sf"))));

    assertEquals(List.of(matching), rowNumsNearestFirst(results));
  }

  @Test
  void invertedListSearchToleratesAStaleInvertedList() {
    InvertedTermCache cache = new InvertedTermCache(config("jaccard"));
    long staleRow = cache.insert(jaccard(new long[] {1}, 1), Map.of());
    cache.insert(jaccard(new long[] {2}, 1), Map.of());
    cache.rowNumToTermsAndValuesMap.remove(staleRow);

    assertTrue(
        cache.getNearestNeighborRowNums(1, jaccard(new long[] {1}, 1), MetaFilter.empty()).isEmpty());
  }

  @Test
  void popularityCheckDefendsAgainstMissingAndOversizedInvertedLists()
      throws ReflectiveOperationException {
    InvertedTermCache cache = new InvertedTermCache(config("jaccard"));

    assertFalse(invokeShouldDiscardTerm(cache, 1));

    cache.insert(jaccard(new long[] {1}, 1), Map.of());
    invertedLists(cache).get(1).add(99);

    assertTrue(invokeShouldDiscardTerm(cache, 1));
  }

  @Test
  void invertedListSearchStopsCurrentInvertedListAfterMinSimilarityTightening()
      throws ReflectiveOperationException {
    InvertedTermCache cache = new InvertedTermCache(config("jaccard"));
    long rowNum = cache.insert(jaccard(new long[] {1}, 1), Map.of());
    setComparator(cache, new AggressivePrefixComparator());

    List<RowNumAndSimilarity> results =
        cache.getNearestNeighborRowNums(1, jaccard(new long[] {1}, 1), MetaFilter.empty());

    assertEquals(List.of(rowNum), rowNumsNearestFirst(results));
  }

  @Test
  void randomizedResultsMatchScanCacheForEverySparseComparator() {
    for (String comparatorType : List.of("jaccard", "ruzicka", "l2")) {
      Random random = new Random(191_733L + comparatorType.hashCode());
      InvertedTermCache sparseCache = new InvertedTermCache(config(comparatorType));
      ScanCache scanCache = new ScanCache(scanCacheConfig(comparatorType));
      List<Long> liveRowNums = new ArrayList<>();

      for (int insertIndex = 0; insertIndex < 120; ++insertIndex) {
        LongTermsAndValues record = randomSparseRecord(random, comparatorType);
        long rowNum = sparseCache.insert(record, Map.of());
        assertEquals(rowNum, scanCache.insert(record, Map.of()));
        liveRowNums.add(rowNum);
      }
      for (int updateIndex = 0; updateIndex < 40; ++updateIndex) {
        long rowNum = liveRowNums.get(random.nextInt(liveRowNums.size()));
        LongTermsAndValues record = randomSparseRecord(random, comparatorType);
        assertTrue(sparseCache.update(rowNum, record, Map.of()));
        assertTrue(scanCache.update(rowNum, record, Map.of()));
      }
      for (int deleteIndex = 0; deleteIndex < 20; ++deleteIndex) {
        long rowNum = liveRowNums.remove(random.nextInt(liveRowNums.size()));
        assertTrue(sparseCache.delete(rowNum));
        assertTrue(scanCache.delete(rowNum));
      }

      LongObjectHashMap<LongTermsAndValues> rows = scanCache.getAll();
      for (int queryIndex = 0; queryIndex < 40; ++queryIndex) {
        LongTermsAndValues query = randomSparseRecord(random, comparatorType);
        int k = 1 + random.nextInt(10);
        float minSimilarity = new float[] {0.0f, 0.2f, 0.5f, 0.8f}[random.nextInt(4)];

        // The scan cache scores every row, so restrict its results to rows sharing a term.
        assertEquivalent(
            comparatorType + " nearest queryIndex=" + queryIndex + " k=" + k + " query=" + query,
            restrictToRowsSharingATerm(
                scanCache.getNearestNeighborRowNums(rows.size(), query, MetaFilter.empty()),
                rows,
                query,
                k),
            sparseCache.getNearestNeighborRowNums(k, query, MetaFilter.empty()));
        assertEquivalent(
            comparatorType
                + " minimum similarity queryIndex="
                + queryIndex
                + " minSimilarity="
                + minSimilarity
                + " query="
                + query,
            restrictToRowsSharingATerm(
                scanCache.getSimilarRowNums(minSimilarity, query, MetaFilter.empty()),
                rows,
                query,
                rows.size()),
            sparseCache.getSimilarRowNums(minSimilarity, query, MetaFilter.empty()));
      }
    }
  }

  /** An empty cache whose 0.5 confidence discards a term exactly above half the cached rows. */
  private static InvertedTermCache popularTermCache(String discardScope) {
    return new InvertedTermCache(
        config(
            "jaccard",
            Map.of(
                ConfigKeys.MAX_FRACTION_IDS_PER_TERM,
                "0.5",
                ConfigKeys.MAX_FRACTION_IDS_PER_TERM_CONFIDENCE,
                "0.5",
                ConfigKeys.POPULAR_TERM_DISCARD_SCOPE,
                discardScope)));
  }

  private static NamespaceConfig config(String comparatorType) {
    return config(comparatorType, Map.of());
  }

  private static NamespaceConfig config(String comparatorType, Map<String, String> cacheParams) {
    return namespaceConfig(comparatorType, "inverted_term", cacheParams);
  }

  private static NamespaceConfig scanCacheConfig(String comparatorType) {
    return namespaceConfig(comparatorType, "scan", Map.of());
  }

  private static NamespaceConfig namespaceConfig(
      String comparatorType, String cacheType, Map<String, String> cacheParams) {
    return NamespaceConfig.builder()
        .minTermsAndValuesLength(0)
        .maxTermsAndValuesLength(100)
        .maxCacheSize(1000)
        .cacheType(cacheType)
        .cacheParams(cacheParams)
        .indexType("inverted_hybrid")
        .comparatorType(comparatorType)
        .comparatorNormalizerType(comparatorType.equals("l2") ? "reciprocal" : "identity")
        .maxNumSearchableStructures(3)
        .maxNumSimilarities(200)
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
  private static LongObjectHashMap<LongArrayList> invertedLists(InvertedTermCache cache)
      throws ReflectiveOperationException {
    Field field = InvertedTermCache.class.getDeclaredField("termAndRowNumsIndex");
    field.setAccessible(true);
    return (LongObjectHashMap<LongArrayList>) field.get(cache);
  }

  private static boolean invokeShouldDiscardTerm(InvertedTermCache cache, long term)
      throws ReflectiveOperationException {
    Method method = InvertedTermCache.class.getDeclaredMethod("shouldDiscardTerm", long.class);
    method.setAccessible(true);
    return (boolean) method.invoke(cache, term);
  }

  private static void setComparator(InvertedTermCache cache, Comparator comparator)
      throws ReflectiveOperationException {
    Field field = Cache.class.getDeclaredField("comparator");
    field.setAccessible(true);
    field.set(cache, comparator);
  }

  private static final class AggressivePrefixComparator extends Comparator {
    private AggressivePrefixComparator() {
      super(new IdentityComparatorNormalizer());
    }

    @Override
    public Set<RecordType> getSupportedRecordTypes() {
      return Set.of(RecordType.SPARSE);
    }

    @Override
    protected double compareInternal(
        LongTermsAndValues first, LongTermsAndValues second, double minSimilarity) {
      return 0.5;
    }

    @Override
    public double getUniTransformedValue(float value) {
      return Math.abs(value);
    }

    @Override
    protected double getMaxPrefixSumForTermsAndValuesInternal(
        double uniValue, double comparatorValue) {
      return comparatorValue == 0.0 ? 10.0 : -1.0;
    }

    @Override
    public boolean mayPassLengthFiltering(
        double uniValue1, double uniValue2, double minSimilarity) {
      return true;
    }
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
}
