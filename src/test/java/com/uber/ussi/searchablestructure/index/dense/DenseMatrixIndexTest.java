package com.uber.ussi.searchablestructure.index.dense;

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
import com.uber.ussi.searchablestructure.index.Index;
import com.uber.ussi.searchablestructure.metadata.MetadataFilteringStrategy;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DenseMatrixIndexTest {
  private static final float DELTA = 1e-6f;

  @Test
  void constructorMaterializesDenseRows() {
    DenseMatrixIndex index = new DenseMatrixIndex(config(), rows(), metadata());

    assertEquals(3, index.size());
    assertEquals(2, index.getDimensionForTests());
    assertTrue(index.getAll().containsKey(10));
    assertTrue(index.getAll().containsKey(11));
    assertTrue(index.getAll().containsKey(12));
  }

  @Test
  void closeCanBeCalled() {
    DenseMatrixIndex index = new DenseMatrixIndex(config(), rows(), metadata());

    index.close();
  }

  @Test
  void emptyIndexSearchReturnsEmptyResult() {
    DenseMatrixIndex index = new DenseMatrixIndex(config(), longObjectMap(), longObjectMap());

    assertTrue(index.getNearestNeighbors(1, denseVector(1f, 0f), MetaFilter.empty()).isEmpty());
  }

  @Test
  void getNearestNeighborsKeepsMostSimilarRows() {
    DenseMatrixIndex index = new DenseMatrixIndex(config(), rows(), metadata());

    List<RowNumAndSimilarity> result =
        index.getNearestNeighbors(2, denseVector(1f, 0f), MetaFilter.empty());

    LongFloatHashMap rowNumToSimilarity = rowNumToSimilarityMap(result);
    assertEquals(2, result.size());
    assertTrue(rowNumToSimilarity.containsKey(10));
    assertTrue(rowNumToSimilarity.containsKey(12));
    assertEquals(1.0f, rowNumToSimilarity.get(10), DELTA);
    assertEquals(0.5f, rowNumToSimilarity.get(12), DELTA);
  }

  @Test
  void getNearestNeighborsBreaksSimilarityTiesByRowNumAscending() {
    LongObjectHashMap<LongTermsAndValues> rows = longObjectMap();
    rows.put(31, denseInternal(1f, 0f));
    rows.put(30, denseInternal(1f, 0f));
    rows.put(32, denseInternal(0f, 1f));
    DenseMatrixIndex index = new DenseMatrixIndex(config(), rows, longObjectMap());

    List<RowNumAndSimilarity> result =
        index.getNearestNeighbors(1, denseVector(1f, 0f), MetaFilter.empty());

    assertEquals(List.of(30L), result.stream().map(row -> row.getRowNum()).toList());
  }

  @Test
  void getSimilarRowNumsRespectsMinSimilarity() {
    DenseMatrixIndex index = new DenseMatrixIndex(config(), rows(), metadata());

    List<RowNumAndSimilarity> result =
        index.getSimilarRowNums(0.5f, denseVector(1f, 0f), MetaFilter.empty());

    assertEquals(List.of(10L, 12L), sortedRowNums(result));
  }

  @Test
  void autoMetadataFilteringUsesPreFilteringWhenFilterIsSelective() {
    DenseMatrixIndex index =
        new DenseMatrixIndex(
            configWithIndexParams(Map.of(Index.MAX_PRE_FILTERING_ROWS_RATIO, "0.34")),
            rows(),
            metadata());

    List<RowNumAndSimilarity> result =
        index.getNearestNeighbors(
            10, denseVector(1f, 0f), new MetaFilter(Map.of("city", List.of("sf"))));

    assertEquals(List.of(10L), result.stream().map(row -> row.getRowNum()).toList());
    assertEquals(
        MetadataFilteringStrategy.PRE_FILTERING,
        index.getResolvedMetadataFilteringStrategyForLastSearchForTests());
  }

  @Test
  void autoMetadataFilteringUsesPostFilteringWhenFilterIsNotSelective() {
    DenseMatrixIndex index =
        new DenseMatrixIndex(
            configWithIndexParams(Map.of(Index.MAX_PRE_FILTERING_ROWS_RATIO, "0.34")),
            rows(),
            metadata());

    List<RowNumAndSimilarity> result =
        index.getNearestNeighbors(
            10, denseVector(1f, 0f), new MetaFilter(Map.of("city", List.of("sf", "la"))));

    assertEquals(List.of(10L, 11L), sortedRowNums(result));
    assertEquals(
        MetadataFilteringStrategy.POST_FILTERING,
        index.getResolvedMetadataFilteringStrategyForLastSearchForTests());
  }

  @Test
  void inFilteringStrategyFiltersDuringMatrixScanAndSkipsDeletedRows() {
    DenseMatrixIndex index =
        new DenseMatrixIndex(
            configWithIndexParams(Map.of(Index.METADATA_FILTERING_STRATEGY, "in_filtering")),
            rows(),
            metadata());

    assertTrue(index.delete(10));
    List<RowNumAndSimilarity> result =
        index.getNearestNeighbors(
            10, denseVector(1f, 0f), new MetaFilter(Map.of("city", List.of("sf"))));

    assertTrue(result.isEmpty());
    assertEquals(
        MetadataFilteringStrategy.IN_FILTERING,
        index.getResolvedMetadataFilteringStrategyForLastSearchForTests());
  }

  @Test
  void inFilteringStrategyCropsFilteredRowsToMaxResults() {
    DenseMatrixIndex index =
        new DenseMatrixIndex(
            configWithIndexParams(Map.of(Index.METADATA_FILTERING_STRATEGY, "in_filtering")),
            rows(),
            allSfMetadata());

    List<RowNumAndSimilarity> result =
        index.getNearestNeighbors(
            1, denseVector(1f, 0f), new MetaFilter(Map.of("city", List.of("sf"))));

    assertEquals(List.of(10L), result.stream().map(row -> row.getRowNum()).toList());
    assertEquals(
        MetadataFilteringStrategy.IN_FILTERING,
        index.getResolvedMetadataFilteringStrategyForLastSearchForTests());
  }

  @Test
  void preFilteringStrategyCropsCandidateRowsToMaxResults() {
    DenseMatrixIndex index =
        new DenseMatrixIndex(
            configWithIndexParams(
                Map.of(
                    Index.METADATA_FILTERING_STRATEGY,
                    "pre_filtering",
                    Index.MAX_PRE_FILTERING_ROWS_RATIO,
                    "1.0")),
            rows(),
            allSfMetadata());

    List<RowNumAndSimilarity> result =
        index.getNearestNeighbors(
            1, denseVector(1f, 0f), new MetaFilter(Map.of("city", List.of("sf"))));

    assertEquals(List.of(10L), result.stream().map(row -> row.getRowNum()).toList());
    assertEquals(
        MetadataFilteringStrategy.PRE_FILTERING,
        index.getResolvedMetadataFilteringStrategyForLastSearchForTests());
  }

  @Test
  void preFilteringStrategyFallsBackToPostFilteringWhenLimitIsExceeded() {
    DenseMatrixIndex index =
        new DenseMatrixIndex(
            configWithIndexParams(
                Map.of(
                    Index.METADATA_FILTERING_STRATEGY,
                    "pre_filtering",
                    Index.MAX_PRE_FILTERING_ROWS_RATIO,
                    "0.34")),
            rows(),
            metadata());

    List<RowNumAndSimilarity> result =
        index.getNearestNeighbors(
            10, denseVector(1f, 0f), new MetaFilter(Map.of("city", List.of("sf", "la"))));

    assertEquals(List.of(10L, 11L), sortedRowNums(result));
    assertEquals(
        MetadataFilteringStrategy.POST_FILTERING,
        index.getResolvedMetadataFilteringStrategyForLastSearchForTests());
  }

  @Test
  void postFilteringExpandsSearchPoolToFindMatchesNaiveTopKWouldMiss() {
    LongObjectHashMap<LongTermsAndValues> rows = longObjectMap();
    rows.put(20, denseInternal(1f, 0f)); // closest to query, city=sf
    rows.put(21, denseInternal(1f, 1f)); // middle, city=sf
    rows.put(22, denseInternal(0f, 1f)); // farthest, city=la and the only match
    LongObjectHashMap<LongMeta> metadata = longObjectMap();
    metadata.put(20, longMeta("city", "sf"));
    metadata.put(21, longMeta("city", "sf"));
    metadata.put(22, longMeta("city", "la"));
    DenseMatrixIndex index =
        new DenseMatrixIndex(
            configWithIndexParams(Map.of(Index.METADATA_FILTERING_STRATEGY, "post_filtering")),
            rows,
            metadata);

    List<RowNumAndSimilarity> result =
        index.getNearestNeighbors(
            1, denseVector(1f, 0f), new MetaFilter(Map.of("city", List.of("la"))));

    assertEquals(List.of(22L), result.stream().map(row -> row.getRowNum()).toList());
    assertEquals(
        MetadataFilteringStrategy.POST_FILTERING,
        index.getResolvedMetadataFilteringStrategyForLastSearchForTests());
  }

  @Test
  void postFilteringReturnsEmptyWhenNoMetadataMatches() {
    DenseMatrixIndex index =
        new DenseMatrixIndex(
            configWithIndexParams(Map.of(Index.METADATA_FILTERING_STRATEGY, "post_filtering")),
            rows(),
            metadata());

    List<RowNumAndSimilarity> result =
        index.getNearestNeighbors(
            1, denseVector(1f, 0f), new MetaFilter(Map.of("city", List.of("missing"))));

    assertTrue(result.isEmpty());
    assertEquals(
        MetadataFilteringStrategy.POST_FILTERING,
        index.getResolvedMetadataFilteringStrategyForLastSearchForTests());
  }

  @Test
  void deleteRemovesRowsFromSearchAndGetAll() {
    DenseMatrixIndex index = new DenseMatrixIndex(config(), rows(), metadata());

    assertTrue(index.delete(10));
    assertFalse(index.delete(10));
    assertFalse(index.getAll().containsKey(10));

    List<RowNumAndSimilarity> result =
        index.getNearestNeighbors(10, denseVector(1f, 0f), MetaFilter.empty());
    assertEquals(List.of(11L, 12L), sortedRowNums(result));
  }

  @Test
  void constructorRejectsNonDenseRows() {
    LongObjectHashMap<LongTermsAndValues> rows = longObjectMap();
    rows.put(1, LongTermsAndValuesTestFactory.create(new long[] {7L}, new float[] {1f}, 1.0d));

    assertThrows(
        IllegalArgumentException.class,
        () -> new DenseMatrixIndex(config(), rows, longObjectMap()));
  }

  @Test
  void constructorRejectsRowsWithoutValues() {
    LongObjectHashMap<LongTermsAndValues> rows = longObjectMap();
    rows.put(1, LongTermsAndValuesTestFactory.create(new long[0], new float[0], 0.0d));

    assertThrows(
        IllegalArgumentException.class,
        () -> new DenseMatrixIndex(config(), rows, longObjectMap()));
  }

  @Test
  void constructorRejectsMismatchedDimensions() {
    LongObjectHashMap<LongTermsAndValues> rows = longObjectMap();
    rows.put(1, denseInternal(1f, 0f));
    rows.put(2, denseInternal(1f, 0f, 0f));

    assertThrows(
        IllegalArgumentException.class,
        () -> new DenseMatrixIndex(config(), rows, longObjectMap()));
  }

  @Test
  void queryRejectsMismatchedDimensions() {
    DenseMatrixIndex index = new DenseMatrixIndex(config(), rows(), metadata());

    assertThrows(
        IllegalArgumentException.class,
        () -> index.getNearestNeighbors(1, denseVector(1f, 0f, 0f), MetaFilter.empty()));
  }

  @Test
  void queryRejectsNullAndTerms() {
    DenseMatrixIndex index = new DenseMatrixIndex(config(), rows(), metadata());

    assertThrows(
        NullPointerException.class, () -> index.getNearestNeighbors(1, null, MetaFilter.empty()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            index.getNearestNeighbors(
                1,
                LongTermsAndValuesTestFactory.create(new long[] {7L}, new float[] {1f}, 1.0d),
                MetaFilter.empty()));
  }

  @Test
  void invalidSearchArgumentsThrow() {
    DenseMatrixIndex index = new DenseMatrixIndex(config(), rows(), metadata());

    assertThrows(
        IllegalArgumentException.class,
        () -> index.getNearestNeighbors(0, denseVector(1f, 0f), MetaFilter.empty()));
    assertThrows(
        IllegalArgumentException.class,
        () -> index.getSimilarRowNums(-0.1f, denseVector(1f, 0f), MetaFilter.empty()));
  }

  @Test
  void validateMatrixCellCountRejectsHugeMatrix() {
    assertThrows(
        IllegalArgumentException.class,
        () -> DenseMatrixIndex.validateMatrixCellCountForTests(46_342, 46_342));
  }

  @Test
  void validateMatrixCellCountReturnsSafeCellCount() {
    assertEquals(6L, DenseMatrixIndex.validateMatrixCellCountForTests(2, 3));
  }

  @Test
  void validateL2ComparatorTypeRejectsNonL2() throws ReflectiveOperationException {
    Method method =
        DenseMatrixIndex.class.getDeclaredMethod("validateL2ComparatorType", NamespaceConfig.class);
    method.setAccessible(true);

    ReflectiveOperationException error =
        assertThrows(
            ReflectiveOperationException.class,
            () -> method.invoke(null, configWithComparatorType("cosine")));

    assertTrue(error.getCause() instanceof IllegalArgumentException);
  }

  private static NamespaceConfig config() {
    return configWithIndexParams(Map.of());
  }

  private static NamespaceConfig configWithComparatorType(String comparatorType) {
    return NamespaceConfig.builder()
        .minTermsAndValuesLength(0)
        .maxTermsAndValuesLength(3)
        .maxCacheSize(100)
        .cacheType("generic")
        .indexType("dense")
        .comparatorType(comparatorType)
        .comparatorNormalizerType("reciprocal")
        .maxNumSearchableStructures(3)
        .maxNumSimilarities(10)
        .build();
  }

  private static NamespaceConfig configWithIndexParams(Map<String, String> indexParams) {
    return NamespaceConfig.builder()
        .minTermsAndValuesLength(0)
        .maxTermsAndValuesLength(3)
        .maxCacheSize(100)
        .cacheType("generic")
        .indexType("dense")
        .indexParams(indexParams)
        .comparatorType("l2")
        .comparatorNormalizerType("reciprocal")
        .maxNumSearchableStructures(3)
        .maxNumSimilarities(10)
        .build();
  }

  private static LongObjectHashMap<LongTermsAndValues> rows() {
    LongObjectHashMap<LongTermsAndValues> rows = longObjectMap();
    rows.put(10, denseInternal(1f, 0f));
    rows.put(11, denseInternal(0f, 1f));
    rows.put(12, denseInternal(1f, 1f));
    return rows;
  }

  private static LongObjectHashMap<LongMeta> metadata() {
    LongObjectHashMap<LongMeta> metadata = longObjectMap();
    metadata.put(10, longMeta("city", "sf"));
    metadata.put(11, longMeta("city", "la"));
    metadata.put(12, longMeta("city", "ny"));
    return metadata;
  }

  private static LongObjectHashMap<LongMeta> allSfMetadata() {
    LongObjectHashMap<LongMeta> metadata = longObjectMap();
    metadata.put(10, longMeta("city", "sf"));
    metadata.put(11, longMeta("city", "sf"));
    metadata.put(12, longMeta("city", "sf"));
    return metadata;
  }

  private static LongTermsAndValues denseInternal(float... values) {
    double uniValue = 0.0;
    for (float value : values) {
      uniValue += value * value;
    }
    return LongTermsAndValuesTestFactory.create(new long[0], values, uniValue);
  }

  private static LongTermsAndValues denseVector(float... values) {
    return denseInternal(values);
  }

  private static LongMeta longMeta(String key, String value) {
    return new LongMeta(Map.of(key, value), /* requireLongKeysAndValues */ false);
  }

  private static LongFloatHashMap rowNumToSimilarityMap(List<RowNumAndSimilarity> rows) {
    LongFloatHashMap rowNumToSimilarity = new LongFloatHashMap(rows.size());
    for (RowNumAndSimilarity row : rows) {
      rowNumToSimilarity.put(row.getRowNum(), row.getSimilarity());
    }
    return rowNumToSimilarity;
  }

  private static List<Long> sortedRowNums(List<RowNumAndSimilarity> rows) {
    return rows.stream().map(RowNumAndSimilarity::getRowNum).sorted().toList();
  }
}
