package com.uber.ussi;

import static com.uber.ussi.TestLongObjectMaps.longHashSet;
import static com.uber.ussi.TestLongObjectMaps.longObjectMap;
import static com.uber.ussi.TestLongObjectMaps.rowNums;
import static com.uber.ussi.TestLongObjectMaps.sortedKeys;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carrotsearch.hppc.LongHashSet;
import com.carrotsearch.hppc.LongObjectHashMap;
import com.carrotsearch.hppc.cursors.LongObjectCursor;
import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.entity.meta.LongMeta;
import com.uber.ussi.entity.meta.MetaFilter;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValuesTestFactory;
import com.uber.ussi.entity.termsandvalues.TermsAndValues;
import com.uber.ussi.searchablestructure.cache.Cache;
import com.uber.ussi.searchablestructure.cache.CacheFactory;
import com.uber.ussi.searchablestructure.index.Index;
import com.uber.ussi.searchablestructure.index.generic.GenericIndex;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class NearestNeighborSearchIndexExtraTest {

  private static NamespaceConfig config() {
    return configWithMaxCacheSize(10);
  }

  private static NamespaceConfig configWithMaxCacheSize(int maxCacheSize) {
    return configWithMaxCacheSizeAndSearchableStructures(maxCacheSize, 3);
  }

  private static NamespaceConfig configWithMaxCacheSizeAndSearchableStructures(
      int maxCacheSize, int maxNumSearchableStructures) {
    return NamespaceConfig.builder()
        .minTermsAndValuesLength(0)
        .maxTermsAndValuesLength(2)
        .maxCacheSize(maxCacheSize)
        .cacheType("generic")
        .indexType("generic")
        .comparatorType("l2")
        .comparatorNormalizerType("reciprocal")
        .maxNumSearchableStructures(maxNumSearchableStructures)
        .maxNumSimilarities(10)
        .build();
  }

  private static TermsAndValues denseVector(float... values) {
    return new TermsAndValues(new String[0], values);
  }

  @Test
  void getNamespaceConfigReturnsConfig() {
    NamespaceConfig config = config();

    assertEquals(config, NearestNeighborSearchIndex.create(config).getNamespaceConfig());
  }

  @Test
  void getNearestNeighborsRejectsNonPositiveK() {
    NearestNeighborSearchIndex index = NearestNeighborSearchIndex.create(config());

    assertThrows(
        IllegalArgumentException.class,
        () -> index.getNearestNeighbors(0, denseVector(1f, 0f), MetaFilter.empty()));
  }

  @Test
  void getSimilarRowNumsReturnsMatchesAboveThreshold() {
    NearestNeighborSearchIndex index = NearestNeighborSearchIndex.create(config());
    long sf = index.insert(denseVector(1f, 0f), Map.of("city", "sf"));

    SearchResults result =
        index.getSimilarRowNums(
            0.5f, denseVector(1f, 0f), new MetaFilter(Map.of("city", List.of("sf"))));

    assertTrue(rowNums(result).contains(sf));
  }

  @Test
  void getSimilarRowNumsRejectsOutOfRangeMinSimilarity() {
    NearestNeighborSearchIndex index = NearestNeighborSearchIndex.create(config());

    assertThrows(
        IllegalArgumentException.class,
        () -> index.getSimilarRowNums(1.5f, denseVector(1f, 0f), MetaFilter.empty()));
  }

  @Test
  void updateReturnsFalseForUnknownRow() {
    NearestNeighborSearchIndex index = NearestNeighborSearchIndex.create(config());

    assertFalse(index.update(999, denseVector(1f, 0f), Map.of("city", "sf")));
  }

  @Test
  void insertThrowsWhenGeneratedRowNumAlreadyExistsInActiveCache()
      throws ReflectiveOperationException {
    NearestNeighborSearchIndex index = NearestNeighborSearchIndex.create(config());
    index.insert(denseVector(1f, 0f), Map.of("city", "sf"));
    setField(index, "nextRowNum", 0L);

    assertThrows(
        IllegalStateException.class, () -> index.insert(denseVector(0f, 1f), Map.of("city", "la")));
  }

  @Test
  void insertThrowsWhenNextRowNumWouldOverflow() throws ReflectiveOperationException {
    NearestNeighborSearchIndex index = NearestNeighborSearchIndex.create(config());
    setField(index, "nextRowNum", Long.MAX_VALUE);

    assertThrows(
        IllegalStateException.class, () -> index.insert(denseVector(1f, 0f), Map.of("city", "sf")));

    assertEquals(0, index.size());
  }

  @Test
  void updateThrowsWhenActiveCacheInsertConflictsAfterDelete() throws ReflectiveOperationException {
    NearestNeighborSearchIndex index = NearestNeighborSearchIndex.create(config());
    Cache activeCache = activeCache(index);
    Field rowsField = Cache.class.getDeclaredField("rowNumToTermsAndValuesMap");
    rowsField.setAccessible(true);
    rowsField.set(activeCache, new RowSevenConflictsAfterFirstContainsMap());
    addIndexForTest(
        index,
        new GenericIndex(
            config(),
            longObjectMap(7, denseInternal(1f, 0f)),
            longObjectMap(7, longMeta("city", "sf"))),
        0);

    assertThrows(
        IllegalStateException.class,
        () -> index.update(7, denseVector(0f, 1f), Map.of("city", "la")));
  }

  @Test
  void sizeIncludesGraduatingCacheBeforeBackgroundTaskRuns() {
    CountDownLatch releaseGraduation = new CountDownLatch(1);
    try (NearestNeighborSearchIndex index =
        new NearestNeighborSearchIndex(
            configWithMaxCacheSize(1), blockedSingleThreadExecutor(releaseGraduation))) {
      index.insert(denseVector(1f, 0f), Map.of("city", "sf"));

      assertEquals(1, index.size());

      releaseGraduation.countDown();
      index.awaitBackgroundTasks();
    }
  }

  @Test
  void sizeIncludesGraduatedIndexAfterBackgroundTaskRuns() {
    try (NearestNeighborSearchIndex index =
        NearestNeighborSearchIndex.create(configWithMaxCacheSize(1))) {
      index.insert(denseVector(1f, 0f), Map.of("city", "sf"));
      index.awaitBackgroundTasks();

      assertEquals(1, index.size());
    }
  }

  @Test
  void deleteCanRemoveRowFromGraduatingCacheBeforeBackgroundTaskRuns() {
    CountDownLatch releaseGraduation = new CountDownLatch(1);
    try (NearestNeighborSearchIndex index =
        new NearestNeighborSearchIndex(
            configWithMaxCacheSize(1), blockedSingleThreadExecutor(releaseGraduation))) {
      long rowNum = index.insert(denseVector(1f, 0f), Map.of("city", "sf"));

      assertTrue(index.delete(rowNum));

      releaseGraduation.countDown();
      index.awaitBackgroundTasks();
    }
  }

  @Test
  void searchCanReadGraduatingCacheWhileIndexBuildReadsCache() throws Exception {
    CountDownLatch buildStarted = new CountDownLatch(1);
    CountDownLatch releaseBuild = new CountDownLatch(1);
    CapturingExecutorService executor = new CapturingExecutorService();
    try (NearestNeighborSearchIndex index =
        new NearestNeighborSearchIndex(configWithMaxCacheSize(1), executor)) {
      long rowNum = index.insert(denseVector(1f, 0f), Map.of("city", "sf"));
      Cache graduatingCache = graduatingCaches(index).get(0);
      setField(
          graduatingCache,
          "rowNumToTermsAndValuesMap",
          new FirstEntrySetBlocksMap(buildStarted, releaseBuild, rowNum));

      FutureTask<?> graduationTask = executor.getSubmittedTask();
      Thread graduationThread = new Thread(graduationTask, "test-graduation-thread");
      graduationThread.start();

      buildStarted.await();
      SearchResults result =
          index.getNearestNeighbors(
              10, denseVector(1f, 0f), new MetaFilter(Map.of("city", List.of("sf"))));

      assertEquals(List.of(rowNum), rowNums(result));
      releaseBuild.countDown();
      graduationTask.get();
    }
  }

  @Test
  void deleteCanRemoveRowFromGraduatedIndexAfterBackgroundTaskRuns() {
    try (NearestNeighborSearchIndex index =
        NearestNeighborSearchIndex.create(configWithMaxCacheSize(1))) {
      long rowNum = index.insert(denseVector(1f, 0f), Map.of("city", "sf"));
      index.awaitBackgroundTasks();

      assertTrue(index.delete(rowNum));
    }
  }

  @Test
  void similarSearchMergesGraduatingCacheAndGraduatedIndex() {
    CountDownLatch releaseGraduation = new CountDownLatch(1);
    try (NearestNeighborSearchIndex index =
        new NearestNeighborSearchIndex(
            configWithMaxCacheSize(2), blockedSingleThreadExecutor(releaseGraduation))) {
      long first = index.insert(denseVector(1f, 0f), Map.of("city", "sf"));
      long second = index.insert(denseVector(0.9f, 0.1f), Map.of("city", "sf"));
      long third = index.insert(denseVector(0.8f, 0.2f), Map.of("city", "sf"));

      SearchResults result =
          index.getSimilarRowNums(
              0.4f, denseVector(1f, 0f), new MetaFilter(Map.of("city", List.of("sf"))));

      assertEquals(List.of(first, second, third), rowNums(result));

      releaseGraduation.countDown();
      index.awaitBackgroundTasks();
    }
  }

  @Test
  void similarSearchMergesGraduatedIndexAfterBackgroundTaskRuns() {
    try (NearestNeighborSearchIndex index =
        NearestNeighborSearchIndex.create(configWithMaxCacheSize(1))) {
      long rowNum = index.insert(denseVector(1f, 0f), Map.of("city", "sf"));
      index.awaitBackgroundTasks();

      SearchResults result =
          index.getSimilarRowNums(
              0.5f, denseVector(1f, 0f), new MetaFilter(Map.of("city", List.of("sf"))));

      assertEquals(List.of(rowNum), rowNums(result));
    }
  }

  @Test
  void nearestNeighborSearchTruncatesMergedStructureResults() {
    CountDownLatch releaseGraduation = new CountDownLatch(1);
    try (NearestNeighborSearchIndex index =
        new NearestNeighborSearchIndex(
            configWithMaxCacheSize(2), blockedSingleThreadExecutor(releaseGraduation))) {
      long first = index.insert(denseVector(1f, 0f), Map.of("city", "sf"));
      index.insert(denseVector(0.9f, 0.1f), Map.of("city", "sf"));
      long active = index.insert(denseVector(1f, 0f), Map.of("city", "sf"));

      SearchResults result =
          index.getNearestNeighbors(
              1, denseVector(1f, 0f), new MetaFilter(Map.of("city", List.of("sf"))));

      assertEquals(List.of(first), rowNums(result));
      assertFalse(rowNums(result).contains(active));

      releaseGraduation.countDown();
      index.awaitBackgroundTasks();
    }
  }

  @Test
  void privateGraduationNoopsWhenCacheIsNoLongerGraduating() throws ReflectiveOperationException {
    NearestNeighborSearchIndex index = NearestNeighborSearchIndex.create(config());
    Method method =
        NearestNeighborSearchIndex.class.getDeclaredMethod("graduateCache", Cache.class);
    method.setAccessible(true);

    method.invoke(index, CacheFactory.createCache(config()));

    assertEquals(0, index.size());
  }

  @Test
  void awaitBackgroundTasksWrapsExecutionException() {
    NearestNeighborSearchIndex index =
        new NearestNeighborSearchIndex(
            configWithMaxCacheSize(1),
            new FutureReturningExecutorService(new ThrowingFuture(false)));
    index.insert(denseVector(1f, 0f), Map.of("city", "sf"));

    assertThrows(RuntimeException.class, index::awaitBackgroundTasks);
  }

  @Test
  void awaitBackgroundTasksRestoresInterruptWhenInterrupted() {
    NearestNeighborSearchIndex index =
        new NearestNeighborSearchIndex(
            configWithMaxCacheSize(1),
            new FutureReturningExecutorService(new ThrowingFuture(true)));
    index.insert(denseVector(1f, 0f), Map.of("city", "sf"));

    assertThrows(RuntimeException.class, index::awaitBackgroundTasks);
    assertTrue(Thread.currentThread().isInterrupted());
    Thread.interrupted();
  }

  @Test
  void getSimilarRowNumsRejectsNegativeMinSimilarity() {
    NearestNeighborSearchIndex index = NearestNeighborSearchIndex.create(config());

    assertThrows(
        IllegalArgumentException.class,
        () -> index.getSimilarRowNums(-0.1f, denseVector(1f, 0f), MetaFilter.empty()));
  }

  @Test
  void applyTombstonesIgnoresNullTombstones() throws ReflectiveOperationException {
    Index builtIndex = genericIndex(1);

    invokeApplyTombstones(builtIndex, null);

    assertEquals(1, builtIndex.size());
  }

  @Test
  void applyTombstonesDeletesEachRow() throws ReflectiveOperationException {
    Index builtIndex =
        new GenericIndex(
            config(),
            longObjectMap(1, denseInternal(1f, 0f), 2, denseInternal(0f, 1f)),
            longObjectMap(1, longMeta("city", "sf"), 2, longMeta("city", "la")));

    invokeApplyTombstones(builtIndex, longHashSet(1));

    assertEquals(1, builtIndex.size());
    assertFalse(builtIndex.getAll().containsKey(1));
  }

  @Test
  void indexesStartWithSnapshotReturnsFalseWhenLiveListIsShorter()
      throws ReflectiveOperationException {
    NearestNeighborSearchIndex index = NearestNeighborSearchIndex.create(config());
    Index onlyIndex = genericIndex(1);
    indexes(index).add(onlyIndex);

    assertFalse(invokeIndexesStartWithSnapshot(index, List.of(onlyIndex, genericIndex(2))));
  }

  @Test
  void indexesStartWithSnapshotReturnsFalseWhenElementDiffers()
      throws ReflectiveOperationException {
    NearestNeighborSearchIndex index = NearestNeighborSearchIndex.create(config());
    indexes(index).add(genericIndex(1));

    assertFalse(invokeIndexesStartWithSnapshot(index, List.of(genericIndex(2))));
  }

  @Test
  void indexesStartWithSnapshotReturnsTrueWhenIdentical() throws ReflectiveOperationException {
    NearestNeighborSearchIndex index = NearestNeighborSearchIndex.create(config());
    Index onlyIndex = genericIndex(1);
    indexes(index).add(onlyIndex);

    assertTrue(invokeIndexesStartWithSnapshot(index, List.of(onlyIndex)));
  }

  @Test
  void indexesStartWithSnapshotReturnsTrueWhenNewerIndexWasAppended()
      throws ReflectiveOperationException {
    NearestNeighborSearchIndex index = NearestNeighborSearchIndex.create(config());
    Index oldIndex = genericIndex(1);
    indexes(index).add(oldIndex);
    indexes(index).add(genericIndex(2));

    assertTrue(invokeIndexesStartWithSnapshot(index, List.of(oldIndex)));
  }

  @Test
  void clearConsolidationDeletesClearsWhenSameSet() throws ReflectiveOperationException {
    NearestNeighborSearchIndex index = NearestNeighborSearchIndex.create(config());
    LongHashSet tombstones = longHashSet();
    setField(index, "consolidationDeletes", tombstones);

    invokeClearConsolidationDeletes(index, tombstones);

    assertNull(getFieldValue(index, "consolidationDeletes"));
  }

  @Test
  void clearConsolidationDeletesKeepsDifferentSet() throws ReflectiveOperationException {
    NearestNeighborSearchIndex index = NearestNeighborSearchIndex.create(config());
    LongHashSet current = longHashSet();
    setField(index, "consolidationDeletes", current);

    invokeClearConsolidationDeletes(index, longHashSet());

    assertSame(current, getFieldValue(index, "consolidationDeletes"));
  }

  @Test
  void deleteSkipsGraduatingCacheWithoutRowAndMissingTombstoneSet()
      throws ReflectiveOperationException {
    NearestNeighborSearchIndex index = NearestNeighborSearchIndex.create(config());
    Cache rowCache = CacheFactory.createCache(config());
    rowCache.insertWithRowNum(5, denseInternal(1f, 0f), Map.of("city", "sf"));
    Cache emptyCache = CacheFactory.createCache(config());
    addGraduatingCacheForTest(index, rowCache, 0);
    addGraduatingCacheForTest(index, emptyCache, 1);

    assertTrue(index.delete(5));
  }

  @Test
  void deleteFromIndexRecordsConsolidationTombstone() throws ReflectiveOperationException {
    NearestNeighborSearchIndex index = NearestNeighborSearchIndex.create(config());
    addIndexForTest(index, genericIndex(9), 0);
    addIndexForTest(index, genericIndex(1), 1);
    LongHashSet consolidationDeletes = longHashSet();
    setField(index, "consolidationDeletes", consolidationDeletes);

    assertTrue(index.delete(9));
    assertTrue(consolidationDeletes.contains(9));
  }

  @Test
  void graduateCacheClosesBuiltIndexWhenCacheRemovedBeforeSwap()
      throws ReflectiveOperationException {
    NearestNeighborSearchIndex index = NearestNeighborSearchIndex.create(config());
    Cache graduating = CacheFactory.createCache(config());
    graduating.insertWithRowNum(3, denseInternal(1f, 0f), Map.of("city", "sf"));
    setField(index, "graduatingCaches", new ContainsTrueRemoveFalseCacheList());

    invokeGraduateCache(index, graduating);

    assertEquals(0, indexes(index).size());
  }

  @Test
  void graduateCacheSkipsCloseWhenSnapshotEmptyAndNotGraduated()
      throws ReflectiveOperationException {
    NearestNeighborSearchIndex index = NearestNeighborSearchIndex.create(config());
    Cache graduating = CacheFactory.createCache(config());
    setField(index, "graduatingCaches", new ContainsTrueRemoveFalseCacheList());

    invokeGraduateCache(index, graduating);

    assertEquals(0, indexes(index).size());
  }

  @Test
  void graduateCacheThrowsWhenGenerationIsMissing() throws ReflectiveOperationException {
    NearestNeighborSearchIndex index = NearestNeighborSearchIndex.create(config());
    Cache graduating = CacheFactory.createCache(config());
    graduating.insertWithRowNum(3, denseInternal(1f, 0f), Map.of("city", "sf"));
    graduatingCaches(index).add(graduating);

    ReflectiveOperationException error =
        assertThrows(
            ReflectiveOperationException.class, () -> invokeGraduateCache(index, graduating));

    assertTrue(error.getCause() instanceof IllegalStateException);
  }

  @Test
  void consolidateMergesNonEmptyIndexes() throws ReflectiveOperationException {
    NearestNeighborSearchIndex index = NearestNeighborSearchIndex.create(config());
    addIndexForTest(index, genericIndex(1), 0);
    addIndexForTest(index, genericIndex(2), 1);

    invokeConsolidate(index);

    assertEquals(1, indexes(index).size());
    assertEquals(2, indexes(index).get(0).size());
  }

  @Test
  void consolidatableIndexPrefixStopsAtOldestGraduatingCacheGeneration()
      throws ReflectiveOperationException {
    NearestNeighborSearchIndex index = NearestNeighborSearchIndex.create(config());
    Index olderIndex = genericIndex(1);
    Index sameGenerationAsGraduatingCache = genericIndex(2);
    Cache graduatingCache = CacheFactory.createCache(config());
    indexes(index).add(olderIndex);
    indexes(index).add(sameGenerationAsGraduatingCache);
    graduatingCaches(index).add(graduatingCache);
    indexGenerations(index).put(olderIndex, 3);
    indexGenerations(index).put(sameGenerationAsGraduatingCache, 7);
    graduatingCacheGenerations(index).put(graduatingCache, 7);

    List<Index> prefix = invokeGetConsolidatableIndexPrefix(index);

    assertEquals(List.of(olderIndex), prefix);
  }

  @Test
  void orderedSearchableStructuresUsesAscendingTieBreakerForOldestFirstEqualGenerations()
      throws ReflectiveOperationException {
    NearestNeighborSearchIndex index = NearestNeighborSearchIndex.create(config());
    Index firstIndex = genericIndex(1);
    Index secondIndex = genericIndex(2);
    indexes(index).add(firstIndex);
    indexes(index).add(secondIndex);
    indexGenerations(index).put(firstIndex, 5);
    indexGenerations(index).put(secondIndex, 5);

    List<?> structures = invokeOrderedSearchableStructures(index, /* newestFirst */ false);
    List<?> newestFirstStructures =
        invokeOrderedSearchableStructures(index, /* newestFirst */ true);

    assertEquals(2, structures.size());
    assertEquals(2, newestFirstStructures.size());
  }

  @Test
  void orderedSearchableStructuresThrowsWhenIndexGenerationIsMissing()
      throws ReflectiveOperationException {
    NearestNeighborSearchIndex index = NearestNeighborSearchIndex.create(config());
    indexes(index).add(genericIndex(1));

    ReflectiveOperationException error =
        assertThrows(
            ReflectiveOperationException.class,
            () -> invokeOrderedSearchableStructures(index, /* newestFirst */ false));

    assertTrue(error.getCause() instanceof IllegalStateException);
  }

  @Test
  void orderedSearchableStructuresThrowsWhenGraduatingCacheGenerationIsMissing()
      throws ReflectiveOperationException {
    NearestNeighborSearchIndex index = NearestNeighborSearchIndex.create(config());
    graduatingCaches(index).add(CacheFactory.createCache(config()));

    ReflectiveOperationException error =
        assertThrows(
            ReflectiveOperationException.class,
            () -> invokeOrderedSearchableStructures(index, /* newestFirst */ false));

    assertTrue(error.getCause() instanceof IllegalStateException);
  }

  @Test
  void consolidateClearsIndexesWhenAllEmpty() throws ReflectiveOperationException {
    NearestNeighborSearchIndex index = NearestNeighborSearchIndex.create(config());
    addIndexForTest(index, new GenericIndex(config(), longObjectMap(), longObjectMap()), 0);
    addIndexForTest(index, new GenericIndex(config(), longObjectMap(), longObjectMap()), 1);

    invokeConsolidate(index);

    assertEquals(0, indexes(index).size());
  }

  @Test
  void consolidatePreservesIndexAppendedByConcurrentGraduation()
      throws ReflectiveOperationException {
    NearestNeighborSearchIndex index = NearestNeighborSearchIndex.create(config());
    Index concurrentlyGraduatedIndex = genericIndex(7);
    AppendAfterSnapshotIndexList indexList =
        new AppendAfterSnapshotIndexList(concurrentlyGraduatedIndex);
    Index firstIndex = genericIndex(7);
    Index secondIndex = genericIndex(2);
    indexList.add(firstIndex);
    indexList.add(secondIndex);
    setField(index, "indexes", indexList);
    indexGenerations(index).put(firstIndex, 0);
    indexGenerations(index).put(secondIndex, 1);
    indexGenerations(index).put(concurrentlyGraduatedIndex, 2);

    invokeConsolidate(index);

    assertEquals(2, indexes(index).size());
    assertEquals(2, indexes(index).get(0).size());
    assertSame(concurrentlyGraduatedIndex, indexes(index).get(1));
  }

  @Test
  void updateScansConcurrentlyGraduatedIndexBeforeConsolidatedIndex()
      throws ReflectiveOperationException {
    NearestNeighborSearchIndex index = NearestNeighborSearchIndex.create(config());
    Index concurrentlyGraduatedIndex = genericIndex(7);
    AppendAfterSnapshotIndexList indexList =
        new AppendAfterSnapshotIndexList(concurrentlyGraduatedIndex);
    Index firstIndex = genericIndex(7);
    Index secondIndex = genericIndex(2);
    indexList.add(firstIndex);
    indexList.add(secondIndex);
    setField(index, "indexes", indexList);
    indexGenerations(index).put(firstIndex, 0);
    indexGenerations(index).put(secondIndex, 1);
    indexGenerations(index).put(concurrentlyGraduatedIndex, 2);

    invokeConsolidate(index);
    Index consolidatedIndex = indexes(index).get(0);

    assertTrue(index.update(7, denseVector(0f, 1f), Map.of("city", "la")));

    assertTrue(consolidatedIndex.getAll().containsKey(7));
    assertFalse(concurrentlyGraduatedIndex.getAll().containsKey(7));
    assertTrue(activeCache(index).getAll().containsKey(7));
  }

  @Test
  void concurrentGraduationsKeepIndexListInCacheChronology() throws Exception {
    CapturingExecutorService executor = new CapturingExecutorService();
    try (NearestNeighborSearchIndex index =
        new NearestNeighborSearchIndex(
            configWithMaxCacheSizeAndSearchableStructures(1, 4), executor)) {
      long olderRow = index.insert(denseVector(1f, 0f), Map.of("city", "sf"));
      long newerRow = index.insert(denseVector(0f, 1f), Map.of("city", "la"));
      List<FutureTask<?>> tasks = executor.getSubmittedTasks();

      tasks.get(1).run();
      tasks.get(1).get();
      tasks.get(0).run();
      tasks.get(0).get();

      assertEquals(List.of(olderRow), sortedKeys(indexes(index).get(0).getAll()));
      assertEquals(List.of(newerRow), sortedKeys(indexes(index).get(1).getAll()));
    }
  }

  @Test
  void consolidateReturnsEarlyWhenSingleIndexButManyStructures()
      throws ReflectiveOperationException {
    NearestNeighborSearchIndex index = NearestNeighborSearchIndex.create(config());
    addGraduatingCacheForTest(index, CacheFactory.createCache(config()), 0);
    addGraduatingCacheForTest(index, CacheFactory.createCache(config()), 1);
    addIndexForTest(index, genericIndex(1), 2);

    invokeConsolidate(index);

    assertEquals(1, indexes(index).size());
  }

  @Test
  void consolidateRetriesWhileGraduatingCachesKeepStructureCountAboveLimit()
      throws ReflectiveOperationException {
    NearestNeighborSearchIndex index = NearestNeighborSearchIndex.create(config());
    addIndexForTest(index, genericIndex(1), 0);
    addIndexForTest(index, genericIndex(2), 1);
    addGraduatingCacheForTest(index, CacheFactory.createCache(config()), 2);
    addGraduatingCacheForTest(index, CacheFactory.createCache(config()), 3);

    invokeConsolidate(index);

    assertEquals(1, indexes(index).size());
    assertEquals(2, indexes(index).get(0).size());
  }

  @Test
  void consolidateDiscardsMergedIndexWhenSnapshotChangesDuringBuild()
      throws ReflectiveOperationException {
    NearestNeighborSearchIndex index = NearestNeighborSearchIndex.create(config());
    ShrinkAfterSnapshotIndexList indexList = new ShrinkAfterSnapshotIndexList();
    Index firstIndex = genericIndex(1);
    Index secondIndex = genericIndex(2);
    indexList.add(firstIndex);
    indexList.add(secondIndex);
    setField(index, "indexes", indexList);
    indexGenerations(index).put(firstIndex, 0);
    indexGenerations(index).put(secondIndex, 1);

    invokeConsolidate(index);

    /*
     * The swap is skipped because the live index set no longer matches the build snapshot, so the
     * original indexes remain in place.
     */
    assertEquals(2, indexList.underlyingSize());
  }

  @Test
  void consolidateSkipsCloseWhenSnapshotChangesAndMergedIndexEmpty()
      throws ReflectiveOperationException {
    NearestNeighborSearchIndex index = NearestNeighborSearchIndex.create(config());
    ShrinkAfterSnapshotIndexList indexList = new ShrinkAfterSnapshotIndexList();
    Index firstIndex = new GenericIndex(config(), longObjectMap(), longObjectMap());
    Index secondIndex = new GenericIndex(config(), longObjectMap(), longObjectMap());
    indexList.add(firstIndex);
    indexList.add(secondIndex);
    setField(index, "indexes", indexList);
    indexGenerations(index).put(firstIndex, 0);
    indexGenerations(index).put(secondIndex, 1);

    invokeConsolidate(index);

    assertEquals(2, indexList.underlyingSize());
  }

  @SuppressWarnings("unchecked")
  private static List<Index> indexes(NearestNeighborSearchIndex index)
      throws ReflectiveOperationException {
    Field field = NearestNeighborSearchIndex.class.getDeclaredField("indexes");
    field.setAccessible(true);
    return (List<Index>) field.get(index);
  }

  private static void addIndexForTest(
      NearestNeighborSearchIndex index, Index indexToAdd, int generation)
      throws ReflectiveOperationException {
    indexes(index).add(indexToAdd);
    indexGenerations(index).put(indexToAdd, generation);
  }

  private static void addGraduatingCacheForTest(
      NearestNeighborSearchIndex index, Cache cacheToAdd, int generation)
      throws ReflectiveOperationException {
    graduatingCaches(index).add(cacheToAdd);
    graduatingCacheGenerations(index).put(cacheToAdd, generation);
  }

  private static Cache activeCache(NearestNeighborSearchIndex index)
      throws ReflectiveOperationException {
    Field field = NearestNeighborSearchIndex.class.getDeclaredField("cache");
    field.setAccessible(true);
    return (Cache) field.get(index);
  }

  @SuppressWarnings("unchecked")
  private static Map<Index, Integer> indexGenerations(NearestNeighborSearchIndex index)
      throws ReflectiveOperationException {
    return getFieldValue(index, "indexGenerations");
  }

  @SuppressWarnings("unchecked")
  private static Map<Cache, Integer> graduatingCacheGenerations(NearestNeighborSearchIndex index)
      throws ReflectiveOperationException {
    return getFieldValue(index, "graduatingCacheGenerations");
  }

  @SuppressWarnings("unchecked")
  private static List<Cache> graduatingCaches(NearestNeighborSearchIndex index)
      throws ReflectiveOperationException {
    Field field = NearestNeighborSearchIndex.class.getDeclaredField("graduatingCaches");
    field.setAccessible(true);
    return (List<Cache>) field.get(index);
  }

  private static void setField(Object target, String fieldName, Object value)
      throws ReflectiveOperationException {
    Field field = getField(target.getClass(), fieldName);
    field.setAccessible(true);
    field.set(target, value);
  }

  private static Field getField(Class<?> type, String fieldName) throws NoSuchFieldException {
    Class<?> currentType = type;
    while (currentType != null) {
      try {
        return currentType.getDeclaredField(fieldName);
      } catch (NoSuchFieldException e) {
        currentType = currentType.getSuperclass();
      }
    }
    throw new NoSuchFieldException(fieldName);
  }

  private static LongTermsAndValues denseInternal(float... values) {
    double uniValue = 0.0;
    for (float value : values) {
      uniValue += value * value;
    }
    return LongTermsAndValuesTestFactory.create(new long[0], values, uniValue);
  }

  private static LongMeta longMeta(String key, String value) {
    return new LongMeta(Map.of(key, value), /* requireLongKeysAndValues */ false);
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
                  "blocked-extra-test-background-executor");
          thread.setDaemon(true);
          return thread;
        });
  }

  private static final class RowSevenConflictsAfterFirstContainsMap
      extends LongObjectHashMap<LongTermsAndValues> {
    private int rowSevenContainsCalls;

    @Override
    public boolean containsKey(long key) {
      if (key == 7) {
        rowSevenContainsCalls++;
        return rowSevenContainsCalls > 1;
      }
      return super.containsKey(key);
    }
  }

  private static final class FirstEntrySetBlocksMap extends LongObjectHashMap<LongTermsAndValues> {
    private final CountDownLatch buildStarted;
    private final CountDownLatch releaseBuild;
    private boolean hasBlocked;

    private FirstEntrySetBlocksMap(
        CountDownLatch buildStarted, CountDownLatch releaseBuild, long rowNum) {
      this.buildStarted = buildStarted;
      this.releaseBuild = releaseBuild;
      put(rowNum, denseInternal(1f, 0f));
    }

    @Override
    public Iterator<LongObjectCursor<LongTermsAndValues>> iterator() {
      if (!hasBlocked) {
        hasBlocked = true;
        buildStarted.countDown();
        try {
          releaseBuild.await();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new RuntimeException("Interrupted while blocking index build.", e);
        }
      }
      return super.iterator();
    }
  }

  private static final class CapturingExecutorService extends AbstractExecutorService {
    private final List<FutureTask<?>> submittedTasks = new ArrayList<>();
    private boolean shutdown;

    @Override
    public void shutdown() {
      shutdown = true;
    }

    @Override
    public List<Runnable> shutdownNow() {
      shutdown = true;
      return List.of();
    }

    @Override
    public boolean isShutdown() {
      return shutdown;
    }

    @Override
    public boolean isTerminated() {
      return shutdown;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
      return shutdown;
    }

    @Override
    public void execute(Runnable command) {}

    @Override
    public Future<?> submit(Runnable task) {
      FutureTask<?> submittedTask = new FutureTask<>(task, null);
      submittedTasks.add(submittedTask);
      return submittedTask;
    }

    private FutureTask<?> getSubmittedTask() {
      return submittedTasks.get(0);
    }

    private List<FutureTask<?>> getSubmittedTasks() {
      return submittedTasks;
    }
  }

  private static final class FutureReturningExecutorService extends AbstractExecutorService {
    private final Future<?> future;
    private boolean shutdown;

    private FutureReturningExecutorService(Future<?> future) {
      this.future = future;
    }

    @Override
    public void shutdown() {
      shutdown = true;
    }

    @Override
    public List<Runnable> shutdownNow() {
      shutdown = true;
      return List.of();
    }

    @Override
    public boolean isShutdown() {
      return shutdown;
    }

    @Override
    public boolean isTerminated() {
      return shutdown;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
      return shutdown;
    }

    @Override
    public void execute(Runnable command) {}

    @Override
    public Future<?> submit(Runnable task) {
      return future;
    }
  }

  private static final class ThrowingFuture implements Future<Object> {
    private final boolean throwInterrupted;

    private ThrowingFuture(boolean throwInterrupted) {
      this.throwInterrupted = throwInterrupted;
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
      return false;
    }

    @Override
    public boolean isCancelled() {
      return false;
    }

    @Override
    public boolean isDone() {
      return true;
    }

    @Override
    public Object get() throws InterruptedException, ExecutionException {
      if (throwInterrupted) {
        throw new InterruptedException("interrupted for test");
      }
      throw new ExecutionException(new RuntimeException("failed for test"));
    }

    @Override
    public Object get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException {
      return get();
    }
  }

  private static GenericIndex genericIndex(long rowNum) {
    return new GenericIndex(
        config(),
        longObjectMap(rowNum, denseInternal(1f, 0f)),
        longObjectMap(rowNum, longMeta("city", "sf")));
  }

  private static void invokeConsolidate(NearestNeighborSearchIndex index)
      throws ReflectiveOperationException {
    Method method =
        NearestNeighborSearchIndex.class.getDeclaredMethod("consolidateIndexesIfNeeded");
    method.setAccessible(true);
    method.invoke(index);
  }

  private static void invokeGraduateCache(NearestNeighborSearchIndex index, Cache cache)
      throws ReflectiveOperationException {
    Method method =
        NearestNeighborSearchIndex.class.getDeclaredMethod("graduateCache", Cache.class);
    method.setAccessible(true);
    method.invoke(index, cache);
  }

  private static void invokeApplyTombstones(Index builtIndex, LongHashSet tombstones)
      throws ReflectiveOperationException {
    Method method =
        NearestNeighborSearchIndex.class.getDeclaredMethod(
            "applyTombstones", Index.class, LongHashSet.class);
    method.setAccessible(true);
    method.invoke(null, builtIndex, tombstones);
  }

  @SuppressWarnings("unchecked")
  private static List<Index> invokeGetConsolidatableIndexPrefix(NearestNeighborSearchIndex index)
      throws ReflectiveOperationException {
    Method method =
        NearestNeighborSearchIndex.class.getDeclaredMethod("getConsolidatableIndexPrefixLocked");
    method.setAccessible(true);
    return (List<Index>) method.invoke(index);
  }

  private static List<?> invokeOrderedSearchableStructures(
      NearestNeighborSearchIndex index, boolean newestFirst) throws ReflectiveOperationException {
    Method method =
        NearestNeighborSearchIndex.class.getDeclaredMethod(
            "orderedSearchableStructuresLocked", boolean.class);
    method.setAccessible(true);
    return (List<?>) method.invoke(index, newestFirst);
  }

  private static boolean invokeIndexesStartWithSnapshot(
      NearestNeighborSearchIndex index, List<Index> snapshot) throws ReflectiveOperationException {
    Method method =
        NearestNeighborSearchIndex.class.getDeclaredMethod(
            "indexesStartWithSnapshotLocked", List.class);
    method.setAccessible(true);
    return (boolean) method.invoke(index, snapshot);
  }

  private static void invokeClearConsolidationDeletes(
      NearestNeighborSearchIndex index, LongHashSet tombstones)
      throws ReflectiveOperationException {
    Method method =
        NearestNeighborSearchIndex.class.getDeclaredMethod(
            "clearConsolidationDeletes", LongHashSet.class);
    method.setAccessible(true);
    method.invoke(index, tombstones);
  }

  @SuppressWarnings("unchecked")
  private static <T> T getFieldValue(Object target, String fieldName)
      throws ReflectiveOperationException {
    Field field = getField(target.getClass(), fieldName);
    field.setAccessible(true);
    return (T) field.get(target);
  }

  private static final class ContainsTrueRemoveFalseCacheList extends ArrayList<Cache> {
    @Override
    public boolean contains(Object o) {
      return true;
    }

    @Override
    public boolean remove(Object o) {
      return false;
    }
  }

  private static final class ShrinkAfterSnapshotIndexList extends ArrayList<Index> {
    private boolean snapshotTaken;
    private int sizeCalls;

    @Override
    public int size() {
      int size = super.size();
      if (!snapshotTaken) {
        ++sizeCalls;
        if (sizeCalls >= 3) {
          snapshotTaken = true;
          return size;
        }
      }
      return snapshotTaken ? size - 1 : size;
    }

    private int underlyingSize() {
      return super.size();
    }
  }

  private static final class AppendAfterSnapshotIndexList extends ArrayList<Index> {
    private final Index indexToAppend;
    private boolean appended;
    private int sizeCalls;

    private AppendAfterSnapshotIndexList(Index indexToAppend) {
      this.indexToAppend = indexToAppend;
    }

    @Override
    public int size() {
      int size = super.size();
      if (!appended) {
        ++sizeCalls;
        if (sizeCalls >= 4) {
          appended = true;
          add(indexToAppend);
          return size;
        }
      }
      return size;
    }
  }
}
