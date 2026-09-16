/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure;

import com.uber.ussi.utils.BoundedSizeMaxHeap;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Searches the shards of one structure and keeps the nearest rows across all of them.
 *
 * <p>Every shard is searched. A structure's rows are divided between its shards, so a shard left
 * out would take its rows out of the answer. What the thread budget decides is only how many
 * shards are searched at the same time.
 *
 * <p>Shards keep their own heaps and are merged at the end. Each therefore prunes using its own
 * k-th nearest row rather than the answer's.
 *
 * <p>This returns only once every shard it handed off has finished. Callers search under the
 * engine's read lock, which excludes writers from the shards for exactly as long as the search
 * holds it. A handed-off shard that outlived the call could therefore read a structure a writer had
 * begun to change, or an index that consolidation had closed.
 */
public final class ShardFanOut {

  /**
   * Threads for the shards a search hands off. Sized to the cores for the same reason {@link
   * ScanSplit}'s pool is: searches are admitted up to the core count, and each divides the cores
   * among its own shards. A structure hands off to one pool or the other and never to both, because
   * the structures divided into shards are not the ones that divide the rows of a single search.
   */
  private static final ExecutorService SEARCHERS = createSearchers();

  private ShardFanOut() {}

  /** Searches one shard, returning the rows it keeps. */
  public interface ShardSearch {
    List<RowNumAndSimilarity> search(int shard);
  }

  /**
   * The nearest {@code maxResults} rows across {@code numShards} shards, searching {@code
   * numShardsAtOnce} of them at the same time.
   *
   * <p>The calling thread searches one of the shards it is waiting on. This uses the thread already
   * here, and it keeps the search moving when every pool thread is busy.
   */
  public static List<RowNumAndSimilarity> search(
      int numShards, int numShardsAtOnce, int maxResults, ShardSearch shardSearch) {
    BoundedSizeMaxHeap<RowNumAndSimilarity> nearestRowNums =
        new BoundedSizeMaxHeap<>(maxResults, RowNumAndSimilarity.TOP_RESULTS_HEAP_ORDER);
    if (numShardsAtOnce <= 1) {
      // Searched on the calling thread, so a search without threads to spare pays no hand-off.
      for (int shard = 0; shard < numShards; shard++) {
        nearestRowNums.addAll(shardSearch.search(shard));
      }
      return nearestRowNums.toList();
    }
    for (int firstShard = 0; firstShard < numShards; firstShard += numShardsAtOnce) {
      int afterLastShard = Math.min(numShards, firstShard + numShardsAtOnce);
      List<Future<List<RowNumAndSimilarity>>> handedOffShards =
          new ArrayList<>(afterLastShard - firstShard - 1);
      for (int shard = firstShard + 1; shard < afterLastShard; shard++) {
        int handedOffShard = shard;
        handedOffShards.add(SEARCHERS.submit(() -> shardSearch.search(handedOffShard)));
      }
      nearestRowNums.addAll(shardSearch.search(firstShard));
      addHandedOffShards(handedOffShards, nearestRowNums);
    }
    return nearestRowNums.toList();
  }

  /**
   * Adds the rows every handed-off shard kept, waiting for all of them even once one has failed.
   *
   * <p>A failed shard could be left to finish on its own, but only by returning while it still held
   * the structure, which the read lock the caller searches under would no longer be protecting.
   * Waiting costs the rest of a search that is going to throw, and it keeps every shard inside the
   * lock that makes reading it safe.
   */
  private static void addHandedOffShards(
      List<Future<List<RowNumAndSimilarity>>> handedOffShards,
      BoundedSizeMaxHeap<RowNumAndSimilarity> nearestRowNums) {
    RuntimeException failure = null;
    boolean interrupted = false;
    for (Future<List<RowNumAndSimilarity>> shard : handedOffShards) {
      try {
        nearestRowNums.addAll(shard.get());
      } catch (InterruptedException e) {
        interrupted = true;
        failure = failure != null ? failure : new IllegalStateException(searchFailed(), e);
      } catch (ExecutionException e) {
        // A search of every shard in turn would have thrown this from the caller's thread.
        RuntimeException thrown =
            e.getCause() instanceof RuntimeException runtimeCause
                ? runtimeCause
                : new IllegalStateException(searchFailed(), e.getCause());
        failure = failure != null ? failure : thrown;
      }
    }
    if (interrupted) {
      Thread.currentThread().interrupt();
    }
    if (failure != null) {
      throw failure;
    }
  }

  private static String searchFailed() {
    return "Failed to search a shard of the structure.";
  }

  private static ExecutorService createSearchers() {
    AtomicInteger threadNumber = new AtomicInteger(1);
    return Executors.newFixedThreadPool(
        Math.max(1, Runtime.getRuntime().availableProcessors()),
        runnable -> {
          Thread thread = new Thread(runnable, "ussi-shard-" + threadNumber.getAndIncrement());
          thread.setDaemon(true);
          return thread;
        });
  }
}
