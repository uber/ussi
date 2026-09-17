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
import java.util.function.IntFunction;

/**
 * Runs the searches one search is divided into, and keeps the nearest rows across all of them.
 *
 * <p>Both ways of dividing a search use this. {@link ParallelShardSearch} runs one complete search
 * per shard of a structure, and {@link ParallelRowScan} runs one range of a single structure's rows
 * per thread. What they share is this dispatch: submit every division at once, search one of them on
 * the calling thread, then wait for all of them and merge what each kept.
 *
 * <p>Each division keeps its own heap, and the heaps are merged once all of them have finished. No
 * heap is shared between threads.
 */
final class ParallelSearch {

  /**
   * Threads the divisions of a search are handed off to. Sized to the cores, which is what the
   * searches in flight demand together: searches are admitted up to the core count, and a search's
   * divisions are worth no more threads than that.
   *
   * <p>One pool serves both ways of dividing a search, so the threads a structure takes do not
   * depend on which way it divides, and so the two cannot each claim the cores.
   */
  private static final ExecutorService SEARCHERS = createSearchers();

  private ParallelSearch() {}

  /**
   * The nearest {@code maxResults} rows across {@code numDivisions} divisions of one search.
   *
   * <p>Every division is submitted before any is waited on. The pool starts them in the order they
   * were submitted, so a search that submitted only some of its divisions and then returned for the
   * rest would have those later ones queued behind the divisions of every search that arrived in the
   * meantime.
   *
   * <p>The calling thread searches one division rather than only waiting. This uses the thread
   * already here, and it keeps the search moving when every pool thread is busy.
   */
  static List<RowNumAndSimilarity> inParallel(
      int numDivisions, int maxResults, IntFunction<List<RowNumAndSimilarity>> search) {
    BoundedSizeMaxHeap<RowNumAndSimilarity> nearestRowNums =
        new BoundedSizeMaxHeap<>(maxResults, RowNumAndSimilarity.TOP_RESULTS_HEAP_ORDER);
    if (numDivisions <= 0) {
      return nearestRowNums.toList();
    }
    if (numDivisions == 1) {
      // Searched on the calling thread, so a search that divides into one pays no hand-off.
      nearestRowNums.addAll(search.apply(0));
      return nearestRowNums.toList();
    }
    List<Future<List<RowNumAndSimilarity>>> handedOff = new ArrayList<>(numDivisions - 1);
    for (int division = 1; division < numDivisions; division++) {
      int handedOffDivision = division;
      handedOff.add(SEARCHERS.submit(() -> search.apply(handedOffDivision)));
    }
    nearestRowNums.addAll(search.apply(0));
    addHandedOff(handedOff, nearestRowNums);
    return nearestRowNums.toList();
  }

  /**
   * Adds the rows every handed-off division kept, waiting for all of them even once one has failed.
   *
   * <p>A failed division could be left to finish on its own, but only by returning while it still
   * held the structure, which the read lock the caller searches under would no longer be protecting.
   * Waiting costs the rest of a search that is going to throw, and it keeps every division inside
   * the lock that makes reading the structure safe.
   */
  private static void addHandedOff(
      List<Future<List<RowNumAndSimilarity>>> handedOff,
      BoundedSizeMaxHeap<RowNumAndSimilarity> nearestRowNums) {
    RuntimeException failure = null;
    boolean interrupted = false;
    for (Future<List<RowNumAndSimilarity>> division : handedOff) {
      try {
        nearestRowNums.addAll(division.get());
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

  private static final String SEARCH_FAILED = "Failed to search one division of a search.";

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
