package com.uber.ussi;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.entity.meta.MetaFilter;
import com.uber.ussi.entity.termsandvalues.TermsAndValues;
import com.uber.ussi.searchablestructure.index.Index;
import com.uber.ussi.utils.Constants;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SparseEndToEndTest {
  private static final float DELTA = 1e-6f;
  private static final TermsAndValues QUERY =
      sparseVector(new String[] {"b", "d", "f"}, 0.11f, 0.11f, 0.001f);
  private static final List<Row> ROWS =
      List.of(
          new Row(
              sparseVector(new String[] {"a", "b", "c"}, 0.1f, 0.1f, 0.001f),
              Map.of("country", "USA", "city", "San Diego")),
          new Row(
              sparseVector(new String[] {"b", "c", "d"}, 0.001f, 0.5f, 0.7f),
              Map.of("country", "USA", "city", "Santa Barbara")),
          new Row(
              sparseVector(new String[] {"c", "d", "e"}, 0.001f, 0.5f, 0.6f),
              Map.of("country", "India", "city", "Mumbai")),
          new Row(
              sparseVector(new String[] {"d", "e", "f"}, 0.001f, 0.6f, 0.7f),
              Map.of("country", "Egypt", "city", "Alexandria")),
          new Row(
              sparseVector(new String[] {"e", "f", "g"}, 0.6f, 0.001f, 0.7f),
              Map.of("country", "USA", "city", "Alexandria")));
  private static final List<FilterCase> FILTER_CASES =
      List.of(
          new FilterCase(null, List.of(0L, 1L, 2L, 3L, 4L)),
          new FilterCase(new MetaFilter(Map.of("country", List.of("USA"))), List.of(0L, 1L, 4L)),
          new FilterCase(new MetaFilter(Map.of("city", List.of("Santa Barbara"))), List.of(1L)),
          new FilterCase(
              new MetaFilter(Map.of("country", List.of("India"), "city", List.of("Mumbai"))),
              List.of(2L)),
          new FilterCase(new MetaFilter(Map.of("city", List.of("Alexandria"))), List.of(3L, 4L)));

  @Test
  void portedNearestNeighborMatrixPassesThroughSparseIndex() {
    for (double maxFraction : List.of(1.0, 0.5)) {
      for (String metadataStrategy : List.of("in_filtering", "pre_filtering", "auto")) {
        try (NearestNeighborSearchIndex index =
            buildGraduatedIndex(config(ROWS.size(), maxFraction, metadataStrategy))) {
          List<ExpectedHit> allExpectedHits = expectedHits(maxFraction);
          for (FilterCase filterCase : FILTER_CASES) {
            List<ExpectedHit> filteredHits =
                restrictToRows(allExpectedHits, filterCase.expectedRowNums());
            for (int k = 1; k <= ROWS.size(); ++k) {
              assertResults(
                  filteredHits.subList(0, Math.min(k, filteredHits.size())),
                  index.getNearestNeighbors(k, QUERY, filterCase.filter()));
            }
          }
        }
      }
    }
  }

  @Test
  void portedSimilarityThresholdMatrixPassesThroughSparseIndex() {
    for (double maxFraction : List.of(1.0, 0.5)) {
      for (String metadataStrategy : List.of("in_filtering", "pre_filtering", "auto")) {
        try (NearestNeighborSearchIndex index =
            buildGraduatedIndex(config(ROWS.size(), maxFraction, metadataStrategy))) {
          List<ExpectedHit> allExpectedHits = expectedHits(maxFraction);
          for (FilterCase filterCase : FILTER_CASES) {
            List<ExpectedHit> filteredHits =
                restrictToRows(allExpectedHits, filterCase.expectedRowNums());
            for (float minSimilarity : new float[] {1.0f, 0.5f, 0.2f}) {
              assertResults(
                  filteredHits.stream().filter(hit -> hit.similarity() >= minSimilarity).toList(),
                  index.getSimilarRowNums(minSimilarity, QUERY, filterCase.filter()));
            }
          }
        }
      }
    }
  }

  @Test
  void sparseResultsRemainStableAcrossCacheGraduationAndMerging() {
    List<ExpectedHit> expectedHits = expectedHits(1.0);
    NamespaceConfig config = config(ROWS.size(), 1.0, "auto");
    try (NearestNeighborSearchIndex index = NearestNeighborSearchIndex.create(config)) {
      insertRows(index, ROWS.subList(0, ROWS.size() - 1));
      assertResults(expectedHits.subList(0, 4), index.getNearestNeighbors(5, QUERY, null));

      insertRows(index, ROWS.subList(ROWS.size() - 1, ROWS.size()));
      assertResults(expectedHits, index.getNearestNeighbors(5, QUERY, null));
      index.awaitBackgroundTasks();
      assertResults(expectedHits, index.getNearestNeighbors(5, QUERY, null));
    }

    try (NearestNeighborSearchIndex index =
        NearestNeighborSearchIndex.create(config(3, 1.0, "auto"))) {
      insertRows(index, ROWS.subList(0, 3));
      index.awaitBackgroundTasks();
      insertRows(index, ROWS.subList(3, ROWS.size()));

      assertResults(expectedHits, index.getNearestNeighbors(5, QUERY, null));
      assertResults(
          expectedHits.stream().filter(hit -> hit.similarity() >= 0.5f).toList(),
          index.getSimilarRowNums(0.5f, QUERY, null));
    }
  }

  private static NearestNeighborSearchIndex buildGraduatedIndex(NamespaceConfig config) {
    NearestNeighborSearchIndex index = NearestNeighborSearchIndex.create(config);
    insertRows(index, ROWS);
    index.awaitBackgroundTasks();
    return index;
  }

  private static void insertRows(NearestNeighborSearchIndex index, List<Row> rows) {
    for (Row row : rows) {
      index.insert(row.termsAndValues(), row.metadata());
    }
  }

  private static NamespaceConfig config(
      int maxCacheSize, double maxFraction, String metadataStrategy) {
    Map<String, String> popularityParams =
        Map.of(
            Constants.MAX_FRACTION_IDS_PER_SPARSE_KEY,
            Double.toString(maxFraction),
            Constants.MAX_FRACTION_IDS_PER_SPARSE_KEY_CONFIDENCE,
            "0.5");
    Map<String, String> indexParams =
        Map.of(
            Constants.MAX_FRACTION_IDS_PER_SPARSE_KEY,
            Double.toString(maxFraction),
            Index.METADATA_FILTERING_STRATEGY,
            metadataStrategy,
            Index.MAX_PRE_FILTERING_ROWS_RATIO,
            metadataStrategy.equals("auto") ? "0.4" : "1.0");
    return NamespaceConfig.builder()
        .minTermsAndValuesLength(0)
        .maxTermsAndValuesLength(3)
        .maxCacheSize(maxCacheSize)
        .cacheType("sparse")
        .cacheParams(popularityParams)
        .indexType("inverted")
        .indexParams(indexParams)
        .comparatorType("l2")
        .comparatorNormalizerType("reciprocal")
        .maxNumSearchableStructures(3)
        .maxNumSimilarities(10)
        .build();
  }

  private static List<ExpectedHit> expectedHits(double maxFraction) {
    if (maxFraction == 0.5) {
      return List.of(
          new ExpectedHit(0, 0.9086748f),
          new ExpectedHit(1, 0.9017095f),
          new ExpectedHit(3, 0.58561647f),
          new ExpectedHit(4, 0.58527786f));
    }
    return List.of(
        new ExpectedHit(0, 0.8703195f),
        new ExpectedHit(2, 0.58004034f),
        new ExpectedHit(1, 0.5614781f),
        new ExpectedHit(3, 0.5170307f),
        new ExpectedHit(4, 0.51679945f));
  }

  private static List<ExpectedHit> restrictToRows(
      List<ExpectedHit> hits, List<Long> expectedRowNums) {
    List<ExpectedHit> filtered = new ArrayList<>();
    for (ExpectedHit hit : hits) {
      if (expectedRowNums.contains(hit.rowNum())) {
        filtered.add(hit);
      }
    }
    return filtered;
  }

  private static void assertResults(List<ExpectedHit> expected, SearchResults actual) {
    assertEquals(expected.size(), actual.size(), actual.toString());
    for (int i = 0; i < expected.size(); ++i) {
      assertEquals(expected.get(i).rowNum(), actual.getRowNum(i), actual.toString());
      assertEquals(expected.get(i).similarity(), actual.getSimilarity(i), DELTA, actual.toString());
    }
  }

  private static TermsAndValues sparseVector(String[] terms, float... values) {
    return new TermsAndValues(terms, values);
  }

  private record Row(TermsAndValues termsAndValues, Map<String, String> metadata) {}

  private record FilterCase(MetaFilter filter, List<Long> expectedRowNums) {}

  private record ExpectedHit(long rowNum, float similarity) {}
}
