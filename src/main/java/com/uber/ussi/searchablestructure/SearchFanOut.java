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
 * Searches the parts of one structure, as many at a time as the search may use threads, and keeps
 * the best rows across all of them.
 *
 * <p>Every part is searched however many threads the search has, so a structure divided into more
 * parts than the budget allows is searched in waves rather than left unsearched. This is what makes
 * the part count a property of the structure rather than of the load: the parts are fixed when the
 * structure is built, and only how many run at once follows the budget.
 *
 * <p>Parts keep their own heaps and are merged at the end, so each prunes from its own k-th best
 * rather than from the answer as a whole. That is weaker pruning than one heap would give, and it
 * is what buys the parts their independence.
 */
public final class SearchFanOut {

  /**
   * Threads for the parts a search hands off. Sized to the cores for the same reason {@link
   * ScanSplit}'s pool is: searches are admitted up to the core count and each divides the cores
   * among its own parts. A structure hands off to one pool or the other and never to both, since
   * the structures that divide rows within a part are not the ones divided into parts.
   */
  private static final ExecutorService SEARCHERS = createSearchers();

  private SearchFanOut() {}

  /** Searches one part, returning the rows it keeps. */
  public interface PartSearch {
    List<RowNumAndSimilarity> search(int part);
  }

  /**
   * The best {@code maxResults} rows across {@code numParts} parts, searching {@code atOnce} of
   * them at a time. The calling thread searches one part of every wave rather than waiting on all
   * of them.
   */
  public static List<RowNumAndSimilarity> inWaves(
      int numParts, int atOnce, int maxResults, PartSearch partSearch) {
    if (numParts <= 0) {
      return List.of();
    }
    BoundedSizeMaxHeap<RowNumAndSimilarity> merged =
        new BoundedSizeMaxHeap<>(maxResults, RowNumAndSimilarity.TOP_RESULTS_HEAP_ORDER);
    if (atOnce <= 1) {
      // Searched on the calling thread, so a search without threads to spare pays no hand-off.
      for (int part = 0; part < numParts; part++) {
        merged.addAll(partSearch.search(part));
      }
      return merged.toList();
    }
    for (int firstOfWave = 0; firstOfWave < numParts; firstOfWave += atOnce) {
      int afterWave = Math.min(numParts, firstOfWave + atOnce);
      List<Future<List<RowNumAndSimilarity>>> handedOff = new ArrayList<>(afterWave - firstOfWave);
      for (int part = firstOfWave + 1; part < afterWave; part++) {
        int handedOffPart = part;
        handedOff.add(SEARCHERS.submit(() -> partSearch.search(handedOffPart)));
      }
      merged.addAll(partSearch.search(firstOfWave));
      for (Future<List<RowNumAndSimilarity>> part : handedOff) {
        merged.addAll(awaitPart(part, handedOff));
      }
    }
    return merged.toList();
  }

  /** The rows a part kept, abandoning the remaining parts if it did not produce them. */
  private static List<RowNumAndSimilarity> awaitPart(
      Future<List<RowNumAndSimilarity>> part, List<Future<List<RowNumAndSimilarity>>> parts) {
    try {
      return part.get();
    } catch (InterruptedException e) {
      cancel(parts);
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while searching part of the structure.", e);
    } catch (ExecutionException e) {
      cancel(parts);
      // A sequential search would have thrown this from the caller's thread, so it is rethrown.
      if (e.getCause() instanceof RuntimeException) {
        throw (RuntimeException) e.getCause();
      }
      throw new IllegalStateException("Failed to search part of the structure.", e.getCause());
    }
  }

  private static void cancel(List<Future<List<RowNumAndSimilarity>>> parts) {
    for (Future<List<RowNumAndSimilarity>> part : parts) {
      part.cancel(true);
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
