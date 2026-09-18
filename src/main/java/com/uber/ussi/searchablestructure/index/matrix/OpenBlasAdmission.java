/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.matrix;

import java.util.concurrent.Semaphore;
import org.bytedeco.openblas.global.openblas_full;
import org.bytedeco.openblas.presets.CachedBlasThreadCountSetter;

/**
 * Bounds how many threads are inside OpenBLAS at once and admits waiting callers in the order they
 * arrived.
 *
 * <p>OpenBLAS retains one buffer per thread that calls into it, and the number of buffers is fixed
 * when the binary is built. A caller beyond that number reaches an allocation the library reports
 * as liable to corrupt the heap rather than one that fails, so the permits limit concurrent callers
 * to the number of buffers the loaded binary retains. A buffer is released when the call returns,
 * so limiting the threads currently inside the library is sufficient.
 *
 * <p>The limit is the lesser of the core count and the number the binary reports. Limiting searches
 * to the core count is insufficient on a machine whose cores outnumber the buffers, where every
 * search may hold a permit and the callers still outnumber the buffers.
 */
final class OpenBlasAdmission {

  /**
   * Applied when the loaded binary does not report a number. Every binary observed so far retains
   * at least this many buffers, so a machine whose number cannot be read remains limited.
   */
  private static final int FALLBACK_MAX_NUM_CONCURRENT_CALLERS = 64;

  private static final int MAX_NUM_CONCURRENT_CALLERS =
      Math.min(Math.max(1, Runtime.getRuntime().availableProcessors()), readMaxNumThreads());

  private static final Semaphore PERMITS =
      new Semaphore(MAX_NUM_CONCURRENT_CALLERS, /* fair */ true);

  private OpenBlasAdmission() {}

  /** Waits for a buffer to call OpenBLAS with. Call {@link #release()} in a finally block. */
  static void acquire() {
    try {
      PERMITS.acquire();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while waiting to call into OpenBLAS.", e);
    }
  }

  static void release() {
    PERMITS.release();
  }

  static int getMaxNumConcurrentCallers() {
    return MAX_NUM_CONCURRENT_CALLERS;
  }

  /** Callers awaiting a buffer. An estimate, read to observe that the limit holds. */
  static int getNumWaitingCallers() {
    return PERMITS.getQueueLength();
  }

  /**
   * The number of threads the loaded binary retains buffers for.
   *
   * <p>Obtained by requesting more threads than any binary provides, since OpenBLAS answers a
   * request above its own maximum with that maximum. The number configured beforehand is restored,
   * so obtaining it leaves the library as it was found.
   */
  static int readMaxNumThreads() {
    try {
      int configuredNumThreads = openblas_full.openblas_get_num_threads();
      CachedBlasThreadCountSetter.setNumThreads(Integer.MAX_VALUE);
      int maxNumThreads = openblas_full.openblas_get_num_threads();
      if (configuredNumThreads > 0) {
        CachedBlasThreadCountSetter.setNumThreads(configuredNumThreads);
      }
      return maxNumThreads > 0 ? maxNumThreads : FALLBACK_MAX_NUM_CONCURRENT_CALLERS;
    } catch (LinkageError | RuntimeException e) {
      return FALLBACK_MAX_NUM_CONCURRENT_CALLERS;
    }
  }
}
