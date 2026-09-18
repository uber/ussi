/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounds how many searches run at once and admits waiting searches in the order they arrived.
 *
 * <p>The bound is the core count: beyond it searches contend for the same cores without any of them
 * finishing sooner.
 *
 * <p>Admission is in arrival order so that a search does not lose its turn to one that arrived
 * later. Ordering is per search rather than per scored structure, so a search covering several
 * structures keeps its place for all of them.
 *
 * <p>Holding the permits also makes two things available to whoever needs them: how many searches
 * ran at once, and a moment with none running.
 *
 * <p>{@link #shared()} is process-wide rather than per index, because the cores it rations are not
 * divided between indexes.
 */
final class QueryAdmission {

  private static final QueryAdmission SHARED =
      new QueryAdmission(Math.max(1, Runtime.getRuntime().availableProcessors()));

  private final int maxNumConcurrentSearches;
  private final Semaphore permits;
  private final AtomicInteger peakNumConcurrentSearches = new AtomicInteger();

  QueryAdmission(int maxNumConcurrentSearches) {
    if (maxNumConcurrentSearches < 1) {
      throw new IllegalArgumentException("maxNumConcurrentSearches must be >= 1.");
    }
    this.maxNumConcurrentSearches = maxNumConcurrentSearches;
    this.permits = new Semaphore(maxNumConcurrentSearches, /* fair */ true);
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
      throw new IllegalStateException("Interrupted while waiting to run a search.", e);
    }
    peakNumConcurrentSearches.accumulateAndGet(getNumConcurrentSearches(), Math::max);
  }

  void release() {
    permits.release();
  }

  int getMaxNumConcurrentSearches() {
    return maxNumConcurrentSearches;
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
   * The most concurrent searches since this was last called.
   *
   * <p>The number rises only when a search is admitted, so sampling it on admission catches every
   * peak within an interval. The next interval is seeded with the number running now, because a
   * search outlasting its interval is already running when the next one opens and would otherwise
   * go uncounted until it finished.
   */
  int takePeakNumConcurrentSearches() {
    return peakNumConcurrentSearches.getAndSet(getNumConcurrentSearches());
  }

  /**
   * Runs the task with no search running. Admission is fair, so this waits for at most the searches
   * already running.
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
