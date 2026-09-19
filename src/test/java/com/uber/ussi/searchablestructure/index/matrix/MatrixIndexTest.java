package com.uber.ussi.searchablestructure.index.matrix;

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

class MatrixIndexTest {
  private static final float DELTA = 1e-6f;
  // The squared-norm expansion loses precision in proportion to the number of dimensions
  // summed, so these rows carry the length a dense embedding actually has.
  private static final int HIGH_DIMENSION = 512;
  private static final float NEAR_DUPLICATE_SEPARATION = 1e-3f;

  @Test
  void constructorMaterializesDenseRows() {
    MatrixIndex index = new MatrixIndex(config(), rows(), metadata());

    assertEquals(3, index.size());
    assertEquals(2, index.getDimensionForTests());
    assertTrue(index.getAll().containsKey(10));
    assertTrue(index.getAll().containsKey(11));
    assertTrue(index.getAll().containsKey(12));
  }

  @Test
  void closeCanBeCalled() {
    MatrixIndex index = new MatrixIndex(config(), rows(), metadata());

    index.close();
  }

  @Test
  void emptyIndexSearchReturnsEmptyResult() {
    MatrixIndex index = new MatrixIndex(config(), longObjectMap(), longObjectMap());

    assertTrue(index.getNearestNeighborRowNums(1, denseVector(1f, 0f), MetaFilter.empty()).isEmpty());
  }

  @Test
  void getNearestNeighborRowNumsKeepsMostSimilarRows() {
    MatrixIndex index = new MatrixIndex(config(), rows(), metadata());

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
    MatrixIndex index = new MatrixIndex(config(), rows, longObjectMap());

    List<RowNumAndSimilarity> result =
        index.getNearestNeighborRowNums(1, denseVector(1f, 0f), MetaFilter.empty());

    assertEquals(List.of(30L), result.stream().map(row -> row.getRowNum()).toList());
  }

  @Test
  void getSimilarRowNumsRespectsMinSimilarity() {
    MatrixIndex index = new MatrixIndex(config(), rows(), metadata());

    List<RowNumAndSimilarity> result =
        index.getSimilarRowNums(0.5f, denseVector(1f, 0f), MetaFilter.empty());

    assertEquals(List.of(10L, 12L), sortedRowNums(result));
  }

  @Test
  void autoMetadataFilteringUsesPreFilteringWhenFilterIsSelective() {
    MatrixIndex index =
        new MatrixIndex(
            configWithIndexParams(Map.of(Index.MAX_PRE_FILTERING_ROWS_RATIO, "0.34")),
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
  void autoMetadataFilteringUsesPostFilteringWhenFilterIsNotSelective() {
    MatrixIndex index =
        new MatrixIndex(
            configWithIndexParams(Map.of(Index.MAX_PRE_FILTERING_ROWS_RATIO, "0.34")),
            rows(),
            metadata());

    List<RowNumAndSimilarity> result =
        index.getNearestNeighborRowNums(
            10, denseVector(1f, 0f), new MetaFilter(Map.of("city", List.of("sf", "la"))));

    assertEquals(List.of(10L, 11L), sortedRowNums(result));
    assertEquals(
        MetadataFilteringStrategy.POST_FILTERING,
        index.getResolvedMetadataFilteringStrategyForLastSearchForTests());
  }

  @Test
  void inFilteringStrategyFiltersDuringMatrixScanAndSkipsDeletedRows() {
    MatrixIndex index =
        new MatrixIndex(
            configWithIndexParams(Map.of(Index.METADATA_FILTERING_STRATEGY, "in_filtering")),
            rows(),
            metadata());

    assertTrue(index.delete(10));
    List<RowNumAndSimilarity> result =
        index.getNearestNeighborRowNums(
            10, denseVector(1f, 0f), new MetaFilter(Map.of("city", List.of("sf"))));

    assertTrue(result.isEmpty());
    assertEquals(
        MetadataFilteringStrategy.IN_FILTERING,
        index.getResolvedMetadataFilteringStrategyForLastSearchForTests());
  }

  @Test
  void inFilteringStrategyCropsFilteredRowsToMaxResults() {
    MatrixIndex index =
        new MatrixIndex(
            configWithIndexParams(Map.of(Index.METADATA_FILTERING_STRATEGY, "in_filtering")),
            rows(),
            allSfMetadata());

    List<RowNumAndSimilarity> result =
        index.getNearestNeighborRowNums(
            1, denseVector(1f, 0f), new MetaFilter(Map.of("city", List.of("sf"))));

    assertEquals(List.of(10L), result.stream().map(row -> row.getRowNum()).toList());
    assertEquals(
        MetadataFilteringStrategy.IN_FILTERING,
        index.getResolvedMetadataFilteringStrategyForLastSearchForTests());
  }

  @Test
  void inFilteringDistinguishesHighDimensionalNearDuplicates() {
    float[] rowValues = unitVector();
    float[] nearRowValues = rowValues.clone();
    nearRowValues[0] += NEAR_DUPLICATE_SEPARATION;

    LongObjectHashMap<LongTermsAndValues> rows = longObjectMap();
    rows.put(10, denseInternal(rowValues));
    rows.put(11, denseInternal(nearRowValues));
    LongObjectHashMap<LongMeta> metadata = longObjectMap();
    metadata.put(10, longMeta("city", "sf"));
    metadata.put(11, longMeta("city", "sf"));
    MatrixIndex index = new MatrixIndex(highDimensionalInFilteringConfig(), rows, metadata);

    List<RowNumAndSimilarity> result =
        index.getNearestNeighborRowNums(
            2, denseVector(rowValues), new MetaFilter(Map.of("city", List.of("sf"))));

    LongFloatHashMap rowNumToSimilarity = rowNumToSimilarityMap(result);
    assertEquals(1.0f, rowNumToSimilarity.get(10), DELTA);
    assertEquals(
        expectedSimilarity(rowValues, nearRowValues), rowNumToSimilarity.get(11), DELTA);
  }

  @Test
  void preFilteringStrategyCropsCandidateRowsToMaxResults() {
    MatrixIndex index =
        new MatrixIndex(
            configWithIndexParams(
                Map.of(
                    Index.METADATA_FILTERING_STRATEGY,
                    "pre_filtering",
                    Index.MAX_PRE_FILTERING_ROWS_RATIO,
                    "1.0")),
            rows(),
            allSfMetadata());

    List<RowNumAndSimilarity> result =
        index.getNearestNeighborRowNums(
            1, denseVector(1f, 0f), new MetaFilter(Map.of("city", List.of("sf"))));

    assertEquals(List.of(10L), result.stream().map(row -> row.getRowNum()).toList());
    assertEquals(
        MetadataFilteringStrategy.PRE_FILTERING,
        index.getResolvedMetadataFilteringStrategyForLastSearchForTests());
  }

  @Test
  void preFilteringStrategyFallsBackToPostFilteringWhenLimitIsExceeded() {
    MatrixIndex index =
        new MatrixIndex(
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
    MatrixIndex index =
        new MatrixIndex(
            configWithIndexParams(Map.of(Index.METADATA_FILTERING_STRATEGY, "post_filtering")),
            rows,
            metadata);

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
    MatrixIndex index =
        new MatrixIndex(
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
    MatrixIndex index = new MatrixIndex(config(), rows(), metadata());

    assertTrue(index.delete(10));
    assertFalse(index.delete(10));
    assertFalse(index.getAll().containsKey(10));

    List<RowNumAndSimilarity> result =
        index.getNearestNeighborRowNums(10, denseVector(1f, 0f), MetaFilter.empty());
    assertEquals(List.of(11L, 12L), sortedRowNums(result));
  }

  /**
   * The bulk multiply scores every row of the matrix, including a deleted one, so exclusion rests
   * on the similarity derived for a deleted row rather than on a test the search performs.
   */
  @Test
  void deletedRowsAreExcludedFromTheBulkMultiply() {
    MatrixIndex index = new MatrixIndex(config(), rows(), metadata());

    assertTrue(index.delete(12));
    List<RowNumAndSimilarity> result =
        index.getNearestNeighborRowNums(10, denseVector(1f, 1f), /* metadataFilter */ null);

    assertEquals(List.of(10L, 11L), sortedRowNums(result));
    for (RowNumAndSimilarity row : result) {
      assertFalse(
          Float.isNaN(row.getSimilarity()), "a kept row must carry a similarity that is a number");
    }
  }

  /** A row deleted after others were deleted is excluded too, so the mark is per row. */
  @Test
  void deletingSeveralRowsExcludesAllOfThem() {
    MatrixIndex index = new MatrixIndex(config(), rows(), metadata());

    assertTrue(index.delete(10));
    assertTrue(index.delete(12));
    List<RowNumAndSimilarity> result =
        index.getNearestNeighborRowNums(10, denseVector(1f, 1f), /* metadataFilter */ null);

    assertEquals(List.of(11L), sortedRowNums(result));
    assertEquals(1, index.size());
  }

  @Test
  void constructorRejectsNonDenseRows() {
    LongObjectHashMap<LongTermsAndValues> rows = longObjectMap();
    rows.put(1, LongTermsAndValuesTestFactory.create(new long[] {7L}, new float[] {1f}, 1.0d));

    assertThrows(
        IllegalArgumentException.class, () -> new MatrixIndex(config(), rows, longObjectMap()));
  }

  @Test
  void constructorRejectsRowsWithoutValues() {
    LongObjectHashMap<LongTermsAndValues> rows = longObjectMap();
    rows.put(1, LongTermsAndValuesTestFactory.create(new long[0], new float[0], 0.0d));

    assertThrows(
        IllegalArgumentException.class, () -> new MatrixIndex(config(), rows, longObjectMap()));
  }

  @Test
  void constructorRejectsMismatchedDimensions() {
    LongObjectHashMap<LongTermsAndValues> rows = longObjectMap();
    rows.put(1, denseInternal(1f, 0f));
    rows.put(2, denseInternal(1f, 0f, 0f));

    assertThrows(
        IllegalArgumentException.class, () -> new MatrixIndex(config(), rows, longObjectMap()));
  }

  @Test
  void queryRejectsMismatchedDimensions() {
    MatrixIndex index = new MatrixIndex(config(), rows(), metadata());

    assertThrows(
        IllegalArgumentException.class,
        () -> index.getNearestNeighborRowNums(1, denseVector(1f, 0f, 0f), MetaFilter.empty()));
  }

  @Test
  void queryRejectsNullAndTerms() {
    MatrixIndex index = new MatrixIndex(config(), rows(), metadata());

    assertThrows(
        NullPointerException.class, () -> index.getNearestNeighborRowNums(1, null, MetaFilter.empty()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            index.getNearestNeighborRowNums(
                1,
                LongTermsAndValuesTestFactory.create(new long[] {7L}, new float[] {1f}, 1.0d),
                MetaFilter.empty()));
  }

  @Test
  void invalidSearchArgumentsThrow() {
    MatrixIndex index = new MatrixIndex(config(), rows(), metadata());

    assertThrows(
        IllegalArgumentException.class,
        () -> index.getNearestNeighborRowNums(0, denseVector(1f, 0f), MetaFilter.empty()));
    assertThrows(
        IllegalArgumentException.class,
        () -> index.getSimilarRowNums(-0.1f, denseVector(1f, 0f), MetaFilter.empty()));
  }

  @Test
  void constructorRejectsAComparatorThatCannotReadDenseRecords() {
    NamespaceConfig config = configWithComparatorType("jaccard");

    assertThrows(
        IllegalArgumentException.class,
        () -> new MatrixIndex(config, longObjectMap(), longObjectMap()));
  }

  private static NamespaceConfig config() {
    return configWithIndexParams(Map.of());
  }

  /**
   * A namespace holding more values than one array can is scored chunk by chunk. The chunk size is
   * injected because a matrix that large cannot be built in a test.
   */
  @Test
  void searchesAMatrixSplitAcrossChunksExactlyAsOneChunk() {
    // Two values per row, so three rows need two chunks at three values each.
    MatrixIndex chunked = new MatrixIndex(config(), rows(), metadata(), /* maxChunkValues */ 3);
    MatrixIndex single = new MatrixIndex(config(), rows(), metadata());

    for (float[] query : new float[][] {{1f, 0f}, {0f, 1f}, {1f, 1f}, {0.25f, 0.75f}}) {
      List<RowNumAndSimilarity> fromChunked =
          chunked.getNearestNeighborRowNums(3, denseVector(query), MetaFilter.empty());
      List<RowNumAndSimilarity> fromSingle =
          single.getNearestNeighborRowNums(3, denseVector(query), MetaFilter.empty());

      assertEquals(sortedRowNums(fromSingle), sortedRowNums(fromChunked));
      assertEquals(fromSingle.size(), fromChunked.size());
      for (int i = 0; i < fromSingle.size(); ++i) {
        assertEquals(
            similarityOf(fromSingle, fromSingle.get(i).getRowNum()),
            similarityOf(fromChunked, fromSingle.get(i).getRowNum()),
            DELTA,
            "row " + fromSingle.get(i).getRowNum());
      }
    }
  }

  /** The filtered path scores one row at a time, so it indexes into the chunks separately. */
  @Test
  void scoresASingleRowFromAChunkedMatrix() {
    MatrixIndex chunked =
        new MatrixIndex(config(), rows(), allSfMetadata(), /* maxChunkValues */ 3);
    MatrixIndex single = new MatrixIndex(config(), rows(), allSfMetadata());
    MetaFilter onlySf = new MetaFilter(Map.of("city", List.of("sf")));

    List<RowNumAndSimilarity> fromChunked =
        chunked.getNearestNeighborRowNums(3, denseVector(1f, 1f), onlySf);
    List<RowNumAndSimilarity> fromSingle =
        single.getNearestNeighborRowNums(3, denseVector(1f, 1f), onlySf);

    assertEquals(sortedRowNums(fromSingle), sortedRowNums(fromChunked));
  }

  private static float similarityOf(List<RowNumAndSimilarity> results, long rowNum) {
    return results.stream()
        .filter(result -> result.getRowNum() == rowNum)
        .findFirst()
        .orElseThrow()
        .getSimilarity();
  }

  private static NamespaceConfig configWithComparatorType(String comparatorType) {
    return NamespaceConfig.builder()
        .minTermsAndValuesLength(0)
        .maxTermsAndValuesLength(3)
        .maxCacheSize(100)
        .cacheType("scan")
        .indexType("matrix")
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
        .cacheType("scan")
        .indexType("matrix")
        .indexParams(indexParams)
        .comparatorType("l2")
        .comparatorNormalizerType("reciprocal")
        .maxNumSearchableStructures(3)
        .maxNumSimilarities(10)
        .build();
  }

  private static NamespaceConfig highDimensionalInFilteringConfig() {
    return NamespaceConfig.builder()
        .minTermsAndValuesLength(0)
        .maxTermsAndValuesLength(HIGH_DIMENSION)
        .maxCacheSize(100)
        .cacheType("scan")
        .indexType("matrix")
        .indexParams(Map.of(Index.METADATA_FILTERING_STRATEGY, "in_filtering"))
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

  private static float[] unitVector() {
    float[] values = new float[HIGH_DIMENSION];
    double squaredNorm = 0.0;
    for (int i = 0; i < HIGH_DIMENSION; ++i) {
      values[i] = (float) Math.sin(i * 0.013d);
      squaredNorm += (double) values[i] * values[i];
    }
    float norm = (float) Math.sqrt(squaredNorm);
    for (int i = 0; i < HIGH_DIMENSION; ++i) {
      values[i] /= norm;
    }
    return values;
  }

  /** Scores a pair through the distance definition directly, without the squared-norm expansion. */
  private static float expectedSimilarity(float[] queryValues, float[] rowValues) {
    double squaredDistance = 0.0;
    for (int i = 0; i < queryValues.length; ++i) {
      double difference = (double) queryValues[i] - rowValues[i];
      squaredDistance += difference * difference;
    }
    return (float) (1.0 / (1.0 + Math.sqrt(squaredDistance)));
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
