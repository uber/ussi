/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi;

import com.carrotsearch.hppc.LongHashSet;
import com.carrotsearch.hppc.LongObjectHashMap;
import com.carrotsearch.hppc.cursors.LongCursor;
import com.uber.ussi.comparator.Comparator;
import com.uber.ussi.comparator.ComparatorConfigValidator;
import com.uber.ussi.comparator.ComparatorFactory;
import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.entity.meta.LongMeta;
import com.uber.ussi.entity.meta.MetaFilter;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;
import com.uber.ussi.entity.termsandvalues.TermsAndValues;
import com.uber.ussi.searchablestructure.ParallelismBudget;
import com.uber.ussi.searchablestructure.RowNumAndSimilarity;
import com.uber.ussi.searchablestructure.SearchThreads;
import com.uber.ussi.searchablestructure.cache.Cache;
import com.uber.ussi.searchablestructure.cache.CacheConfigValidator;
import com.uber.ussi.searchablestructure.cache.CacheFactory;
import com.uber.ussi.searchablestructure.index.Index;
import com.uber.ussi.searchablestructure.index.IndexConfigValidator;
import com.uber.ussi.searchablestructure.index.IndexFactory;
import com.uber.ussi.searchablestructure.TopResults;
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
  // Deletes that race a background cache graduation, replayed onto the new index at swap time.
  private final Map<Cache, LongHashSet> graduationDeletes;
  private final Map<Cache, Integer> graduatingCacheGenerations;
  private final Map<Index, Integer> indexGenerations;
  private final List<Future<?>> backgroundTasks;
  // Deletes that race an index consolidation, replayed onto the merged index at swap time.
  @Nullable private LongHashSet consolidationDeletes;
  private final ExecutorService backgroundExecutor;
  private final ReadWriteLock lock;
  private final QueryAdmission queryAdmission;
  private final Comparator comparator;
  private Cache cache;
  private long nextRowNum;
  private int nextStructureGeneration;
  private boolean consolidationInFlight;
  private boolean closed;

  public NearestNeighborSearchIndex(NamespaceConfig namespaceConfig) {
    this(namespaceConfig, createBackgroundExecutor());
  }

  NearestNeighborSearchIndex(NamespaceConfig namespaceConfig, ExecutorService backgroundExecutor) {
    this.namespaceConfig = Objects.requireNonNull(namespaceConfig, "namespaceConfig");
    // A namespace holds a cache and the indexes it graduates into, so all their rules must hold.
    this.namespaceConfig.validate(
        CacheConfigValidator.getInstance(),
        IndexConfigValidator.getInstance(),
        ComparatorConfigValidator.getInstance());
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
    this.queryAdmission = QueryAdmission.shared();
    // Composition lives here: admission counts the searches and can quiet them, the budget decides
    // what that concurrency is worth, and neither needs to know about the other.
    ParallelismBudget.shared()
        .attach(queryAdmission::takePeakInFlight, queryAdmission::runExclusively);
    this.nextRowNum = 0;
    this.nextStructureGeneration = 0;
    this.consolidationInFlight = false;
    this.closed = false;
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
      // Updates keep the rowNum but move the current row version back into the active cache.
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

  public SearchResults getNearestNeighborRowNums(
      int k, TermsAndValues record, MetaFilter metadataFilter) {
    if (k <= 0) {
      throw new IllegalArgumentException("k must be greater than 0.");
    }
    // Admission is taken before the read lock so that searches queued for a turn do not hold the
    // lock and stall an insert, delete or update.
    queryAdmission.acquire();
    try {
      lock.readLock().lock();
      try {
        int maxResults = Math.min(k, namespaceConfig.getMaxNumSimilarities());
        // One ticket for the whole traversal, so every structure this query visits is served at
        // the query's arrival rather than at the arrival of each structure's own search.
        SearchResults[] results = new SearchResults[1];
        SearchThreads.runUnderOneTicket(
            () ->
                results[0] =
                    mergeSearchResultsLocked(
                        /* topK */ true,
                        record,
                        metadataFilter,
                        /* minSimilarity */ 0.0f,
                        maxResults));
        return results[0];
      } finally {
        lock.readLock().unlock();
      }
    } finally {
      queryAdmission.release();
    }
  }

  public SearchResults getSimilarRowNums(
      float minSimilarity, TermsAndValues record, MetaFilter metadataFilter) {
    if (minSimilarity < 0.0f || minSimilarity > 1.0f) {
      throw new IllegalArgumentException("minSimilarity must be in the range [0.0, 1.0].");
    }
    queryAdmission.acquire();
    try {
      lock.readLock().lock();
      try {
        SearchResults[] results = new SearchResults[1];
        SearchThreads.runUnderOneTicket(
            () ->
                results[0] =
                    mergeSearchResultsLocked(
                        /* topK */ false,
                        record,
                        metadataFilter,
                        minSimilarity,
                        namespaceConfig.getMaxNumSimilarities()));
        return results[0];
      } finally {
        lock.readLock().unlock();
      }
    } finally {
      queryAdmission.release();
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
      if (closed) {
        return;
      }
      closed = true;
      ParallelismBudget.shared().detach();
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
    // A rotated cache takes no more inserts, but stays searchable and deletable during the build.
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
   * Builds an index from a graduating cache: snapshot under the read lock, build off-lock, swap
   * under the write lock. Deletes that race the build are tombstoned (see {@link
   * #deleteInternalLocked}) and replayed at swap time, so the write-lock window is
   * O(deletes-during-build) rather than O(cache size).
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
   * Merges the indexes when there are too many searchable structures: snapshot under the read
   * lock, build off-lock, swap under the write lock, then close the old indexes outside the lock.
   * Deletes that race the build are tombstoned (see {@link #deleteInternalLocked}) and replayed at
   * swap time, so the write-lock window is O(deletes-during-build) rather than O(total rows).
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
      // Route deletes into the tombstone set before snapshotting, so no racing delete is missed.
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
        // Replace only the prefix we built from, so indexes appended by concurrent graduations
        // stay after the consolidated index and oldest-to-newest ordering holds.
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
      // Stop routing deletes into the sink even if the build threw before the swap.
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
   * Replays deletes accumulated during a build onto the freshly built index. Must hold the write
   * lock. rowNums absent from the index are no-ops, so replaying the full set is exact.
   */
  private static void applyTombstones(Index builtIndex, @Nullable LongHashSet tombstones) {
    if (tombstones == null) {
      return;
    }
    for (LongCursor rowNum : tombstones) {
      builtIndex.delete(rowNum.value);
    }
  }
  
  /**
   * Deletes from the active cache if present, otherwise scans searchable structures newest to
   * oldest and tombstones the first match, since each rowNum lives in exactly one structure. A
   * delete against a structure whose build is in flight is also recorded in {@link
   * #graduationDeletes} or {@link #consolidationDeletes} for replay at swap time. Must hold the
   * write lock.
   */
  private boolean deleteInternalLocked(long rowNum) {
    if (cache.delete(rowNum)) {
      return true;
    }
    for (OrderedSearchableStructure structure : orderedSearchableStructuresLocked(true)) {
      if (structure.cache != null) {
        if (structure.cache.delete(rowNum)) {
          LongHashSet tombstones = graduationDeletes.get(structure.cache);
          if (tombstones != null) {
            tombstones.add(rowNum);
          }
          return true;
        }
      } else if (structure.index != null && structure.index.delete(rowNum)) {
        // Indexes are delete-only; tombstoning hides rows that have already graduated.
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
    // Graduations can complete out of order, so insert by generation rather than append order.
    int insertionPoint = 0;
    while (insertionPoint < indexes.size()
        && getIndexGenerationLocked(indexes.get(insertionPoint), insertionPoint) <= generation) {
      ++insertionPoint;
    }
    indexes.add(insertionPoint, newIndex);
    indexGenerations.put(newIndex, generation);
  }

  private List<Index> getConsolidatableIndexPrefixLocked() {
    // Only consolidate indexes older than the oldest graduating cache; newer ones may interleave
    // with caches whose builds are unfinished, and that order is what makes the newest win.
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
    // Deletes scan newest-first so an updated rowNum is found before older versions. Searches and
    // the snapshot and merge paths use oldest-first, the former because the oldest structure is the
    // largest and so raises a result floor the rest can use.
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

  /**
   * The floor the next structure may search above, once enough rows are held to have one. Nothing
   * below what {@code rows} already holds can reach the answer, so the weakest held score is the
   * floor, one step below it so that a row scoring exactly as well still qualifies. The floor only
   * rises, and never below what the caller asked for.
   */
  static float tightenedFloor(
      BoundedSizeMaxHeap<RowNumAndSimilarity> rows, float floor) {
    if (!rows.isFull()) {
      return floor;
    }
    return Math.max(floor, (float) TopResults.getConservativeMinSimilarity(rows));
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
    // Each structure is told the weakest score the answer already holds enough of, so it can stop
    // scoring rows that cannot reach the answer. Both searches are capped at their result count, so
    // both may raise the floor, and both visit the structures in the same order so that they prune
    // alike. An update deletes a row before re-inserting it, so one structure holds any given row:
    // which order the structures are visited in cannot change the version a search finds, and the
    // rows counted towards the floor are that many distinct rows. The order is therefore free, and
    // what it decides is how soon the floor rises.
    //
    // The structures are visited one after another rather than at once. Their sizes differ by
    // orders of magnitude, so the longest search would decide the latency either way, and visiting
    // them in turn lets each one narrow the next, which searches running side by side cannot do.
    // Oldest structure first, which is the largest: caches graduate at one size and older indexes
    // are consolidated into bigger ones, so the oldest holds the most rows and is the likeliest to
    // hold the answer's best. Raising the floor there first is what the structures after it spend.
    // The active cache is the newest and smallest, so it comes last. Order is free to choose: an
    // update deletes a row before re-inserting it, so one structure holds any given row and no
    // order can change the version a search finds.
    float floor = minSimilarity;
    if (topK) {
      for (OrderedSearchableStructure structure : orderedSearchableStructuresLocked(false)) {
        floor = tightenedFloor(rows, floor);
        rows.addAll(
            structure.getNearestNeighborRowNums(maxResults, encodedRecord, metadataFilter, floor));
      }
      floor = tightenedFloor(rows, floor);
      rows.addAll(
          cache.getNearestNeighborRowNums(maxResults, encodedRecord, metadataFilter, floor));
    } else {
      for (OrderedSearchableStructure structure : orderedSearchableStructuresLocked(false)) {
        floor = tightenedFloor(rows, floor);
        rows.addAll(structure.getSimilarRowNums(floor, encodedRecord, metadataFilter));
      }
      floor = tightenedFloor(rows, floor);
      rows.addAll(cache.getSimilarRowNums(floor, encodedRecord, metadataFilter));
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

    private List<RowNumAndSimilarity> getNearestNeighborRowNums(
        int k, LongTermsAndValues record, MetaFilter metadataFilter, float minSimilarity) {
      if (cache != null) {
        return cache.getNearestNeighborRowNums(k, record, metadataFilter, minSimilarity);
      }
      return Objects.requireNonNull(index)
          .getNearestNeighborRowNums(k, record, metadataFilter, minSimilarity);
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
