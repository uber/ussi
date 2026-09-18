package com.uber.ussi.searchablestructure.index.matrix;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

class OpenBlasAdmissionTest {

  /**
   * The bound exists because a machine can have more cores than the binary holds buffers for, so it
   * must never exceed either.
   */
  @Test
  void boundsCallersByBothTheCoresAndWhatTheBinaryHoldsBuffersFor() {
    int maxNumConcurrentCallers = OpenBlasAdmission.getMaxNumConcurrentCallers();

    assertTrue(maxNumConcurrentCallers >= 1, "at least one caller must be admitted");
    assertTrue(
        maxNumConcurrentCallers <= Runtime.getRuntime().availableProcessors(),
        "admitted " + maxNumConcurrentCallers + " callers on a machine of fewer cores");
    assertTrue(
        maxNumConcurrentCallers <= OpenBlasAdmission.readMaxNumThreads(),
        "admitted " + maxNumConcurrentCallers + " callers with fewer buffers than that");
  }

  @Test
  void admitsUpToItsBoundAndMakesTheNextCallerWait() throws Exception {
    int maxNumConcurrentCallers = OpenBlasAdmission.getMaxNumConcurrentCallers();
    for (int caller = 0; caller < maxNumConcurrentCallers; caller++) {
      OpenBlasAdmission.acquire();
    }

    CountDownLatch admitted = new CountDownLatch(1);
    Thread beyondTheBound =
        new Thread(
            () -> {
              OpenBlasAdmission.acquire();
              admitted.countDown();
              OpenBlasAdmission.release();
            });
    beyondTheBound.setDaemon(true);
    beyondTheBound.start();
    try {
      // Waiting for the caller to be queued, rather than for a timeout to lapse, is unbounded and
      // a stronger statement than "it had not finished yet". Bails out if it is wrongly admitted.
      while (OpenBlasAdmission.getNumWaitingCallers() == 0 && admitted.getCount() > 0) {
        Thread.onSpinWait();
      }

      assertEquals(1, admitted.getCount(), "a caller beyond the bound must wait for a buffer");

      OpenBlasAdmission.release();
      admitted.await();

      assertEquals(0, admitted.getCount(), "releasing a buffer must admit the waiting caller");
    } finally {
      beyondTheBound.join();
      for (int caller = 0; caller < maxNumConcurrentCallers - 1; caller++) {
        OpenBlasAdmission.release();
      }
    }
  }

  /** Reading the number must restore whatever number was configured beforehand. */
  @Test
  void readingTheMaxNumThreadsIsRepeatable() {
    assertEquals(OpenBlasAdmission.readMaxNumThreads(), OpenBlasAdmission.readMaxNumThreads());
  }
}
