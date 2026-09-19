package com.uber.ussi.searchablestructure.index.scan;

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
import com.uber.ussi.searchablestructure.result.RowNumAndSimilarity;
import com.uber.ussi.searchablestructure.index.Index;
import com.uber.ussi.searchablestructure.utils.metadata.MetadataFilteringStrategy;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ScanIndexTest {

  private static final float DELTA = 1e-6f;

  @Test
  void constructorBuildsIndexFromExistingRows() {
    ScanIndex index = new ScanIndex(config(), rows(), metadata());

    assertEquals(3, index.size());
    assertTrue(index.getAll().containsKey(10));
    assertTrue(index.getAll().containsKey(11));
    assertTrue(index.getAll().containsKey(12));
  }

  @Test
  void emptyIndexSearchReturnsEmptyResult() {
    ScanIndex index = new ScanIndex(config(), longObjectMap(), longObjectMap());

    assertTrue(index.getNearestNeighborRowNums(1, denseVector(1f, 0f), MetaFilter.empty()).isEmpty());
  }

  @Test
  void getNearestNeighborRowNumsKeepsMostSimilarRows() {
    ScanIndex index = new ScanIndex(config(), rows(), metadata());

    List<RowNumAndSimilarity> result =
        index.getNearestNeighborRowNums(2, denseVector(1f, 0f), MetaFilter.empty());

    LongFloatHashMap rowNumToSimilarity = rowNumToSimilarityMap(result);
    assertEquals(2, result.size());
    assertTrue(rowNumToSimilarity.containsKey(10));
    assertTrue(rowNumToSimilarity.containsKey(12));
    assertEquals(1.0f, rowNumToSimilarity.get(10), DELTA);
    assertEquals(0.5f, rowNumToSimilarity.get(12), DELTA);
  }

  @Test
  void getNearestNeighborRowNumsBreaksSimilarityTiesByRowNumAscending() {
    LongObjectHashMap<LongTermsAndValues> rows = longObjectMap();
    rows.put(31, denseInternal(1f, 0f));
    rows.put(30, denseInternal(1f, 0f));
    rows.put(32, denseInternal(0f, 1f));
    ScanIndex index = new ScanIndex(config(), rows, longObjectMap());

    List<RowNumAndSimilarity> result =
        index.getNearestNeighborRowNums(1, denseVector(1f, 0f), MetaFilter.empty());

    assertEquals(List.of(30L), result.stream().map(row -> row.getRowNum()).toList());
  }

  @Test
  void getSimilarRowNumsRespectsMinSimilarity() {
    ScanIndex index = new ScanIndex(config(), rows(), metadata());

    List<RowNumAndSimilarity> result =
        index.getSimilarRowNums(0.5f, denseVector(1f, 0f), MetaFilter.empty());

    assertEquals(List.of(10L, 12L), sortedRowNums(result));
  }

  @Test
  void metadataFilterRestrictsResults() {
    ScanIndex index = new ScanIndex(config(), rows(), metadata());

    List<RowNumAndSimilarity> result =
        index.getNearestNeighborRowNums(
            10, denseVector(1f, 0f), new MetaFilter(Map.of("city", List.of("sf"))));

    assertEquals(List.of(10L), result.stream().map(row -> row.getRowNum()).toList());
  }

  @Test
  void autoMetadataFilteringUsesInFilteringWhenIndexSupportsInFiltering() {
    ScanIndex index =
        new ScanIndex(
            configWithIndexParams(Map.of(Index.MAX_PRE_FILTERING_ROWS_RATIO, "0.34")),
            rows(),
            metadata());

    List<RowNumAndSimilarity> result =
        index.getNearestNeighborRowNums(
            10, denseVector(1f, 0f), new MetaFilter(Map.of("city", List.of("sf"))));

    assertEquals(List.of(10L), result.stream().map(row -> row.getRowNum()).toList());
    assertEquals(
        MetadataFilteringStrategy.IN_FILTERING,
        index.getResolvedMetadataFilteringStrategyForLastSearchForTests());
  }

  @Test
  void preFilteringStrategyUsesPreFilteringWhenFilterIsSelective() {
    ScanIndex index =
        new ScanIndex(
            configWithIndexParams(
                Map.of(
                    Index.METADATA_FILTERING_STRATEGY,
                    "pre_filtering",
                    Index.MAX_PRE_FILTERING_ROWS_RATIO,
                    "0.34")),
            rows(),
            metadata());

    List<RowNumAndSimilarity> result =
        index.getNearestNeighborRowNums(
            10, denseVector(1f, 0f), new MetaFilter(Map.of("city", List.of("sf"))));

    assertEquals(List.of(10L), result.stream().map(row -> row.getRowNum()).toList());
    assertEquals(
        MetadataFilteringStrategy.PRE_FILTERING,
        index.getResolvedMetadataFilteringStrategyForLastSearchForTests());
  }

  @Test
  void preFilteringStrategyFallsBackToInFilteringWhenLimitIsExceeded() {
    ScanIndex index =
        new ScanIndex(
            configWithIndexParams(
                Map.of(
                    Index.METADATA_FILTERING_STRATEGY,
                    "pre_filtering",
                    Index.MAX_PRE_FILTERING_ROWS_RATIO,
                    "0.34")),
            rows(),
            metadata());

    List<RowNumAndSimilarity> result =
        index.getNearestNeighborRowNums(
            10, denseVector(1f, 0f), new MetaFilter(Map.of("city", List.of("sf", "la"))));

    assertEquals(List.of(10L, 11L), sortedRowNums(result));
    assertEquals(
        MetadataFilteringStrategy.IN_FILTERING,
        index.getResolvedMetadataFilteringStrategyForLastSearchForTests());
  }

  @Test
  void metadataFilteringStrategyCanBeForcedToPostFiltering() {
    ScanIndex index =
        new ScanIndex(
            configWithIndexParams(Map.of(Index.METADATA_FILTERING_STRATEGY, "post_filtering")),
            rows(),
            metadata());

    List<RowNumAndSimilarity> result =
        index.getNearestNeighborRowNums(
            10, denseVector(1f, 0f), new MetaFilter(Map.of("city", List.of("sf"))));

    assertEquals(List.of(10L), result.stream().map(row -> row.getRowNum()).toList());
    assertEquals(
        MetadataFilteringStrategy.POST_FILTERING,
        index.getResolvedMetadataFilteringStrategyForLastSearchForTests());
  }

  @Test
  void postFilteringExpandsSearchPoolToFindMatchesNaiveTopKWouldMiss() {
    LongObjectHashMap<LongTermsAndValues> rows = longObjectMap();
    rows.put(20, denseInternal(1f, 0f)); // closest to the query, city=sf (filtered out)
    rows.put(21, denseInternal(1f, 1f)); // middle, city=sf (filtered out)
    rows.put(22, denseInternal(0f, 1f)); // farthest, city=la (the only match)
    LongObjectHashMap<LongMeta> metadata = longObjectMap();
    metadata.put(20, longMeta("city", "sf"));
    metadata.put(21, longMeta("city", "sf"));
    metadata.put(22, longMeta("city", "la"));
    ScanIndex index =
        new ScanIndex(
            configWithIndexParams(Map.of(Index.METADATA_FILTERING_STRATEGY, "post_filtering")),
            rows,
            metadata);

    // k=1: the only matching row (22) is the least similar, so the expansion
    // (size/numMatching = 3) must widen the pool to reach it.
    List<RowNumAndSimilarity> result =
        index.getNearestNeighborRowNums(
            1, denseVector(1f, 0f), new MetaFilter(Map.of("city", List.of("la"))));

    assertEquals(List.of(22L), result.stream().map(row -> row.getRowNum()).toList());
    assertEquals(
        MetadataFilteringStrategy.POST_FILTERING,
        index.getResolvedMetadataFilteringStrategyForLastSearchForTests());
  }

  @Test
  void postFilteringReturnsEmptyWhenNoMetadataMatches() {
    ScanIndex index =
        new ScanIndex(
            configWithIndexParams(Map.of(Index.METADATA_FILTERING_STRATEGY, "post_filtering")),
            rows(),
            metadata());

    List<RowNumAndSimilarity> result =
        index.getNearestNeighborRowNums(
            1, denseVector(1f, 0f), new MetaFilter(Map.of("city", List.of("missing"))));

    assertTrue(result.isEmpty());
    assertEquals(
        MetadataFilteringStrategy.POST_FILTERING,
        index.getResolvedMetadataFilteringStrategyForLastSearchForTests());
  }

  @Test
  void deleteRemovesRowsFromSearchAndGetAll() {
    ScanIndex index = new ScanIndex(config(), rows(), metadata());

    assertTrue(index.delete(10));
    assertFalse(index.delete(10));
    assertFalse(index.getAll().containsKey(10));

    List<RowNumAndSimilarity> result =
        index.getNearestNeighborRowNums(10, denseVector(1f, 0f), MetaFilter.empty());
    assertEquals(List.of(11L, 12L), sortedRowNums(result));
  }

  @Test
  void invalidSearchArgumentsThrow() {
    ScanIndex index = new ScanIndex(config(), rows(), metadata());

    assertThrows(
        IllegalArgumentException.class,
        () -> index.getNearestNeighborRowNums(0, denseVector(1f, 0f), MetaFilter.empty()));
    assertThrows(
        IllegalArgumentException.class,
        () -> index.getSimilarRowNums(-0.1f, denseVector(1f, 0f), MetaFilter.empty()));
    assertThrows(
        IllegalArgumentException.class,
        () -> index.getSimilarRowNums(1.1f, denseVector(1f, 0f), MetaFilter.empty()));
  }

  @Test
  void invalidMetadataFilteringIndexParamsThrow() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ScanIndex(
                configWithIndexParams(Map.of(Index.MAX_PRE_FILTERING_ROWS_RATIO, "1.5")),
                rows(),
                metadata()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ScanIndex(
                configWithIndexParams(Map.of(Index.METADATA_FILTERING_STRATEGY, "not_real")),
                rows(),
                metadata()));
  }

  /**
   * Jaccard and Ruzicka read dense records as well as sparse ones, and scan is the only structure
   * that can hold a dense record for them: the inverted structures key by terms a dense record has
   * none of, and the matrix structure takes l2 alone. A dense record addresses its coordinates by
   * position rather than by a term, so what these two measure is the positions a pair populates.
   */
  @Test
  void jaccardAndRuzickaScoreDenseVectorsByPosition() {
    LongObjectHashMap<LongTermsAndValues> jaccardRows = longObjectMap();
    jaccardRows.put(1, jaccardDense(1f, 0f, 1f));
    jaccardRows.put(2, jaccardDense(1f, 1f, 1f));
    ScanIndex jaccardIndex = new ScanIndex(denseConfig("jaccard"), jaccardRows, longObjectMap());

    LongFloatHashMap jaccard =
        rowNumToSimilarityMap(
            jaccardIndex.getSimilarRowNums(0.0f, jaccardDense(1f, 1f, 0f), MetaFilter.empty()));
    // Jaccard counts a populated position once however large its value, so row 1 populates
    // {0, 2} against the query's {0, 1}: one position of the three either holds.
    assertEquals(1.0f / 3.0f, jaccard.get(1), DELTA);
    // Row 2 populates every position, so it covers both of the query's two.
    assertEquals(2.0f / 3.0f, jaccard.get(2), DELTA);

    LongObjectHashMap<LongTermsAndValues> ruzickaRows = longObjectMap();
    ruzickaRows.put(1, ruzickaDense(2f, 0f, 1f));
    ruzickaRows.put(2, ruzickaDense(1f, 1f, 1f));
    ScanIndex ruzickaIndex = new ScanIndex(denseConfig("ruzicka"), ruzickaRows, longObjectMap());

    LongFloatHashMap ruzicka =
        rowNumToSimilarityMap(
            ruzickaIndex.getSimilarRowNums(0.0f, ruzickaDense(1f, 1f, 0f), MetaFilter.empty()));
    // Ruzicka weighs each position, so row 1's larger first value widens the union without
    // adding to the intersection: min(2,1) + 0 + 0 over max(2,1) + 1 + 1.
    assertEquals(0.25f, ruzicka.get(1), DELTA);
    assertEquals(2.0f / 3.0f, ruzicka.get(2), DELTA);
  }

  private static NamespaceConfig config() {
    return configWithIndexParams(Map.of());
  }

  private static NamespaceConfig configWithIndexParams(Map<String, String> indexParams) {
    return NamespaceConfig.builder()
        .minTermsAndValuesLength(0)
        .maxTermsAndValuesLength(2)
        .maxCacheSize(100)
        .cacheType("scan")
        .indexType("scan")
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

  /** A config for dense records wider than the two dimensions the l2 rows above use. */
  private static NamespaceConfig denseConfig(String comparatorType) {
    return NamespaceConfig.builder()
        .minTermsAndValuesLength(0)
        .maxTermsAndValuesLength(3)
        .maxCacheSize(100)
        .cacheType("scan")
        .indexType("scan")
        .comparatorType(comparatorType)
        .comparatorNormalizerType("identity")
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
