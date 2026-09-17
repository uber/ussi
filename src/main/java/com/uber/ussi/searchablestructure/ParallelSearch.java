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
 * Runs several searches of one structure at the same time, and keeps the nearest rows across all of
 * them.
 *
 * <p>A structure is searched this way either as shards or as ranges of its rows. Either way every
 * search is submitted before any is waited on, one of them runs on the calling thread, and what
 * each keeps is merged once all of them have finished.
 *
 * <p>Each search keeps its own heap. No heap is shared between threads.
 */
final class ParallelSearch {

  /**
   * Threads the searches are handed off to. Sized to the cores, which is what the searches in
   * flight demand together: searches are admitted up to the core count, and searching one
   * structure is worth no more threads than that.
   *
   * <p>One pool serves both ways of dividing a search, so the threads a structure takes do not
   * depend on which way it divides, and so the two cannot each claim the cores.
   */
  private static final ExecutorService SEARCHERS = createSearchers();

  private ParallelSearch() {}

  /** One of the searches, against a minimum similarity shared with the others. */
  public interface Searcher {
    List<RowNumAndSimilarity> search(int searchNumber, SharedMinSimilarity sharedMinSimilarity);
  }

  /**
   * The nearest {@code maxResults} rows across {@code numSearches} searches.
   *
   * <p>The pool starts searches in the order they were submitted, so a caller that submitted some
   * of its searches and then returned for the rest would have those later ones queued behind the
   * searches of every caller that arrived in the meantime. Submitting them all at once is what
   * keeps callers served in the order they arrived.
   *
   * <p>The calling thread runs one of the searches rather than only waiting. This uses the thread
   * already here, and it keeps the work moving when every pool thread is busy.
   *
   * <p>The searches share one minimum similarity, seeded at {@code minSimilarity}, so that each
   * prunes at what any of them has proved. That recovers the pruning they lose by keeping separate
   * heaps.
   */
  static List<RowNumAndSimilarity> inParallel(
      int numSearches, int maxResults, float minSimilarity, Searcher searcher) {
    SharedMinSimilarity sharedMinSimilarity = new SharedMinSimilarity(minSimilarity);
    BoundedSizeMaxHeap<RowNumAndSimilarity> nearestRowNums =
        new BoundedSizeMaxHeap<>(maxResults, RowNumAndSimilarity.TOP_RESULTS_HEAP_ORDER);
    if (numSearches <= 0) {
      return nearestRowNums.toList();
    }
    if (numSearches == 1) {
      // Searched on the calling thread, so one search alone pays no hand-off.
      nearestRowNums.addAll(searcher.search(0, sharedMinSimilarity));
      return nearestRowNums.toList();
    }
    List<Future<List<RowNumAndSimilarity>>> handedOffSearches = new ArrayList<>(numSearches - 1);
    for (int searchNumber = 1; searchNumber < numSearches; searchNumber++) {
      int handedOff = searchNumber;
      handedOffSearches.add(
          SEARCHERS.submit(() -> searcher.search(handedOff, sharedMinSimilarity)));
    }
    nearestRowNums.addAll(searcher.search(0, sharedMinSimilarity));
    addHandedOff(handedOffSearches, nearestRowNums);
    return nearestRowNums.toList();
  }

  /**
   * Adds the rows every handed-off search kept, waiting for all of them even once one has failed.
   *
   * <p>A failed search could be left to finish on its own, but only by returning while it still
   * held the structure, which the read lock the caller searches under would no longer be
   * protecting. Waiting costs the rest of work that is going to throw, and it keeps every search
   * inside the lock that makes reading the structure safe.
   */
  private static void addHandedOff(
      List<Future<List<RowNumAndSimilarity>>> handedOffSearches,
      BoundedSizeMaxHeap<RowNumAndSimilarity> nearestRowNums) {
    RuntimeException failure = null;
    boolean interrupted = false;
    for (Future<List<RowNumAndSimilarity>> handedOffSearch : handedOffSearches) {
      try {
        nearestRowNums.addAll(handedOffSearch.get());
      } catch (InterruptedException e) {
        interrupted = true;
        failure = failure != null ? failure : new IllegalStateException(SEARCH_FAILED, e);
      } catch (ExecutionException e) {
        // An undivided search would have thrown this from the caller's thread, so it is rethrown.
        RuntimeException thrown =
            e.getCause() instanceof RuntimeException runtimeCause
                ? runtimeCause
                : new IllegalStateException(SEARCH_FAILED, e.getCause());
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

  private static final String SEARCH_FAILED = "Failed to run one of the searches.";

  private static ExecutorService createSearchers() {
    AtomicInteger threadNumber = new AtomicInteger(1);
    return Executors.newFixedThreadPool(
        Math.max(1, Runtime.getRuntime().availableProcessors()),
        runnable -> {
          Thread thread = new Thread(runnable, "ussi-search-" + threadNumber.getAndIncrement());
          thread.setDaemon(true);
          return thread;
        });
  }
}
