package com.uber.ussi.searchablestructure.index.inverted;

import static com.uber.ussi.TestLongObjectMaps.longObjectMap;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carrotsearch.hppc.LongObjectHashMap;
import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.entity.meta.LongMeta;
import com.uber.ussi.entity.meta.MetaFilter;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValuesTestFactory;
import com.uber.ussi.searchablestructure.RowNumAndSimilarity;
import com.uber.ussi.utils.ConfigKeys;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class HybridIndexTest {
  private static final float DELTA = 1e-6f;

  @Test
  void routesRowsAtTheExactToSignatureBoundary() {
    LongTermsAndValues exact = jaccard(sequentialTerms(270, 1));
    LongTermsAndValues approximate = jaccard(sequentialTerms(271, 1));

    HybridIndex index =
        new HybridIndex(config(), longObjectMap(1, exact, 2, approximate), longObjectMap());

    assertEquals(1, index.getNumExactRowsForTests());
    assertEquals(1, index.getNumSignatureRowsForTests());
  }

  @Test
  void allowsEitherHybridChildToBeEmpty() {
    HybridIndex exactOnly =
        new HybridIndex(
            config(0, 270), longObjectMap(1, jaccard(sequentialTerms(2, 1))), longObjectMap());
    HybridIndex signatureOnly =
        new HybridIndex(
            config(271, 1000), longObjectMap(2, jaccard(sequentialTerms(271, 1))), longObjectMap());

    assertEquals(1, exactOnly.getNumExactRowsForTests());
    assertEquals(0, exactOnly.getNumSignatureRowsForTests());
    assertEquals(0, signatureOnly.getNumExactRowsForTests());
    assertEquals(1, signatureOnly.getNumSignatureRowsForTests());
  }

  @Test
  void searchesBothChildrenAndMergesNearestAndThresholdResults() {
    LongTermsAndValues exact = jaccard(sequentialTerms(270, 1));
    LongTermsAndValues approximate = jaccard(sequentialTerms(271, 1));
    HybridIndex index =
        new HybridIndex(config(), longObjectMap(1, exact, 2, approximate), longObjectMap());

    List<RowNumAndSimilarity> nearest =
        index.getNearestNeighborRowNums(2, approximate, MetaFilter.empty());
    List<RowNumAndSimilarity> threshold =
        index.getSimilarRowNums(1.0f, approximate, MetaFilter.empty());

    assertEquals(List.of(2L, 1L), rowNumsNearestFirst(nearest));
    assertEquals(1.0f, similarityForRow(nearest, 2), DELTA);
    assertEquals(270.0f / 271.0f, similarityForRow(nearest, 1), DELTA);
    assertEquals(List.of(2L), rowNumsNearestFirst(threshold));
    assertEquals(
        List.of(2L, 1L),
        rowNumsNearestFirst(index.getSimilarRowNums(0.5f, approximate, MetaFilter.empty())));
    assertEquals(
        List.of(2L),
        rowNumsNearestFirst(index.getNearestNeighborRowNums(1, approximate, MetaFilter.empty())));
  }

  @Test
  void jaccardThresholdRoutingSkipsCardinalityRangesThatCannotMatch() {
    LongTermsAndValues smallQuery = jaccard(sequentialTerms(100, 1));
    HybridIndex smallQueryIndex =
        new HybridIndex(
            config(),
            longObjectMap(
                1, smallQuery, 2, jaccard(sequentialTerms(SignatureIndex.NUM_SIGNATURES_PER_ROW + 1, 1))),
            longObjectMap());

    assertEquals(
        List.of(1L),
        rowNumsNearestFirst(
            smallQueryIndex.getSimilarRowNums(0.5f, smallQuery, MetaFilter.empty())));

    LongTermsAndValues largeQuery = jaccard(sequentialTerms(600, 1));
    HybridIndex largeQueryIndex =
        new HybridIndex(
            config(0, 1000),
            longObjectMap(
                1, jaccard(sequentialTerms(SignatureIndex.NUM_SIGNATURES_PER_ROW, 1)), 2, largeQuery),
            longObjectMap());

    assertEquals(
        List.of(2L),
        rowNumsNearestFirst(
            largeQueryIndex.getSimilarRowNums(0.5f, largeQuery, MetaFilter.empty())));
  }

  @Test
  void nearestNeighborRoutingUsesTheOtherChildWhenItCanImproveTheResult() {
    LongTermsAndValues query = jaccard(sequentialTerms(200, 1));
    long[] weakExactTerms = new long[200];
    System.arraycopy(sequentialTerms(100, 1), 0, weakExactTerms, 0, 100);
    System.arraycopy(sequentialTerms(100, 1000), 0, weakExactTerms, 100, 100);
    LongObjectHashMap<LongTermsAndValues> rows =
        longObjectMap(
            1,
            jaccard(weakExactTerms),
            2,
            jaccard(sequentialTerms(SignatureIndex.NUM_SIGNATURES_PER_ROW + 1, 1)));
    rows.put(3, jaccard(new long[] {1}));
    HybridIndex index = new HybridIndex(config(), rows, longObjectMap());

    List<RowNumAndSimilarity> results = index.getNearestNeighborRowNums(1, query, MetaFilter.empty());

    assertEquals(List.of(2L), rowNumsNearestFirst(results));
    assertEquals(
        List.of(2L, 1L),
        rowNumsNearestFirst(index.getNearestNeighborRowNums(2, query, MetaFilter.empty())));
  }

  @Test
  void ruzickaRoutingConservativelySearchesAcrossTheBoundary() {
    LongTermsAndValues query = ruzicka(new long[] {1}, new float[] {100.0f});
    float[] longValues = new float[SignatureIndex.NUM_SIGNATURES_PER_ROW + 1];
    Arrays.fill(longValues, 0.001f);
    longValues[0] = 100.0f;
    LongTermsAndValues longRow = ruzicka(sequentialTerms(longValues.length, 1), longValues);
    HybridIndex index =
        new HybridIndex(
            config("ruzicka", "icws", 0, 1000, Map.of()),
            longObjectMap(1, longRow),
            longObjectMap());

    assertEquals(
        List.of(1L), rowNumsNearestFirst(index.getSimilarRowNums(0.9f, query, MetaFilter.empty())));
  }

  @Test
  void popularityFilteringDisablesUnsafeCardinalityRouting() {
    LongTermsAndValues target = jaccard(sequentialTerms(SignatureIndex.NUM_SIGNATURES_PER_ROW, 1));
    LongTermsAndValues popularExtraTerms = jaccard(sequentialTerms(30, 271));
    LongObjectHashMap<LongTermsAndValues> rows = longObjectMap(1, target, 2, popularExtraTerms);
    rows.put(3, popularExtraTerms);
    HybridIndex index =
        new HybridIndex(
            config(
                "jaccard",
                "minhash",
                0,
                1000,
                Map.of(ConfigKeys.MAX_FRACTION_IDS_PER_TERM, "0.5")),
            rows,
            longObjectMap());
    LongTermsAndValues query = jaccard(sequentialTerms(300, 1));

    assertEquals(
        List.of(1L), rowNumsNearestFirst(index.getSimilarRowNums(1.0f, query, MetaFilter.empty())));
  }

  @Test
  void discardsPopularTermsInTheExactHalfOnlyAndCountsThemOverItsRows() {
    // Term 1 is in three of the four term-keyed rows, which is over half of them, and in none of
    // the signature-keyed rows. Counted over the term-keyed rows it is popular; counted over all
    // six it is not, so what the exact half discards also shows which rows it counted.
    LongObjectHashMap<LongTermsAndValues> rows = longObjectMap();
    rows.put(1, jaccard(new long[] {1, 10}));
    rows.put(2, jaccard(new long[] {1, 11}));
    rows.put(3, jaccard(new long[] {1, 12}));
    rows.put(4, jaccard(new long[] {20, 21}));
    rows.put(5, jaccard(sequentialTerms(271, 1000)));
    rows.put(6, jaccard(sequentialTerms(271, 1000)));

    HybridIndex index =
        new HybridIndex(
            config(
                "jaccard", "minhash", 0, 1000, Map.of(ConfigKeys.MAX_FRACTION_IDS_PER_TERM, "0.5")),
            rows,
            longObjectMap());

    assertEquals(4, index.getNumExactRowsForTests());
    assertEquals(2, index.getNumSignatureRowsForTests());
    assertArrayEquals(
        new long[] {1},
        index.getExactDiscardedTermsForTests(),
        "the term child discards the term popular among the rows it holds");
    assertArrayEquals(
        new long[0],
        index.getSignatureDiscardedTermsForTests(),
        "the signature child discards nothing, since a signature list holds one entry per row"
            + " whatever that row's terms are");
  }

  @Test
  void metadataFilteringAndDeletionApplyAcrossBothChildren() {
    LongTermsAndValues exact = jaccard(sequentialTerms(270, 1));
    LongTermsAndValues approximate = jaccard(sequentialTerms(271, 1));
    LongObjectHashMap<LongMeta> metadata = longObjectMap();
    metadata.put(1, new LongMeta(Map.of("city", "sf"), false));
    metadata.put(2, new LongMeta(Map.of("city", "la"), false));
    HybridIndex index =
        new HybridIndex(config(), longObjectMap(1, exact, 2, approximate), metadata);

    assertEquals(
        List.of(1L),
        rowNumsNearestFirst(
            index.getNearestNeighborRowNums(
                2, approximate, new MetaFilter(Map.of("city", List.of("sf"))))));
    assertTrue(index.delete(1));
    assertTrue(index.delete(2));
    assertFalse(index.delete(2));
    assertTrue(index.getNearestNeighborRowNums(2, approximate, MetaFilter.empty()).isEmpty());
    assertEquals(0, index.size());
    assertTrue(index.getAll().isEmpty());
  }

  @Test
  void validatesHybridSearchArgumentsAndSignatureSupport() {
    HybridIndex index = new HybridIndex(config(), longObjectMap(), longObjectMap());
    LongTermsAndValues query = jaccard(sequentialTerms(1, 1));

    assertThrows(
        IllegalArgumentException.class,
        () -> index.getNearestNeighborRowNums(0, query, MetaFilter.empty()));
    assertThrows(
        IllegalArgumentException.class,
        () -> index.getSimilarRowNums(-0.1f, query, MetaFilter.empty()));
    assertThrows(
        IllegalArgumentException.class,
        () -> index.getSimilarRowNums(1.1f, query, MetaFilter.empty()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new HybridIndex(configWithoutSignatures(), longObjectMap(), longObjectMap()));
  }

  @Test
  void closeClosesBothHybridChildren() {
    HybridIndex index = new HybridIndex(config(), longObjectMap(), longObjectMap());

    index.close();

    assertEquals(0, index.size());
  }

  /** The hybrid fans out to both children, so the merge runs both of its scoring paths. */
  @Test
  void mergeResultsMatchFilteredScanAcrossBothHybridChildren() {
    Random random = new Random(77_213L);
    LongObjectHashMap<LongTermsAndValues> rows = longObjectMap();
    for (long rowNum = 1; rowNum <= 12; ++rowNum) {
      // Rows longer than NUM_SIGNATURES_PER_ROW go to the signature child, the rest to the exact.
      int numTerms = rowNum % 2 == 0 ? 271 + random.nextInt(10) : 8 + random.nextInt(20);
      rows.put(rowNum, jaccard(sequentialTerms(numTerms, 1 + random.nextInt(40))));
    }
    HybridIndex filteredScanIndex = new HybridIndex(config(), rows, longObjectMap());
    HybridIndex mergeIndex =
        new HybridIndex(
            config(
                "jaccard",
                "minhash",
                0,
                1000,
                Map.of(
                    ConfigKeys.CANDIDATE_GENERATOR,
                    NamespaceConfig.CandidateGeneratorType.SPARS_MERGE.getParamValue())),
            rows,
            longObjectMap());

    for (int queryIndex = 0; queryIndex < 12; ++queryIndex) {
      boolean longQuery = queryIndex % 2 == 0;
      LongTermsAndValues query =
          jaccard(sequentialTerms(longQuery ? 280 : 12, 1 + random.nextInt(40)));
      int k = 1 + random.nextInt(6);

      assertEquals(
          rowNums(filteredScanIndex.getNearestNeighborRowNums(k, query, MetaFilter.empty())),
          rowNums(mergeIndex.getNearestNeighborRowNums(k, query, MetaFilter.empty())),
          "nearest queryIndex=" + queryIndex + " k=" + k);
      assertEquals(
          rowNums(filteredScanIndex.getSimilarRowNums(0.2f, query, MetaFilter.empty())),
          rowNums(mergeIndex.getSimilarRowNums(0.2f, query, MetaFilter.empty())),
          "threshold queryIndex=" + queryIndex);
    }
  }

  private static List<Long> rowNums(List<RowNumAndSimilarity> results) {
    return results.stream()
        .sorted(RowNumAndSimilarity.NEAREST_FIRST)
        .map(RowNumAndSimilarity::getRowNum)
        .toList();
  }

  private static NamespaceConfig config() {
    return config(0, 1000);
  }

  private static NamespaceConfig config(int minTermsAndValuesLength, int maxTermsAndValuesLength) {
    return config("jaccard", "minhash", minTermsAndValuesLength, maxTermsAndValuesLength, Map.of());
  }

  private static NamespaceConfig config(
      String comparatorType,
      String signatureGeneratorType,
      int minTermsAndValuesLength,
      int maxTermsAndValuesLength,
      Map<String, String> indexParams) {
    return NamespaceConfig.builder()
        .minTermsAndValuesLength(minTermsAndValuesLength)
        .maxTermsAndValuesLength(maxTermsAndValuesLength)
        .maxCacheSize(100)
        .cacheType("scan")
        .indexType("inverted_hybrid")
        .indexParams(indexParams)
        .comparatorType(comparatorType)
        .comparatorParams(Map.of(ConfigKeys.SIGNATURE_GENERATOR, signatureGeneratorType))
        .comparatorNormalizerType("identity")
        .maxNumSearchableStructures(3)
        .maxNumSimilarities(100)
        .build();
  }

  private static NamespaceConfig configWithoutSignatures() {
    return NamespaceConfig.builder()
        .minTermsAndValuesLength(0)
        .maxTermsAndValuesLength(1000)
        .maxCacheSize(100)
        .cacheType("scan")
        .indexType("inverted_hybrid")
        .comparatorType("jaccard")
        .comparatorNormalizerType("identity")
        .maxNumSearchableStructures(3)
        .maxNumSimilarities(100)
        .build();
  }

  private static LongTermsAndValues jaccard(long[] terms) {
    float[] values = new float[terms.length];
    Arrays.fill(values, 1f);
    return LongTermsAndValuesTestFactory.create(terms, values, terms.length);
  }

  private static LongTermsAndValues ruzicka(long[] terms, float[] values) {
    double uniValue = 0.0;
    for (float value : values) {
      uniValue += Math.abs(value);
    }
    return LongTermsAndValuesTestFactory.create(terms, values, uniValue);
  }

  private static long[] sequentialTerms(int size, long firstTerm) {
    long[] terms = new long[size];
    for (int i = 0; i < size; ++i) {
      terms[i] = firstTerm + i;
    }
    return terms;
  }

  private static List<Long> rowNumsNearestFirst(List<RowNumAndSimilarity> results) {
    return results.stream()
        .sorted(RowNumAndSimilarity.NEAREST_FIRST)
        .map(RowNumAndSimilarity::getRowNum)
        .toList();
  }

  private static float similarityForRow(List<RowNumAndSimilarity> results, long rowNum) {
    return results.stream()
        .filter(result -> result.getRowNum() == rowNum)
        .findFirst()
        .orElseThrow()
        .getSimilarity();
  }
}
