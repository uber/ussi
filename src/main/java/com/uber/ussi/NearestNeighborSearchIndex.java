/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi;

import com.carrotsearch.hppc.LongHashSet;
import com.carrotsearch.hppc.LongObjectHashMap;
import com.carrotsearch.hppc.cursors.LongCursor;
import com.uber.ussi.comparator.Comparator;
import com.uber.ussi.comparator.ComparatorFactory;
import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.entity.meta.LongMeta;
import com.uber.ussi.entity.meta.MetaFilter;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;
import com.uber.ussi.entity.termsandvalues.TermsAndValues;
import com.uber.ussi.searchablestructure.RowNumAndSimilarity;
import com.uber.ussi.searchablestructure.cache.Cache;
import com.uber.ussi.searchablestructure.cache.CacheFactory;
import com.uber.ussi.searchablestructure.index.Index;
import com.uber.ussi.searchablestructure.index.IndexFactory;
import com.uber.ussi.utils.BoundedSizeMaxHeap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import javax.annotation.Nullable;

/** Top-level memory-only USSI facade described by the ERD. */
public final class NearestNeighborSearchIndex implements AutoCloseable {
  private static final int MAX_BACKGROUND_THREADS = 4;

  private final NamespaceConfig namespaceConfig;
  private final List<Index> indexes;
  private final List<Cache> graduatingCaches;
  /*
   * Per-cache tombstone sets capture deletes that race a background cache graduation, replayed onto
   * the freshly built index at swap time.
   */
  private final Map<Cache, LongHashSet> graduationDeletes;
  private final Map<Cache, Integer> graduatingCacheGenerations;
  private final Map<Index, Integer> indexGenerations;
  private final List<Future<?>> backgroundTasks;
  /*
   * Tombstone set capturing deletes that race a background index consolidation, replayed onto the
   * merged index at swap time.
   */
  @Nullable private LongHashSet consolidationDeletes;
  private final ExecutorService backgroundExecutor;
  private final ReadWriteLock lock;
  private final Comparator comparator;
  private Cache cache;
  private long nextRowNum;
  private int nextStructureGeneration;
  private boolean consolidationInFlight;

  public NearestNeighborSearchIndex(NamespaceConfig namespaceConfig) {
    this(namespaceConfig, createBackgroundExecutor());
  }

  NearestNeighborSearchIndex(NamespaceConfig namespaceConfig, ExecutorService backgroundExecutor) {
    this.namespaceConfig = Objects.requireNonNull(namespaceConfig, "namespaceConfig");
    this.comparator = ComparatorFactory.createComparator(namespaceConfig);
    this.cache = CacheFactory.createCache(namespaceConfig);
    this.indexes = new ArrayList<>();
    this.graduatingCaches = new ArrayList<>();
    this.graduationDeletes = new HashMap<>();
    this.graduatingCacheGenerations = new HashMap<>();
    this.indexGenerations = new HashMap<>();
    this.backgroundTasks = new ArrayList<>();
    this.backgroundExecutor = Objects.requireNonNull(backgroundExecutor, "backgroundExecutor");
    this.lock = new ReentrantReadWriteLock();
    this.nextRowNum = 0;
    this.nextStructureGeneration = 0;
    this.consolidationInFlight = false;
  }

  public static NearestNeighborSearchIndex create(NamespaceConfig namespaceConfig) {
    return new NearestNeighborSearchIndex(namespaceConfig);
  }

  public NamespaceConfig getNamespaceConfig() {
    return namespaceConfig;
  }

  public long insert(TermsAndValues record, Map<String, String> metadata) {
    LongTermsAndValues encodedRecord = toLongTermsAndValues(record);
    Cache cacheToGraduate;
    long rowNum;
    lock.writeLock().lock();
    try {
      if (nextRowNum == Long.MAX_VALUE) {
        throw new IllegalStateException("nextRowNum has reached Long.MAX_VALUE.");
      }
      rowNum = nextRowNum++;
      if (!cache.insertWithRowNum(rowNum, encodedRecord, metadata)) {
        throw new IllegalStateException(
            String.format("rowNum %s already exists in cache.", rowNum));
      }
      cacheToGraduate = rotateCacheForGraduationIfReadyLocked();
    } finally {
      lock.writeLock().unlock();
    }
    if (cacheToGraduate != null) {
      scheduleGraduation(cacheToGraduate);
    }
    return rowNum;
  }

  public boolean delete(long rowNum) {
    lock.writeLock().lock();
    try {
      return deleteInternalLocked(rowNum);
    } finally {
      lock.writeLock().unlock();
    }
  }

  public boolean update(long rowNum, TermsAndValues record, Map<String, String> metadata) {
    LongTermsAndValues encodedRecord = toLongTermsAndValues(record);
    Cache cacheToGraduate;
    lock.writeLock().lock();
    try {
      if (!deleteInternalLocked(rowNum)) {
        return false;
      }
      /*
       * Updates keep the logical rowNum but move the current row version back into the active
       * mutable cache.
       */
      boolean inserted = cache.insertWithRowNum(rowNum, encodedRecord, metadata);
      if (!inserted) {
        throw new IllegalStateException(
            String.format("rowNum %s already exists in active cache after delete.", rowNum));
      }
      cacheToGraduate = rotateCacheForGraduationIfReadyLocked();
    } finally {
      lock.writeLock().unlock();
    }
    if (cacheToGraduate != null) {
      scheduleGraduation(cacheToGraduate);
    }
    return true;
  }

  public SearchResults getNearestNeighbors(
      int k, TermsAndValues record, MetaFilter metadataFilter) {
    if (k <= 0) {
      throw new IllegalArgumentException("k must be greater than 0.");
    }
    lock.readLock().lock();
    try {
      int maxResults = Math.min(k, namespaceConfig.getMaxNumSimilarities());
      return mergeSearchResultsLocked(
          /* topK */ true, record, metadataFilter, /* minSimilarity */ 0.0f, maxResults);
    } finally {
      lock.readLock().unlock();
    }
  }

  public SearchResults getSimilarRowNums(
      float minSimilarity, TermsAndValues record, MetaFilter metadataFilter) {
    if (minSimilarity < 0.0f || minSimilarity > 1.0f) {
      throw new IllegalArgumentException("minSimilarity must be in the range [0.0, 1.0].");
    }
    lock.readLock().lock();
    try {
      return mergeSearchResultsLocked(
          /* topK */ false,
          record,
          metadataFilter,
          minSimilarity,
          namespaceConfig.getMaxNumSimilarities());
    } finally {
      lock.readLock().unlock();
    }
  }

  long[] getAllRowNums() {
    long[] rowNums = getAll().keys().toArray();
    Arrays.sort(rowNums);
    return rowNums;
  }

  private LongObjectHashMap<LongTermsAndValues> getAll() {
    lock.readLock().lock();
    try {
      LongObjectHashMap<LongTermsAndValues> rows = new LongObjectHashMap<>();
      for (OrderedSearchableStructure structure : orderedSearchableStructuresLocked(false)) {
        rows.putAll(structure.getAll());
      }
      rows.putAll(cache.getAll());
      return rows;
    } finally {
      lock.readLock().unlock();
    }
  }

  public int size() {
    lock.readLock().lock();
    try {
      int size = cache.size();
      for (Cache graduatingCache : graduatingCaches) {
        size += graduatingCache.size();
      }
      for (Index index : indexes) {
        size += index.size();
      }
      return size;
    } finally {
      lock.readLock().unlock();
    }
  }

  boolean isReadyForGraduation() {
    lock.readLock().lock();
    try {
      return cache.isReadyForGraduation();
    } finally {
      lock.readLock().unlock();
    }
  }

  int getNumSearchableStructures() {
    lock.readLock().lock();
    try {
      return getNumSearchableStructuresLocked();
    } finally {
      lock.readLock().unlock();
    }
  }

  void awaitBackgroundTasks() {
    int numProcessed = 0;
    while (true) {
      Future<?> task;
      lock.readLock().lock();
      try {
        if (numProcessed >= backgroundTasks.size()) {
          return;
        }
        task = backgroundTasks.get(numProcessed);
        ++numProcessed;
      } finally {
        lock.readLock().unlock();
      }
      try {
        task.get();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException("Interrupted while waiting for background index tasks.", e);
      } catch (ExecutionException e) {
        throw new RuntimeException("Background index task failed.", e);
      }
    }
  }

  @Override
  public void close() {
    lock.writeLock().lock();
    try {
      for (Index index : indexes) {
        index.close();
      }
      indexes.clear();
      indexGenerations.clear();
    } finally {
      lock.writeLock().unlock();
      backgroundExecutor.shutdownNow();
    }
  }

  private static ExecutorService createBackgroundExecutor() {
    int numThreads =
        Math.max(2, Math.min(Runtime.getRuntime().availableProcessors(), MAX_BACKGROUND_THREADS));
    AtomicInteger threadNumber = new AtomicInteger(1);
    return Executors.newFixedThreadPool(
        numThreads,
        runnable -> {
          Thread thread =
              new Thread(
                  runnable,
                  "ussi-searchable-structure-background-" + threadNumber.getAndIncrement());
          thread.setDaemon(true);
          return thread;
        });
  }

  private LongTermsAndValues toLongTermsAndValues(TermsAndValues record) {
    Objects.requireNonNull(record, "record");
    return LongTermsAndValues.from(record, NearestNeighborSearchIndex::encodeTerm, comparator);
  }

  private static long encodeTerm(String term) {
    return LongMeta.longHashCode(term.toLowerCase(Locale.ROOT));
  }

  @Nullable
  private Cache rotateCacheForGraduationIfReadyLocked() {
    if (!cache.isReadyForGraduation()) {
      return null;
    }
    Cache cacheToGraduate = cache;
    /*
     * After rotation, this cache no longer receives inserts or updates. It remains searchable and
     * deletable while the background task builds an index from its snapshot.
     */
    graduatingCaches.add(cacheToGraduate);
    graduationDeletes.put(cacheToGraduate, new LongHashSet());
    graduatingCacheGenerations.put(cacheToGraduate, nextStructureGeneration++);
    cache = CacheFactory.createCache(namespaceConfig);
    return cacheToGraduate;
  }

  private void scheduleGraduation(Cache cacheToGraduate) {
    Future<?> task = backgroundExecutor.submit(() -> graduateCache(cacheToGraduate));
    lock.writeLock().lock();
    try {
      backgroundTasks.add(task);
    } finally {
      lock.writeLock().unlock();
    }
  }

  /**
   * Builds an index from a graduating cache. The cache stays whole and searchable throughout: its
   * contents are snapshotted under the read lock, the index is built off-lock so searches and
   * deletes keep running, and the write lock is taken only for the swap. Rows deleted from the
   * cache during the build are accumulated in a per-cache tombstone set (see {@link
   * #deleteInternalLocked}) and replayed onto the freshly built index at swap time, so the
   * write-lock window is O(deletes-during-build) rather than O(cache size).
   */
  private void graduateCache(Cache cacheToGraduate) {
    LongObjectHashMap<LongTermsAndValues> snapshotRows;
    LongObjectHashMap<LongMeta> snapshotMetadata;
    lock.readLock().lock();
    try {
      if (!graduatingCaches.contains(cacheToGraduate)) {
        return;
      }
      snapshotRows = cacheToGraduate.getAll();
      snapshotMetadata = cacheToGraduate.getAllMetadata();
    } finally {
      lock.readLock().unlock();
    }

    Index newIndex =
        snapshotRows.isEmpty()
            ? null
            : IndexFactory.createIndex(namespaceConfig, snapshotRows, snapshotMetadata);

    boolean graduated = false;
    lock.writeLock().lock();
    try {
      if (graduatingCaches.remove(cacheToGraduate)) {
        int generation = removeGraduatingCacheGenerationLocked(cacheToGraduate);
        LongHashSet tombstones = graduationDeletes.remove(cacheToGraduate);
        if (newIndex != null) {
          applyTombstones(newIndex, tombstones);
          insertIndexLocked(newIndex, generation);
        }
        graduated = true;
      }
    } finally {
      lock.writeLock().unlock();
    }

    if (!graduated && newIndex != null) {
      newIndex.close();
    }
    consolidateIndexesIfNeeded();
  }

  /**
   * Merges the indexes into a single index when there are too many searchable structures. The old
   * indexes stay whole and searchable throughout: the index set is snapshotted, the merged index is
   * built off-lock so searches and deletes keep running, and the write lock is taken only for the
   * swap. Rows deleted from the old indexes during the build are accumulated in a tombstone set
   * (see {@link #deleteInternalLocked}) and replayed onto the merged index at swap time, so the
   * write-lock window is O(deletes-during-build) rather than O(total row count). Old indexes are
   * closed after the swap, outside the lock.
   */
  private void consolidateIndexesIfNeeded() {
    List<Index> oldIndexes;
    int consolidatedGeneration;
    LongHashSet tombstones = new LongHashSet();
    lock.writeLock().lock();
    try {
      if (consolidationInFlight
          || getNumSearchableStructuresLocked() < namespaceConfig.getMaxNumSearchableStructures()) {
        return;
      }
      oldIndexes = getConsolidatableIndexPrefixLocked();
      if (oldIndexes.size() <= 1) {
        return;
      }
      consolidatedGeneration = getLatestIndexGenerationLocked(oldIndexes);
      /*
       * Start routing index deletes into the tombstone set before snapshotting, so every deletion
       * that races the build is captured.
       */
      consolidationInFlight = true;
      consolidationDeletes = tombstones;
    } finally {
      lock.writeLock().unlock();
    }

    Index consolidatedIndex = null;
    boolean swapped = false;
    try {
      LongObjectHashMap<LongTermsAndValues> consolidatedRows = new LongObjectHashMap<>();
      LongObjectHashMap<LongMeta> consolidatedMetadata = new LongObjectHashMap<>();
      lock.readLock().lock();
      try {
        for (Index index : oldIndexes) {
          consolidatedRows.putAll(index.getAll());
          consolidatedMetadata.putAll(index.getAllMetadata());
        }
      } finally {
        lock.readLock().unlock();
      }

      consolidatedIndex =
          consolidatedRows.isEmpty()
              ? null
              : IndexFactory.createIndex(namespaceConfig, consolidatedRows, consolidatedMetadata);

      lock.writeLock().lock();
      try {
        consolidationDeletes = null;
        /*
         * Only replace the older prefix we built from. Any indexes appended by concurrent
         * graduations stay after the consolidated index, preserving oldest-to-newest ordering.
         */
        if (indexesStartWithSnapshotLocked(oldIndexes)) {
          List<Index> appendedIndexes =
              new ArrayList<>(indexes.subList(oldIndexes.size(), indexes.size()));
          for (Index oldIndex : oldIndexes) {
            indexGenerations.remove(oldIndex);
          }
          indexes.clear();
          if (consolidatedIndex != null) {
            applyTombstones(consolidatedIndex, tombstones);
            indexes.add(consolidatedIndex);
            indexGenerations.put(consolidatedIndex, consolidatedGeneration);
          }
          indexes.addAll(appendedIndexes);
          swapped = true;
        }
      } finally {
        lock.writeLock().unlock();
      }
    } finally {
      /*
       * Stop routing deletes into the sink even if the build above threw before the swap block ran.
       */
      clearConsolidationDeletes(tombstones);
    }

    if (swapped) {
      for (Index oldIndex : oldIndexes) {
        oldIndex.close();
      }
    } else if (consolidatedIndex != null) {
      consolidatedIndex.close();
    }
    if (getNumSearchableStructures() > namespaceConfig.getMaxNumSearchableStructures()) {
      consolidateIndexesIfNeeded();
    }
  }

  @SuppressWarnings("ReferenceEquality")
  private void clearConsolidationDeletes(LongHashSet tombstones) {
    lock.writeLock().lock();
    try {
      if (consolidationDeletes == tombstones) {
        consolidationDeletes = null;
      }
      consolidationInFlight = false;
    } finally {
      lock.writeLock().unlock();
    }
  }

  private boolean indexesStartWithSnapshotLocked(List<Index> snapshot) {
    if (indexes.size() < snapshot.size()) {
      return false;
    }
    for (int i = 0; i < snapshot.size(); ++i) {
      if (indexes.get(i) != snapshot.get(i)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Replays deletes accumulated during a cache graduation onto the freshly built index. Must be
   * called while holding the write lock. rowNums absent from the index (deleted before the build
   * snapshot was taken) are no-ops, so replaying the full set is safe and exact.
   */
  private static void applyTombstones(Index builtIndex, @Nullable LongHashSet tombstones) {
    if (tombstones == null) {
      return;
    }
    for (LongCursor rowNum : tombstones) {
      builtIndex.delete(rowNum.value);
    }
  }

  private boolean deleteInternalLocked(long rowNum) {
    if (cache.delete(rowNum)) {
      return true;
    }
    for (OrderedSearchableStructure structure : orderedSearchableStructuresLocked(true)) {
      if (structure.cache != null) {
        if (structure.cache.delete(rowNum)) {
          /*
           * The index build snapshots this cache after graduation starts, so replay deletes seen
           * during the build onto the new index at swap time.
           */
          LongHashSet tombstones = graduationDeletes.get(structure.cache);
          if (tombstones != null) {
            tombstones.add(rowNum);
          }
          return true;
        }
      } else if (structure.index != null && structure.index.delete(rowNum)) {
        /*
         * Indexes do not accept inserts or updates, but delete-only tombstoning lets the top-level
         * index hide rows that have already graduated. A consolidation in flight is building a
         * merged index from a snapshot of these indexes, so the deletion must be replayed onto it
         * at swap time.
         */
        if (consolidationDeletes != null) {
          consolidationDeletes.add(rowNum);
        }
        return true;
      }
    }
    return false;
  }

  private int getNumSearchableStructuresLocked() {
    return 1 + graduatingCaches.size() + indexes.size();
  }

  private void insertIndexLocked(Index newIndex, int generation) {
    /*
     * Background graduations can complete out of order, so indexes are inserted by generation
     * instead of append order.
     */
    int insertionPoint = 0;
    while (insertionPoint < indexes.size()
        && getIndexGenerationLocked(indexes.get(insertionPoint), insertionPoint) <= generation) {
      ++insertionPoint;
    }
    indexes.add(insertionPoint, newIndex);
    indexGenerations.put(newIndex, generation);
  }

  private List<Index> getConsolidatableIndexPrefixLocked() {
    /*
     * Only consolidate indexes older than the oldest graduating cache. Newer indexes may still be
     * interleaved with caches whose builds have not finished, and preserving that order keeps
     * newest-version semantics correct.
     */
    int oldestGraduatingCacheGeneration = getOldestGraduatingCacheGenerationLocked();
    List<Index> prefix = new ArrayList<>();
    for (int i = 0; i < indexes.size(); ++i) {
      Index index = indexes.get(i);
      if (getIndexGenerationLocked(index, i) >= oldestGraduatingCacheGeneration) {
        break;
      }
      prefix.add(index);
    }
    return prefix;
  }

  private int getOldestGraduatingCacheGenerationLocked() {
    int oldestGeneration = Integer.MAX_VALUE;
    for (int i = 0; i < graduatingCaches.size(); ++i) {
      oldestGeneration =
          Math.min(
              oldestGeneration, getGraduatingCacheGenerationLocked(graduatingCaches.get(i), i));
    }
    return oldestGeneration;
  }

  private int getLatestIndexGenerationLocked(List<Index> snapshot) {
    int latestGeneration = Integer.MIN_VALUE;
    for (int i = 0; i < snapshot.size(); ++i) {
      latestGeneration = Math.max(latestGeneration, getIndexGenerationLocked(snapshot.get(i), i));
    }
    return latestGeneration;
  }

  private int getIndexGenerationLocked(Index index, int position) {
    Integer generation = indexGenerations.get(index);
    if (generation == null) {
      throw new IllegalStateException(
          String.format("Missing generation for index at position %s.", position));
    }
    return generation.intValue();
  }

  private int getGraduatingCacheGenerationLocked(Cache graduatingCache, int position) {
    Integer generation = graduatingCacheGenerations.get(graduatingCache);
    if (generation == null) {
      throw new IllegalStateException(
          String.format("Missing generation for graduating cache at position %s.", position));
    }
    return generation.intValue();
  }

  private int removeGraduatingCacheGenerationLocked(Cache graduatingCache) {
    Integer generation = graduatingCacheGenerations.remove(graduatingCache);
    if (generation == null) {
      throw new IllegalStateException("Missing generation for graduating cache.");
    }
    return generation.intValue();
  }

  private List<OrderedSearchableStructure> orderedSearchableStructuresLocked(boolean newestFirst) {
    /*
     * Deletes and searches scan newest-first so an updated rowNum is found before older versions.
     * Snapshot and merge paths use oldest-first when reconstructing all visible rows.
     */
    List<OrderedSearchableStructure> structures =
        new ArrayList<>(graduatingCaches.size() + indexes.size());
    for (int i = 0; i < indexes.size(); ++i) {
      Index index = indexes.get(i);
      structures.add(
          OrderedSearchableStructure.forIndex(index, getIndexGenerationLocked(index, i), i));
    }
    for (int i = 0; i < graduatingCaches.size(); ++i) {
      Cache graduatingCache = graduatingCaches.get(i);
      structures.add(
          OrderedSearchableStructure.forCache(
              graduatingCache,
              getGraduatingCacheGenerationLocked(graduatingCache, i),
              indexes.size() + i));
    }
    structures.sort(
        (left, right) -> {
          int generationCompare =
              newestFirst
                  ? Integer.compare(right.generation, left.generation)
                  : Integer.compare(left.generation, right.generation);
          if (generationCompare != 0) {
            return generationCompare;
          }
          return newestFirst
              ? Integer.compare(right.tieBreaker, left.tieBreaker)
              : Integer.compare(left.tieBreaker, right.tieBreaker);
        });
    return structures;
  }

  private SearchResults mergeSearchResultsLocked(
      boolean topK,
      TermsAndValues record,
      MetaFilter metadataFilter,
      float minSimilarity,
      int maxResults) {
    LongTermsAndValues encodedRecord = toLongTermsAndValues(record);
    BoundedSizeMaxHeap<RowNumAndSimilarity> rows =
        new BoundedSizeMaxHeap<>(maxResults, RowNumAndSimilarity.TOP_RESULTS_HEAP_ORDER);
    if (topK) {
      rows.addAll(cache.getNearestNeighbors(maxResults, encodedRecord, metadataFilter));
      for (OrderedSearchableStructure structure : orderedSearchableStructuresLocked(true)) {
        rows.addAll(structure.getNearestNeighbors(maxResults, encodedRecord, metadataFilter));
      }
    } else {
      rows.addAll(cache.getSimilarRowNums(minSimilarity, encodedRecord, metadataFilter));
      for (OrderedSearchableStructure structure : orderedSearchableStructuresLocked(true)) {
        rows.addAll(structure.getSimilarRowNums(minSimilarity, encodedRecord, metadataFilter));
      }
    }
    List<RowNumAndSimilarity> sortedRows = rows.toSortedList(RowNumAndSimilarity.NEAREST_FIRST);
    long[] rowNums = new long[sortedRows.size()];
    float[] similarities = new float[sortedRows.size()];
    for (int i = 0; i < sortedRows.size(); ++i) {
      RowNumAndSimilarity row = sortedRows.get(i);
      rowNums[i] = row.getRowNum();
      similarities[i] = row.getSimilarity();
    }
    return new SearchResults(rowNums, similarities);
  }

  private static final class OrderedSearchableStructure {
    private final int generation;
    private final int tieBreaker;
    @Nullable private final Cache cache;
    @Nullable private final Index index;

    private OrderedSearchableStructure(
        int generation, int tieBreaker, @Nullable Cache cache, @Nullable Index index) {
      this.generation = generation;
      this.tieBreaker = tieBreaker;
      this.cache = cache;
      this.index = index;
    }

    private static OrderedSearchableStructure forCache(
        Cache cache, int generation, int tieBreaker) {
      return new OrderedSearchableStructure(generation, tieBreaker, cache, null);
    }

    private static OrderedSearchableStructure forIndex(
        Index index, int generation, int tieBreaker) {
      return new OrderedSearchableStructure(generation, tieBreaker, null, index);
    }

    private LongObjectHashMap<LongTermsAndValues> getAll() {
      if (cache != null) {
        return cache.getAll();
      }
      return Objects.requireNonNull(index).getAll();
    }

    private List<RowNumAndSimilarity> getNearestNeighbors(
        int k, LongTermsAndValues record, MetaFilter metadataFilter) {
      if (cache != null) {
        return cache.getNearestNeighbors(k, record, metadataFilter);
      }
      return Objects.requireNonNull(index).getNearestNeighbors(k, record, metadataFilter);
    }

    private List<RowNumAndSimilarity> getSimilarRowNums(
        float minSimilarity, LongTermsAndValues record, MetaFilter metadataFilter) {
      if (cache != null) {
        return cache.getSimilarRowNums(minSimilarity, record, metadataFilter);
      }
      return Objects.requireNonNull(index).getSimilarRowNums(minSimilarity, record, metadataFilter);
    }
  }
}
