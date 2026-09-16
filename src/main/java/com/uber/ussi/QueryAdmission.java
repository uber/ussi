/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi;

import com.uber.ussi.searchablestructure.ParallelismBudget;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounds how many searches run at once and admits waiting searches in the order they arrived.
 *
 * <p>The bound is the core count. Beyond that, searches contend for the same cores without finishing
 * any sooner, and a native scorer may hold a per-thread resource whose count its library fixes at
 * build time, which it does not check before using.
 *
 * <p>Admission is in arrival order so that a search does not lose its turn to one that arrived
 * later. Ordering is per search rather than per scored structure, so a search covering several
 * structures keeps its place for all of them.
 *
 * <p>Holding the permits also measures the concurrency that {@link ParallelismBudget} divides the
 * cores by, and draining them provides the quiet moment a process-global thread count needs in order
 * to change. The peak of an interval is used rather than the mean, because too much parallelism
 * costs far more than too little, and because it is stable enough that steady load does not keep
 * changing a setting that is expensive to change.
 *
 * <p>{@link #shared()} is process-wide rather than per index, because the resources it protects are
 * the machine's cores and a process-global native library, neither of which is divided between
 * indexes.
 */
final class QueryAdmission {

  private static final QueryAdmission SHARED =
      new QueryAdmission(Math.max(1, Runtime.getRuntime().availableProcessors()));

  private final int maxConcurrentSearches;
  private final Semaphore permits;
  private final AtomicInteger peakInFlight = new AtomicInteger();

  static {
    ParallelismBudget.shared().attach(SHARED::takePeakInFlight, SHARED::runExclusively);
  }

  QueryAdmission(int maxConcurrentSearches) {
    if (maxConcurrentSearches < 1) {
      throw new IllegalArgumentException("maxConcurrentSearches must be >= 1.");
    }
    this.maxConcurrentSearches = maxConcurrentSearches;
    this.permits = new Semaphore(maxConcurrentSearches, /* fair */ true);
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
    peakInFlight.accumulateAndGet(inFlight(), Math::max);
  }

  void release() {
    permits.release();
  }

  int maxConcurrentSearches() {
    return maxConcurrentSearches;
  }

  /** Searches admitted and not yet finished. */
  int inFlight() {
    return maxConcurrentSearches - permits.availablePermits();
  }

  /** Searches waiting for a turn. An estimate, used to observe that the bound is holding. */
  int waiting() {
    return permits.getQueueLength();
  }

  /**
   * The most searches in flight at once since this was last called.
   *
   * <p>Counts both the searches that started during the interval and those still running at the end
   * of it, since a search outlasting the interval arrives in one and occupies every later one.
   */
  int takePeakInFlight() {
    return Math.max(peakInFlight.getAndSet(0), inFlight());
  }

  /** Runs the task with no search in flight. Admission is fair, so this is not starved. */
  void runExclusively(Runnable task) {
    permits.acquireUninterruptibly(maxConcurrentSearches);
    try {
      task.run();
    } finally {
      permits.release(maxConcurrentSearches);
    }
  }
}
