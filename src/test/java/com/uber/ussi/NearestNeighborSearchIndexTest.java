package com.uber.ussi;

import static com.uber.ussi.TestLongObjectMaps.rowNums;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carrotsearch.hppc.LongObjectHashMap;
import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.entity.meta.LongMeta;
import com.uber.ussi.entity.meta.MetaFilter;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;
import com.uber.ussi.entity.termsandvalues.TermsAndValues;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class NearestNeighborSearchIndexTest {

  @Test
  void createBuildsEmptyIndexFromConfig() {
    NearestNeighborSearchIndex index = NearestNeighborSearchIndex.create(config());

    assertEquals(0, index.size());
    assertEquals(0, index.getAllRowNums().length);
  }

  @Test
  void insertSearchUpdateAndDeleteThroughTopLevelApi() {
    NearestNeighborSearchIndex index = NearestNeighborSearchIndex.create(config());
    long sf = index.insert(denseVector(1f, 0f), Map.of("city", "sf"));
    long la = index.insert(denseVector(0f, 1f), Map.of("city", "la"));

    SearchResults sfResult =
        index.getNearestNeighbors(
            10, denseVector(1f, 0f), new MetaFilter(Map.of("city", List.of("sf"))));
    assertEquals(List.of(sf), rowNums(sfResult));

    assertTrue(index.update(la, denseVector(1f, 0f), Map.of("city", "sf")));
    SearchResults updatedResult =
        index.getNearestNeighbors(
            10, denseVector(1f, 0f), new MetaFilter(Map.of("city", List.of("sf"))));
    assertEquals(List.of(sf, la), rowNums(updatedResult));

    assertTrue(index.delete(sf));
    assertFalse(index.delete(sf));
    SearchResults afterDelete =
        index.getNearestNeighbors(
            10, denseVector(1f, 0f), new MetaFilter(Map.of("city", List.of("sf"))));
    assertEquals(List.of(la), rowNums(afterDelete));
  }

  @Test
  void readinessForGraduationUsesCacheThreshold() {
    NearestNeighborSearchIndex index = NearestNeighborSearchIndex.create(configWithMaxCacheSize(1));

    assertFalse(index.isReadyForGraduation());
    long rowNum = index.insert(denseVector(1f, 0f), Map.of());

    assertFalse(index.isReadyForGraduation());
    assertEquals(List.of(rowNum), rowNums(index.getAllRowNums()));
  }

  @Test
  void insertConvertsStringTermsToLowerCaseLongTerms() throws ReflectiveOperationException {
    NearestNeighborSearchIndex index = NearestNeighborSearchIndex.create(config());

    long rowNum =
        index.insert(new TermsAndValues(new String[] {"Term"}, new float[] {2f}), Map.of());
    LongObjectHashMap<LongTermsAndValues> rows = getAllInternal(index);

    assertEquals(LongMeta.longHashCode("term"), rows.get(rowNum).getTerm(0));
    assertEquals(4.0d, rows.get(rowNum).getUniValue());
  }

  @Test
  void sparseIndexesSearchCanonicalRecordsAfterGraduation() {
    try (NearestNeighborSearchIndex index =
        NearestNeighborSearchIndex.create(sparseConfigWithMaxCacheSize(1))) {
      long exact =
          index.insert(
              new TermsAndValues(
                  new String[] {"second", "first", "first"}, new float[] {1f, 0.5f, 0.5f}),
              Map.of());
      long partial =
          index.insert(
              new TermsAndValues(new String[] {"first", "third"}, new float[] {1f, 1f}), Map.of());
      index.awaitBackgroundTasks();

      SearchResults results =
          index.getNearestNeighbors(
              2, new TermsAndValues(new String[] {"first", "second"}, new float[] {1f, 1f}), null);

      assertEquals(List.of(exact, partial), rowNums(results));
      assertEquals(1.0f, results.getSimilarity(0));
      assertEquals(1.0f / 3.0f, results.getSimilarity(1));
    }
  }

  @Test
  void searchMergesGraduatedIndexAndActiveCache() {
    NearestNeighborSearchIndex index = NearestNeighborSearchIndex.create(configWithMaxCacheSize(2));
    long exact = index.insert(denseVector(1f, 0f), Map.of("city", "sf"));
    long mid = index.insert(denseVector(1f, 1f), Map.of("city", "sf"));
    long far = index.insert(denseVector(0f, 1f), Map.of("city", "sf"));

    SearchResults result =
        index.getNearestNeighbors(
            10, denseVector(1f, 0f), new MetaFilter(Map.of("city", List.of("sf"))));

    assertEquals(List.of(exact, mid, far), rowNums(result));
  }

  @Test
  void metadataFilteringIndexesAllProvidedMetadataKeys() {
    NearestNeighborSearchIndex index = NearestNeighborSearchIndex.create(config());
    long sf = index.insert(denseVector(1f, 0f), Map.of("country", "us"));

    SearchResults result =
        index.getNearestNeighbors(
            10, denseVector(1f, 0f), new MetaFilter(Map.of("country", List.of("us"))));

    assertEquals(List.of(sf), rowNums(result));
  }

  @Test
  void searchIncludesGraduatingCacheBeforeBackgroundGraduationFinishes() {
    CountDownLatch releaseGraduation = new CountDownLatch(1);
    try (NearestNeighborSearchIndex index =
        new NearestNeighborSearchIndex(
            configWithMaxCacheSize(1), blockedSingleThreadExecutor(releaseGraduation))) {
      long rowNum = index.insert(denseVector(1f, 0f), Map.of("city", "sf"));

      SearchResults result =
          index.getNearestNeighbors(
              10, denseVector(1f, 0f), new MetaFilter(Map.of("city", List.of("sf"))));

      assertEquals(List.of(rowNum), rowNums(result));
      assertEquals(2, index.getNumSearchableStructures());

      releaseGraduation.countDown();
      index.awaitBackgroundTasks();
      assertEquals(2, index.getNumSearchableStructures());
    }
  }

  @Test
  void backgroundGraduationKeepsSearchableStructuresWithinLimit() {
    try (NearestNeighborSearchIndex index =
        NearestNeighborSearchIndex.create(configWithMaxCacheSize(1))) {
      long first = index.insert(denseVector(1f, 0f), Map.of("city", "sf"));
      long second = index.insert(denseVector(0.8f, 0.2f), Map.of("city", "sf"));
      long third = index.insert(denseVector(0.2f, 0.8f), Map.of("city", "sf"));
      long fourth = index.insert(denseVector(0f, 1f), Map.of("city", "sf"));

      index.awaitBackgroundTasks();

      assertTrue(index.getNumSearchableStructures() <= 3);
      assertEquals(List.of(first, second, third, fourth), rowNums(index.getAllRowNums()));

      SearchResults result =
          index.getNearestNeighbors(
              10, denseVector(1f, 0f), new MetaFilter(Map.of("city", List.of("sf"))));
      assertEquals(List.of(first, second, third, fourth), rowNums(result));
    }
  }

  @Test
  void deleteRemovesRowFromGraduatedIndex() {
    NearestNeighborSearchIndex index = NearestNeighborSearchIndex.create(configWithMaxCacheSize(1));
    long rowNum = index.insert(denseVector(1f, 0f), Map.of("city", "sf"));

    assertTrue(index.delete(rowNum));

    SearchResults result =
        index.getNearestNeighbors(
            10, denseVector(1f, 0f), new MetaFilter(Map.of("city", List.of("sf"))));
    assertTrue(result.isEmpty());
    assertEquals(0, index.getAllRowNums().length);
    assertEquals(0, index.size());
  }

  @Test
  void updateGraduatedRowDeletesOldVersionAndWritesSameRowNum() {
    NearestNeighborSearchIndex index = NearestNeighborSearchIndex.create(configWithMaxCacheSize(1));
    long rowNum = index.insert(denseVector(1f, 0f), Map.of("city", "sf"));

    assertTrue(index.update(rowNum, denseVector(0f, 1f), Map.of("city", "la")));

    SearchResults oldVersionResult =
        index.getNearestNeighbors(
            10, denseVector(1f, 0f), new MetaFilter(Map.of("city", List.of("sf"))));
    SearchResults newVersionResult =
        index.getNearestNeighbors(
            10, denseVector(0f, 1f), new MetaFilter(Map.of("city", List.of("la"))));

    assertTrue(oldVersionResult.isEmpty());
    assertEquals(List.of(rowNum), rowNums(newVersionResult));
    assertEquals(List.of(rowNum), rowNums(index.getAllRowNums()));
  }

  @SuppressWarnings("unchecked")
  private static LongObjectHashMap<LongTermsAndValues> getAllInternal(
      NearestNeighborSearchIndex index) throws ReflectiveOperationException {
    Method method = NearestNeighborSearchIndex.class.getDeclaredMethod("getAll");
    method.setAccessible(true);
    return (LongObjectHashMap<LongTermsAndValues>) method.invoke(index);
  }

  private static NamespaceConfig config() {
    return configWithMaxCacheSize(10);
  }

  private static NamespaceConfig configWithMaxCacheSize(int maxCacheSize) {
    return NamespaceConfig.builder()
        .minTermsAndValuesLength(0)
        .maxTermsAndValuesLength(2)
        .maxCacheSize(maxCacheSize)
        .cacheType("generic")
        .indexType("generic")
        .comparatorType("l2")
        .comparatorNormalizerType("reciprocal")
        .maxNumSearchableStructures(3)
        .maxNumSimilarities(10)
        .build();
  }

  private static NamespaceConfig sparseConfigWithMaxCacheSize(int maxCacheSize) {
    return NamespaceConfig.builder()
        .minTermsAndValuesLength(0)
        .maxTermsAndValuesLength(3)
        .maxCacheSize(maxCacheSize)
        .cacheType("generic")
        .indexType("inverted")
        .comparatorType("jaccard")
        .comparatorNormalizerType("identity")
        .maxNumSearchableStructures(3)
        .maxNumSimilarities(10)
        .build();
  }

  private static TermsAndValues denseVector(float... values) {
    return new TermsAndValues(new String[0], values);
  }

  private static ExecutorService blockedSingleThreadExecutor(CountDownLatch releaseLatch) {
    return Executors.newSingleThreadExecutor(
        runnable -> {
          Thread thread =
              new Thread(
                  () -> {
                    try {
                      releaseLatch.await();
                    } catch (InterruptedException e) {
                      Thread.currentThread().interrupt();
                      return;
                    }
                    runnable.run();
                  },
                  "blocked-test-background-executor");
          thread.setDaemon(true);
          return thread;
        });
  }
}
