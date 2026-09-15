package com.uber.ussi.searchablestructure.cache;

import static com.uber.ussi.TestLongObjectMaps.longObjectMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carrotsearch.hppc.LongFloatHashMap;
import com.carrotsearch.hppc.LongObjectHashMap;
import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.entity.meta.LongMeta;
import com.uber.ussi.entity.meta.MetaFilter;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValuesTestFactory;
import com.uber.ussi.searchablestructure.RowNumAndSimilarity;
import com.uber.ussi.searchablestructure.index.scan.ScanIndex;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class ScanCacheTest {

  private static final float DELTA = 1e-6f;

  /** L2 is a distance comparator, so a reciprocal normalizer maps it into [0, 1]. */
  private static NamespaceConfig denseL2Config() {
    return NamespaceConfig.builder()
        .minTermsAndValuesLength(0)
        .maxTermsAndValuesLength(2)
        .maxCacheSize(100)
        .cacheType("scan")
        .indexType("matrix")
        .comparatorType("l2")
        .comparatorNormalizerType("reciprocal")
        .maxNumSearchableStructures(3)
        .maxNumSimilarities(10)
        .build();
  }

  private static LongTermsAndValues denseVector(float... values) {
    double uniValue = 0.0;
    for (float value : values) {
      uniValue += value * value;
    }
    return LongTermsAndValuesTestFactory.create(new long[0], values, uniValue);
  }

  private static NamespaceConfig scanConfig(String comparatorType, String normalizerType) {
    return NamespaceConfig.builder()
        .minTermsAndValuesLength(0)
        .maxTermsAndValuesLength(4)
        .maxCacheSize(100)
        .cacheType("scan")
        .indexType("scan")
        .comparatorType(comparatorType)
        .comparatorNormalizerType(normalizerType)
        .maxNumSearchableStructures(3)
        .maxNumSimilarities(10)
        .build();
  }

  /** Jaccard weighs a populated position at one, so its Uni value counts the non-zero ones. */
  private static LongTermsAndValues jaccardDense(float... values) {
    double uniValue = 0.0;
    for (float value : values) {
      uniValue += Math.abs(Math.signum(value));
    }
    return LongTermsAndValuesTestFactory.create(new long[0], values, uniValue);
  }

  /** Ruzicka weighs a position by its magnitude, so its Uni value sums them. */
  private static LongTermsAndValues ruzickaDense(float... values) {
    double uniValue = 0.0;
    for (float value : values) {
      uniValue += Math.abs(value);
    }
    return LongTermsAndValuesTestFactory.create(new long[0], values, uniValue);
  }

  /** A sequence carries its elements in order, with repeats, and holds no values. */
  private static LongTermsAndValues sequence(long... elements) {
    return LongTermsAndValuesTestFactory.create(elements, new float[0], elements.length);
  }

  private static LongFloatHashMap rowNumToSimilarityMap(List<RowNumAndSimilarity> rows) {
    LongFloatHashMap rowNumToSimilarity = new LongFloatHashMap(rows.size());
    for (RowNumAndSimilarity row : rows) {
      rowNumToSimilarity.put(row.getRowNum(), row.getSimilarity());
    }
    return rowNumToSimilarity;
  }

  @Test
  void insertReturnsSequentialRowNumsStartingFromZero() {
    ScanCache cache = new ScanCache(denseL2Config());
    assertEquals(0, cache.insert(denseVector(1f, 0f), Map.of()));
    assertEquals(1, cache.insert(denseVector(0f, 1f), Map.of()));
    assertEquals(2, cache.insert(denseVector(1f, 1f), Map.of()));
    assertEquals(3, cache.size());
  }

  @Test
  void insertThrowsWhenNextRowNumWouldOverflow() {
    ScanCache cache = new ScanCache(denseL2Config());
    // Advance nextRowNum through the public API so the next insert() overflows.
    cache.insertWithRowNum(Long.MAX_VALUE - 1, denseVector(1f, 0f), Map.of());

    assertThrows(IllegalStateException.class, () -> cache.insert(denseVector(0f, 1f), Map.of()));
  }

  @Test
  void insertWithRowNumRejectsDuplicateRowNum() {
    ScanCache cache = new ScanCache(denseL2Config());

    assertTrue(cache.insertWithRowNum(7, denseVector(1f, 0f), Map.of()));
    assertFalse(cache.insertWithRowNum(7, denseVector(0f, 1f), Map.of()));
  }

  @Test
  void insertStoresEncodedLongTerms() {
    ScanCache cache = new ScanCache(denseL2Config());
    LongTermsAndValues encodedRecord =
        LongTermsAndValuesTestFactory.create(
            new long[] {LongMeta.longHashCode("term")}, new float[] {1f}, 1.0d);

    long rowNum = cache.insert(encodedRecord, Map.of());

    assertEquals(LongMeta.longHashCode("term"), cache.getAll().get(rowNum).getTerm(0));
  }

  @Test
  void insertIndexesAllNonNullMetadataEntries() {
    ScanCache cache = new ScanCache(denseL2Config());
    Map<String, String> metadata = new LinkedHashMap<>();
    metadata.put(null, "sf");
    metadata.put("city", null);
    metadata.put("state", "ca");

    long rowNum = cache.insert(denseVector(1f, 0f), metadata);

    assertEquals(
        new LongMeta(Map.of("state", "ca"), /* requireLongKeysAndValues */ false),
        cache.getAllMetadata().get(rowNum));
  }

  @Test
  void insertWithOnlyNullMetadataEntriesStoresEmptyMetadata() {
    ScanCache cache = new ScanCache(denseL2Config());
    Map<String, String> metadata = new LinkedHashMap<>();
    metadata.put(null, "sf");
    metadata.put("city", null);

    long rowNum = cache.insert(denseVector(1f, 0f), metadata);

    assertEquals(LongMeta.empty(), cache.getAllMetadata().get(rowNum));
  }

  @Test
  void insertThrowsWhenMetadataModuleAlreadyContainsRowNum() {
    ScanCache cache = new ScanCache(denseL2Config());
    cache.metadataFilteringModule.put(7, LongMeta.empty());

    assertThrows(
        IllegalStateException.class,
        () -> cache.insertWithRowNum(7, denseVector(1f, 0f), Map.of()));
  }

  @Test
  void deleteUnknownRowReturnsFalseWithoutThrowing() {
    ScanCache cache = new ScanCache(denseL2Config());
    assertFalse(cache.delete(42));
  }

  @Test
  void deleteRemovesRowFromGetAll() {
    ScanCache cache = new ScanCache(denseL2Config());
    long rowNum = cache.insert(denseVector(1f, 0f), Map.of());
    assertTrue(cache.delete(rowNum));
    assertFalse(cache.getAll().containsKey(rowNum));
    assertTrue(cache.isEmpty());
  }

  @Test
  void updateUnknownRowReturnsFalse() {
    ScanCache cache = new ScanCache(denseL2Config());
    assertFalse(cache.update(7, denseVector(1f, 0f), Map.of()));
  }

  @Test
  void updateReplacesRecord() {
    // ERD: an update is a delete followed by an insert with the same rowNum.
    ScanCache cache = new ScanCache(denseL2Config());
    long rowNum = cache.insert(denseVector(1f, 0f), Map.of());
    assertTrue(cache.update(rowNum, denseVector(0f, 1f), Map.of()));

    List<RowNumAndSimilarity> result =
        cache.getNearestNeighborRowNums(1, denseVector(0f, 1f), MetaFilter.empty());
    assertEquals(rowNum, result.get(0).getRowNum());
    assertEquals(1.0f, result.get(0).getSimilarity(), DELTA);
    assertEquals(1, cache.insert(denseVector(1f, 1f), Map.of()));
  }

  /**
   * The scan cache scores every row through the comparator, exactly as the scan index does, so it
   * serves whatever the comparator reads rather than one record type of its own. Its own tests
   * only ever configured l2 over dense vectors, which left the dense form of the two multiset
   * measures and sequences of either edit distance unsearched here, even though the cache config
   * validator accepts all of them.
   */
  @Test
  void theScanCacheAgreesWithTheScanIndexOnEveryRecordTypeItsComparatorReads() {
    assertAgreesWithScanIndex(
        "jaccard over dense vectors",
        scanConfig("jaccard", "identity"),
        List.of(jaccardDense(1f, 0f, 1f), jaccardDense(1f, 1f, 1f), jaccardDense(0f, 0f, 1f)));
    assertAgreesWithScanIndex(
        "ruzicka over dense vectors",
        scanConfig("ruzicka", "identity"),
        List.of(ruzickaDense(2f, 0f, 1f), ruzickaDense(1f, 1f, 1f), ruzickaDense(0f, 0f, 3f)));
    assertAgreesWithScanIndex(
        "gld over sequences",
        scanConfig("gld", "reciprocal"),
        List.of(sequence(1, 2, 3), sequence(1, 2, 4), sequence(3, 2, 1)));
    assertAgreesWithScanIndex(
        "ngld over sequences",
        scanConfig("ngld", "complement"),
        List.of(sequence(1, 2, 3), sequence(1, 2, 4), sequence(3, 2, 1)));
  }

  /**
   * Queries by the first record, so the cache has to return it scored 1.0 rather than agreeing
   * with the index on an empty result.
   */
  private static void assertAgreesWithScanIndex(
      String where, NamespaceConfig config, List<LongTermsAndValues> records) {
    ScanCache cache = new ScanCache(config);
    LongObjectHashMap<LongTermsAndValues> rows = longObjectMap();
    for (LongTermsAndValues record : records) {
      rows.put(cache.insert(record, Map.of()), record);
    }
    ScanIndex index = new ScanIndex(config, rows, longObjectMap());
    LongTermsAndValues query = records.get(0);

    List<RowNumAndSimilarity> fromCache =
        cache.getSimilarRowNums(0.0f, query, MetaFilter.empty());
    assertEquals(
        similaritiesByRowNum(index.getSimilarRowNums(0.0f, query, MetaFilter.empty())),
        similaritiesByRowNum(fromCache),
        where);
    assertEquals(1.0f, rowNumToSimilarityMap(fromCache).get(0), DELTA, where + " on itself");
  }

  /** Results arrive unordered, so compare them keyed by row rather than as a sequence. */
  private static Map<Long, Float> similaritiesByRowNum(List<RowNumAndSimilarity> results) {
    Map<Long, Float> similarities = new TreeMap<>();
    for (RowNumAndSimilarity result : results) {
      similarities.put(result.getRowNum(), result.getSimilarity());
    }
    return similarities;
  }

  @Test
  void getNearestNeighborRowNumsKeepsMostSimilarRows() {
    ScanCache cache = new ScanCache(denseL2Config());
    long exact = cache.insert(denseVector(1f, 0f), Map.of());
    long far = cache.insert(denseVector(0f, 1f), Map.of());
    long mid = cache.insert(denseVector(1f, 1f), Map.of());

    List<RowNumAndSimilarity> result =
        cache.getNearestNeighborRowNums(2, denseVector(1f, 0f), MetaFilter.empty());

    assertEquals(2, result.size());
    LongFloatHashMap rowNumToSimilarity = rowNumToSimilarityMap(result);
    assertTrue(rowNumToSimilarity.containsKey(exact));
    assertTrue(rowNumToSimilarity.containsKey(mid));
    // Similarity is normalized to [0, 1]; an exact match yields 1.0.
    assertEquals(1.0f, rowNumToSimilarity.get(exact), DELTA);
    assertEquals(0.5f, rowNumToSimilarity.get(mid), DELTA);
    assertFalse(rowNumToSimilarity.containsKey(far));
  }

  @Test
  void getNearestNeighborRowNumsBreaksSimilarityTiesByRowNumAscending() {
    ScanCache cache = new ScanCache(denseL2Config());
    long first = cache.insert(denseVector(1f, 0f), Map.of());
    cache.insert(denseVector(1f, 0f), Map.of());

    List<RowNumAndSimilarity> result =
        cache.getNearestNeighborRowNums(1, denseVector(1f, 0f), MetaFilter.empty());

    assertEquals(List.of(first), result.stream().map(row -> row.getRowNum()).toList());
  }

  @Test
  void getSimilarRowNumsRespectsMinSimilarity() {
    ScanCache cache = new ScanCache(denseL2Config());
    long exact = cache.insert(denseVector(1f, 0f), Map.of()); // distance 0    -> similarity 1.0
    long far = cache.insert(denseVector(0f, 1f), Map.of()); //   distance sqrt2 -> similarity ~0.414
    long mid = cache.insert(denseVector(1f, 1f), Map.of()); //   distance 1     -> similarity 0.5

    List<RowNumAndSimilarity> result =
        cache.getSimilarRowNums(0.5f, denseVector(1f, 0f), MetaFilter.empty());

    LongFloatHashMap rowNumToSimilarity = rowNumToSimilarityMap(result);
    assertEquals(2, result.size());
    assertTrue(rowNumToSimilarity.containsKey(exact));
    assertTrue(rowNumToSimilarity.containsKey(mid));
    assertFalse(rowNumToSimilarity.containsKey(far));
    assertEquals(0.5f, rowNumToSimilarity.get(mid), DELTA);
  }

  @Test
  void metadataFilterRestrictsResults() {
    ScanCache cache = new ScanCache(denseL2Config());
    long sanFrancisco = cache.insert(denseVector(1f, 0f), Map.of("city", "sf"));
    cache.insert(denseVector(1f, 0f), Map.of("city", "la"));

    MetaFilter filter = new MetaFilter(Map.of("city", List.of("sf")));
    List<RowNumAndSimilarity> result = cache.getNearestNeighborRowNums(10, denseVector(1f, 0f), filter);

    assertEquals(1, result.size());
    assertEquals(sanFrancisco, result.get(0).getRowNum());
  }

  @Test
  void emptyMetadataFilterMatchesAllRows() {
    ScanCache cache = new ScanCache(denseL2Config());
    cache.insert(denseVector(1f, 0f), Map.of("city", "sf"));
    cache.insert(denseVector(1f, 0f), Map.of("city", "la"));

    List<RowNumAndSimilarity> result =
        cache.getNearestNeighborRowNums(10, denseVector(1f, 0f), MetaFilter.empty());

    assertEquals(2, result.size());
  }

  @Test
  void getNearestNeighborRowNumsRejectsNonPositiveK() {
    ScanCache cache = new ScanCache(denseL2Config());
    assertThrows(
        IllegalArgumentException.class,
        () -> cache.getNearestNeighborRowNums(0, denseVector(1f, 0f), MetaFilter.empty()));
  }

  @Test
  void getSimilarRowNumsRejectsOutOfRangeMinSimilarity() {
    // ERD: "a similarity range of [0, 1]".
    ScanCache cache = new ScanCache(denseL2Config());
    assertThrows(
        IllegalArgumentException.class,
        () -> cache.getSimilarRowNums(1.5f, denseVector(1f, 0f), MetaFilter.empty()));
  }
}
