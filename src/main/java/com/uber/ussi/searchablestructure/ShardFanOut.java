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
 * Searches the shards of one structure and keeps the best rows across all of them.
 *
 * <p>Every shard is searched, since a structure's rows are divided between them and a shard left
 * out would take its rows out of the answer. What follows the thread budget is how many are
 * searched at the same time, so a structure with more shards than the budget searches them in
 * groups.
 *
 * <p>Shards keep their own heaps and are merged at the end, so each prunes from its own k-th best
 * rather than from the answer as a whole.
 */
public final class ShardFanOut {

  /**
   * Threads for the shards a search hands off. Sized to the cores for the same reason {@link
   * ScanSplit}'s pool is: searches are admitted up to the core count and each divides the cores
   * among its own shards. A structure hands off to one pool or the other and never to both, since
   * the structures divided into shards are not the ones dividing the rows of a single search.
   */
  private static final ExecutorService SEARCHERS = createSearchers();

  private ShardFanOut() {}

  /** Searches one shard, returning the rows it keeps. */
  public interface ShardSearch {
    List<RowNumAndSimilarity> search(int shard);
  }

  /**
   * The best {@code maxResults} rows across {@code numShards} shards, searching {@code
   * numShardsAtOnce} of them at the same time. The calling thread searches one shard of every group
   * rather than waiting on all of them.
   */
  public static List<RowNumAndSimilarity> search(
      int numShards, int numShardsAtOnce, int maxResults, ShardSearch shardSearch) {
    BoundedSizeMaxHeap<RowNumAndSimilarity> bestRows =
        new BoundedSizeMaxHeap<>(maxResults, RowNumAndSimilarity.TOP_RESULTS_HEAP_ORDER);
    if (numShardsAtOnce <= 1) {
      // Searched on the calling thread, so a search without threads to spare pays no hand-off.
      for (int shard = 0; shard < numShards; shard++) {
        bestRows.addAll(shardSearch.search(shard));
      }
      return bestRows.toList();
    }
    for (int firstOfGroup = 0; firstOfGroup < numShards; firstOfGroup += numShardsAtOnce) {
      int afterGroup = Math.min(numShards, firstOfGroup + numShardsAtOnce);
      List<Future<List<RowNumAndSimilarity>>> handedOff =
          new ArrayList<>(afterGroup - firstOfGroup - 1);
      for (int shard = firstOfGroup + 1; shard < afterGroup; shard++) {
        int handedOffShard = shard;
        handedOff.add(SEARCHERS.submit(() -> shardSearch.search(handedOffShard)));
      }
      bestRows.addAll(shardSearch.search(firstOfGroup));
      for (Future<List<RowNumAndSimilarity>> shard : handedOff) {
        bestRows.addAll(awaitShard(shard, handedOff));
      }
    }
    return bestRows.toList();
  }

  /** The rows a shard kept, abandoning the shards still in flight if it did not produce them. */
  private static List<RowNumAndSimilarity> awaitShard(
      Future<List<RowNumAndSimilarity>> shard, List<Future<List<RowNumAndSimilarity>>> shards) {
    try {
      return shard.get();
    } catch (InterruptedException e) {
      cancel(shards);
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while searching a shard.", e);
    } catch (ExecutionException e) {
      cancel(shards);
      // A search of every shard in turn would have thrown this from the caller's thread.
      if (e.getCause() instanceof RuntimeException) {
        throw (RuntimeException) e.getCause();
      }
      throw new IllegalStateException("Failed to search a shard.", e.getCause());
    }
  }

  private static void cancel(List<Future<List<RowNumAndSimilarity>>> shards) {
    for (Future<List<RowNumAndSimilarity>> shard : shards) {
      shard.cancel(true);
    }
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
