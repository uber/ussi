/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.matrix;

import java.util.concurrent.Semaphore;

/**
 * Bounds how many callers are inside a native library at once, and admits waiting callers in the
 * order they arrived.
 *
 * <p>A library may hold a fixed resource per calling thread and fail rather than wait once the
 * callers outnumber it, so the bound is a correctness requirement wherever the library reports
 * one, not a throughput choice. Admission is in arrival order so that a caller does not lose its
 * turn to one that arrived later.
 *
 * <p>One of these covers a whole library rather than one matrix, since the resource it rations is
 * the library's. Every scorer over the same library shares one.
 */
final class NativeBlasAdmission {

  private final int maxNumConcurrentCallers;
  private final Semaphore permits;

  NativeBlasAdmission(int maxNumConcurrentCallers) {
    if (maxNumConcurrentCallers < 1) {
      throw new IllegalArgumentException("maxNumConcurrentCallers must be >= 1.");
    }
    this.maxNumConcurrentCallers = maxNumConcurrentCallers;
    this.permits = new Semaphore(maxNumConcurrentCallers, /* fair */ true);
  }

  /** Waits for a turn to call the library. Call {@link #release()} in a finally block. */
  void acquire() {
    try {
      permits.acquire();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while waiting to call a native library.", e);
    }
  }

  void release() {
    permits.release();
  }

  int getMaxNumConcurrentCallers() {
    return maxNumConcurrentCallers;
  }

  /** Callers awaiting a turn. An estimate, read to observe that the bound holds. */
  int getNumWaitingCallers() {
    return permits.getQueueLength();
  }
}
