/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi;

import java.util.concurrent.Semaphore;

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
 * <p>{@link #shared()} is process-wide rather than per index, because the resources it protects are
 * the machine's cores and a process-global native library, neither of which is divided between
 * indexes.
 */
final class QueryAdmission {

  private static final QueryAdmission SHARED =
      new QueryAdmission(Math.max(1, Runtime.getRuntime().availableProcessors()));

  private final int maxConcurrentSearches;
  private final Semaphore permits;

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
}
