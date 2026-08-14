package com.uber.ussi.searchablestructure.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carrotsearch.hppc.LongFloatHashMap;
import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.entity.meta.LongMeta;
import com.uber.ussi.entity.meta.MetaFilter;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValuesTestFactory;
import com.uber.ussi.searchablestructure.RowNumAndSimilarity;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GenericCacheTest {

  private static final float DELTA = 1e-6f;

  /**
   * Builds a valid USSI namespace config for an L2 dense-vector cache. Per the ERD, L2 is a
   * distance comparator, so a Reciprocal comparator-normalizer is used to map the distance into the
   * [0, 1] similarity range required by the search APIs.
   */
  private static NamespaceConfig denseL2Config() {
    return NamespaceConfig.builder()
        .minTermsAndValuesLength(0)
        .maxTermsAndValuesLength(2)
        .maxCacheSize(100)
        .cacheType("generic")
        .indexType("dense")
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

  private static LongFloatHashMap rowNumToSimilarityMap(List<RowNumAndSimilarity> rows) {
    LongFloatHashMap rowNumToSimilarity = new LongFloatHashMap(rows.size());
    for (RowNumAndSimilarity row : rows) {
      rowNumToSimilarity.put(row.getRowNum(), row.getSimilarity());
    }
    return rowNumToSimilarity;
  }

  @Test
  void insertReturnsSequentialRowNumsStartingFromZero() {
    // ERD: "The rowNums start from 0, and are incremented with each insertion."
    GenericCache cache = new GenericCache(denseL2Config());
    assertEquals(0, cache.insert(denseVector(1f, 0f), Map.of()));
    assertEquals(1, cache.insert(denseVector(0f, 1f), Map.of()));
    assertEquals(2, cache.insert(denseVector(1f, 1f), Map.of()));
    assertEquals(3, cache.size());
  }

  @Test
  void insertThrowsWhenNextRowNumWouldOverflow() {
    GenericCache cache = new GenericCache(denseL2Config());
    /*
     * Advance nextRowNum to Long.MAX_VALUE through the public API so the next sequential insert()
     * overflows, without reaching into private state.
     */
    cache.insertWithRowNum(Long.MAX_VALUE - 1, denseVector(1f, 0f), Map.of());

    assertThrows(IllegalStateException.class, () -> cache.insert(denseVector(0f, 1f), Map.of()));
  }

  @Test
  void insertWithRowNumRejectsDuplicateRowNum() {
    GenericCache cache = new GenericCache(denseL2Config());

    assertTrue(cache.insertWithRowNum(7, denseVector(1f, 0f), Map.of()));
    assertFalse(cache.insertWithRowNum(7, denseVector(0f, 1f), Map.of()));
  }

  @Test
  void insertStoresEncodedLongTerms() {
    GenericCache cache = new GenericCache(denseL2Config());
    LongTermsAndValues encodedRecord =
        LongTermsAndValuesTestFactory.create(
            new long[] {LongMeta.longHashCode("term")}, new float[] {1f}, 1.0d);

    long rowNum = cache.insert(encodedRecord, Map.of());

    assertEquals(LongMeta.longHashCode("term"), cache.getAll().get(rowNum).getTerm(0));
  }

  @Test
  void insertIndexesAllNonNullMetadataEntries() {
    GenericCache cache = new GenericCache(denseL2Config());
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
    GenericCache cache = new GenericCache(denseL2Config());
    Map<String, String> metadata = new LinkedHashMap<>();
    metadata.put(null, "sf");
    metadata.put("city", null);

    long rowNum = cache.insert(denseVector(1f, 0f), metadata);

    assertEquals(LongMeta.empty(), cache.getAllMetadata().get(rowNum));
  }

  @Test
  void insertThrowsWhenMetadataModuleAlreadyContainsRowNum() {
    GenericCache cache = new GenericCache(denseL2Config());
    cache.metadataFilteringModule.put(7, LongMeta.empty());

    assertThrows(
        IllegalStateException.class,
        () -> cache.insertWithRowNum(7, denseVector(1f, 0f), Map.of()));
  }

  @Test
  void deleteUnknownRowReturnsFalseWithoutThrowing() {
    // ERD: "If the delete is unsuccessful ... the API returns false, and no exception is thrown."
    GenericCache cache = new GenericCache(denseL2Config());
    assertFalse(cache.delete(42));
  }

  @Test
  void deleteRemovesRowFromGetAll() {
    GenericCache cache = new GenericCache(denseL2Config());
    long rowNum = cache.insert(denseVector(1f, 0f), Map.of());
    assertTrue(cache.delete(rowNum));
    assertFalse(cache.getAll().containsKey(rowNum));
    assertTrue(cache.isEmpty());
  }

  @Test
  void updateUnknownRowReturnsFalse() {
    GenericCache cache = new GenericCache(denseL2Config());
    assertFalse(cache.update(7, denseVector(1f, 0f), Map.of()));
  }

  @Test
  void updateReplacesRecord() {
    /*
     * ERD: "an update operation is equivalent to a delete followed by an insert with the same
     * rowNum."
     */
    GenericCache cache = new GenericCache(denseL2Config());
    long rowNum = cache.insert(denseVector(1f, 0f), Map.of());
    assertTrue(cache.update(rowNum, denseVector(0f, 1f), Map.of()));

    List<RowNumAndSimilarity> result =
        cache.getNearestNeighbors(1, denseVector(0f, 1f), MetaFilter.empty());
    assertEquals(rowNum, result.get(0).getRowNum());
    assertEquals(1.0f, result.get(0).getSimilarity(), DELTA);
    assertEquals(1, cache.insert(denseVector(1f, 1f), Map.of()));
  }

  @Test
  void getNearestNeighborsKeepsMostSimilarRows() {
    GenericCache cache = new GenericCache(denseL2Config());
    long exact = cache.insert(denseVector(1f, 0f), Map.of());
    long far = cache.insert(denseVector(0f, 1f), Map.of());
    long mid = cache.insert(denseVector(1f, 1f), Map.of());

    List<RowNumAndSimilarity> result =
        cache.getNearestNeighbors(2, denseVector(1f, 0f), MetaFilter.empty());

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
  void getNearestNeighborsBreaksSimilarityTiesByRowNumAscending() {
    GenericCache cache = new GenericCache(denseL2Config());
    long first = cache.insert(denseVector(1f, 0f), Map.of());
    cache.insert(denseVector(1f, 0f), Map.of());

    List<RowNumAndSimilarity> result =
        cache.getNearestNeighbors(1, denseVector(1f, 0f), MetaFilter.empty());

    assertEquals(List.of(first), result.stream().map(row -> row.getRowNum()).toList());
  }

  @Test
  void getSimilarRowNumsRespectsMinSimilarity() {
    // ERD: "searches for all the records similar to the query record by at least minSimilarity".
    GenericCache cache = new GenericCache(denseL2Config());
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
    // ERD: metadata filtering keeps only rows whose metadata matches the filter.
    GenericCache cache = new GenericCache(denseL2Config());
    long sanFrancisco = cache.insert(denseVector(1f, 0f), Map.of("city", "sf"));
    cache.insert(denseVector(1f, 0f), Map.of("city", "la"));

    MetaFilter filter = new MetaFilter(Map.of("city", List.of("sf")));
    List<RowNumAndSimilarity> result = cache.getNearestNeighbors(10, denseVector(1f, 0f), filter);

    assertEquals(1, result.size());
    assertEquals(sanFrancisco, result.get(0).getRowNum());
  }

  @Test
  void emptyMetadataFilterMatchesAllRows() {
    GenericCache cache = new GenericCache(denseL2Config());
    cache.insert(denseVector(1f, 0f), Map.of("city", "sf"));
    cache.insert(denseVector(1f, 0f), Map.of("city", "la"));

    List<RowNumAndSimilarity> result =
        cache.getNearestNeighbors(10, denseVector(1f, 0f), MetaFilter.empty());

    assertEquals(2, result.size());
  }

  @Test
  void getNearestNeighborsRejectsNonPositiveK() {
    GenericCache cache = new GenericCache(denseL2Config());
    assertThrows(
        IllegalArgumentException.class,
        () -> cache.getNearestNeighbors(0, denseVector(1f, 0f), MetaFilter.empty()));
  }

  @Test
  void getSimilarRowNumsRejectsOutOfRangeMinSimilarity() {
    // ERD: "a similarity range of [0, 1]".
    GenericCache cache = new GenericCache(denseL2Config());
    assertThrows(
        IllegalArgumentException.class,
        () -> cache.getSimilarRowNums(1.5f, denseVector(1f, 0f), MetaFilter.empty()));
  }
}
