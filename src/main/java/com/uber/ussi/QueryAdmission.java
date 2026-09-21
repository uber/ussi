/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi;

import com.uber.ussi.error.SearchCancelledException;
import java.util.concurrent.Semaphore;

/**
 * Bounds how many searches run at once and admits waiting searches in the order they arrived.
 *
 * <p>The bound is the processor allowance: beyond it searches contend for the same cores without
 * any of them finishing sooner.
 *
 * <p>Admission is in arrival order so that a search does not lose its turn to one that arrived
 * later. Ordering is per search rather than per scored structure, so a search covering several
 * structures keeps its place for all of them.
 *
 * <p>Holding the permits also makes two things available to whoever needs them: how many searches
 * ran at once, and a moment with none running.
 *
 * <p>{@link #shared()} is process-wide rather than per index, because the cores it rations are not
 * divided between indexes. Its bound follows {@link ProcessorAllowance} and may be lowered or raised
 * while no search is running.
 */
final class QueryAdmission {

  private static final QueryAdmission SHARED = new QueryAdmission(initialBound());

  private final Semaphore permits;
  private volatile int maxNumConcurrentSearches;

  QueryAdmission(int maxNumConcurrentSearches) {
    if (maxNumConcurrentSearches < 1) {
      throw new IllegalArgumentException("maxNumConcurrentSearches must be >= 1.");
    }
    this.maxNumConcurrentSearches = maxNumConcurrentSearches;
    this.permits = new Semaphore(maxNumConcurrentSearches, /* fair */ true);
  }

  private static int initialBound() {
    return Math.max(1, ProcessorAllowance.shared().getNumProcessors());
  }

  static QueryAdmission shared() {
    return SHARED;
  }

  /** Waits for a turn to search. Call {@link #release()} in a finally block. */
  void acquire() {
    try {
      permits.acquire();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new SearchCancelledException("Cancelled while waiting to run a search.", e);
    }
  }

  void release() {
    permits.release();
  }

  int getMaxNumConcurrentSearches() {
    return maxNumConcurrentSearches;
  }

  /**
   * Lowers or raises the bound. Must be called under {@link #runExclusively}, since that holds every
   * permit and its {@code finally} block releases the updated bound, so the semaphore ends up with
   * the new number of permits.
   */
  void setMaxNumConcurrentSearches(int newBound) {
    if (newBound < 1) {
      throw new IllegalArgumentException("maxNumConcurrentSearches must be >= 1.");
    }
    maxNumConcurrentSearches = newBound;
  }

  /** Searches admitted and not yet finished. */
  int getNumConcurrentSearches() {
    return maxNumConcurrentSearches - permits.availablePermits();
  }

  /** Searches waiting for a turn. An estimate, used to observe that the bound is holding. */
  int getNumWaitingSearches() {
    return permits.getQueueLength();
  }

  /**
   * Runs the task with no search running. Admission is fair, so this waits for at most the searches
   * already running. The {@code finally} block releases the bound in effect when the task returns,
   * so a task that changes the bound leaves the semaphore at the new number of permits.
   */
  void runExclusively(Runnable task) {
    permits.acquireUninterruptibly(maxNumConcurrentSearches);
    try {
      task.run();
    } finally {
      permits.release(maxNumConcurrentSearches);
    }
  }
}
