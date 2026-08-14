package com.uber.ussi.searchablestructure.index.sparse;

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
import com.uber.ussi.error.IndexCreationError;
import com.uber.ussi.searchablestructure.RowNumAndSimilarity;
import com.uber.ussi.utils.Constants;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SignatureIndexTest {
  private static final float DELTA = 1e-6f;

  @Test
  void constructorDeduplicatesGeneratedSignatureKeys() {
    SignatureIndex index =
        new SignatureIndex(
            config("jaccard", "minhash"),
            longObjectMap(7, jaccard(new long[] {11}, 1f)),
            longObjectMap());

    assertEquals(1, index.getNumIndexedSparseKeysForTests());
    assertEquals(
        1, index.getSparseKeysAndUniTransformedValues(jaccard(new long[] {11}, 1f)).length);
    long[] signatures = index.getSparseKeys(jaccard(new long[] {11}, 1f));
    assertEquals(Constants.NUM_SIGNATURES_PER_ID, signatures.length);
    assertArrayEquals(new long[] {7}, index.getRowNumsForSparseKeyForTests(signatures[0]));
  }

  @Test
  void identicalRecordsAreRetrievedAndScoredUsingOriginalTermsAndValues() {
    LongTermsAndValues matching = jaccard(sequentialTerms(300, 1), repeatedValue(1f, 300));
    LongTermsAndValues disjoint = jaccard(sequentialTerms(300, 1001), repeatedValue(1f, 300));
    SignatureIndex index =
        new SignatureIndex(
            config("jaccard", "minhash"), longObjectMap(1, matching, 2, disjoint), longObjectMap());

    List<RowNumAndSimilarity> results = index.getNearestNeighbors(2, matching, MetaFilter.empty());

    assertEquals(List.of(1L), rowNumsNearestFirst(results));
    assertEquals(1.0f, results.get(0).getSimilarity(), DELTA);
    assertEquals(
        List.of(1L),
        rowNumsNearestFirst(index.getSimilarRowNums(1.0f, matching, MetaFilter.empty())));
  }

  @Test
  void metadataFilteringAndDeletionApplyToSignatureCandidates() {
    LongTermsAndValues record = jaccard(sequentialTerms(300, 1), repeatedValue(1f, 300));
    LongObjectHashMap<LongMeta> metadata = longObjectMap();
    metadata.put(1, new LongMeta(Map.of("city", "sf"), false));
    SignatureIndex index =
        new SignatureIndex(config("jaccard", "minhash"), longObjectMap(1, record), metadata);
    MetaFilter sf = new MetaFilter(Map.of("city", List.of("sf")));

    assertEquals(List.of(1L), rowNumsNearestFirst(index.getNearestNeighbors(1, record, sf)));
    assertTrue(index.delete(1));
    assertFalse(index.delete(1));
    assertTrue(index.getNearestNeighbors(1, record, sf).isEmpty());
  }

  @Test
  void weightedSignatureIndexSupportsRuzicka() {
    LongTermsAndValues record = ruzicka(sequentialTerms(300, 1), repeatedIncreasingValues(300));
    SignatureIndex index =
        new SignatureIndex(config("ruzicka", "icws"), longObjectMap(1, record), longObjectMap());

    List<RowNumAndSimilarity> results = index.getNearestNeighbors(1, record, MetaFilter.empty());

    assertEquals(List.of(1L), rowNumsNearestFirst(results));
    assertEquals(1.0f, results.get(0).getSimilarity(), DELTA);
  }

  @Test
  void rowsWithOnlyFilteredTermsHaveNoSignatureInvertedLists() {
    LongTermsAndValues record = jaccard(new long[] {11}, 1f);
    SignatureIndex index =
        new SignatureIndex(
            config("jaccard", "minhash", Map.of(Constants.MAX_FRACTION_IDS_PER_SPARSE_KEY, "0.5")),
            longObjectMap(1, record, 2, record),
            longObjectMap());

    assertArrayEquals(new long[] {11}, index.getFilteredOutTermsForTests());
    assertEquals(0, index.getNumIndexedSparseKeysForTests());
    assertEquals(2, index.size());
    assertTrue(index.getNearestNeighbors(2, record, MetaFilter.empty()).isEmpty());
  }

  @Test
  void constructorRequiresSignatureSupportBeforeBuildingRows() {
    NamespaceConfig exactJaccardConfig = config("jaccard", null);

    assertThrows(
        IndexCreationError.class,
        () -> new SignatureIndex(exactJaccardConfig, longObjectMap(), longObjectMap()));
    assertThrows(
        IndexCreationError.class,
        () ->
            new SignatureIndex(
                exactJaccardConfig,
                longObjectMap(1, jaccard(new long[] {1}, 1f)),
                longObjectMap()));
    assertThrows(
        IndexCreationError.class,
        () ->
            new SignatureIndex(
                config("l2", null), longObjectMap(1, l2(new long[] {1}, 1f)), longObjectMap()));
  }

  private static NamespaceConfig config(String comparatorType, String signatureGeneratorType) {
    return config(comparatorType, signatureGeneratorType, Map.of());
  }

  private static NamespaceConfig config(
      String comparatorType, String signatureGeneratorType, Map<String, String> indexParams) {
    Map<String, String> comparatorParams =
        signatureGeneratorType == null
            ? Map.of()
            : Map.of(Constants.SIGNATURE_GENERATOR_TYPE, signatureGeneratorType);
    return NamespaceConfig.builder()
        .minTermsAndValuesLength(0)
        .maxTermsAndValuesLength(1000)
        .maxCacheSize(100)
        .cacheType("generic")
        .indexType("signature")
        .indexParams(indexParams)
        .comparatorType(comparatorType)
        .comparatorParams(comparatorParams)
        .comparatorNormalizerType(comparatorType.equals("l2") ? "reciprocal" : "identity")
        .maxNumSearchableStructures(3)
        .maxNumSimilarities(100)
        .build();
  }

  private static LongTermsAndValues jaccard(long[] terms, float... values) {
    return LongTermsAndValuesTestFactory.create(terms, values, terms.length);
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

  private static long[] sequentialTerms(int size, long firstTerm) {
    long[] terms = new long[size];
    for (int i = 0; i < size; ++i) {
      terms[i] = firstTerm + i;
    }
    return terms;
  }

  private static float[] repeatedValue(float value, int size) {
    float[] values = new float[size];
    java.util.Arrays.fill(values, value);
    return values;
  }

  private static float[] repeatedIncreasingValues(int size) {
    float[] values = new float[size];
    for (int i = 0; i < size; ++i) {
      values[i] = i + 1;
    }
    return values;
  }

  private static List<Long> rowNumsNearestFirst(List<RowNumAndSimilarity> results) {
    return results.stream()
        .sorted(RowNumAndSimilarity.NEAREST_FIRST)
        .map(RowNumAndSimilarity::getRowNum)
        .toList();
  }
}
