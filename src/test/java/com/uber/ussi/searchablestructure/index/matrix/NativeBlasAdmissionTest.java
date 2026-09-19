package com.uber.ussi.searchablestructure.index.matrix;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

class NativeBlasAdmissionTest {

  @Test
  void rejectsABoundBelowOneCaller() {
    assertThrows(IllegalArgumentException.class, () -> new NativeBlasAdmission(0));
  }

  @Test
  void admitsUpToItsBoundAndMakesTheNextCallerWait() throws Exception {
    NativeBlasAdmission admission = new NativeBlasAdmission(2);
    admission.acquire();
    admission.acquire();

    CountDownLatch admitted = new CountDownLatch(1);
    Thread beyondTheBound =
        new Thread(
            () -> {
              admission.acquire();
              admitted.countDown();
              admission.release();
            });
    beyondTheBound.setDaemon(true);
    beyondTheBound.start();
    try {
      // Waiting for the caller to be enqueued, rather than for a timeout to lapse, is unbounded
      // and a stronger assertion than "it had not completed yet". Terminates if it is wrongly
      // admitted.
      while (admission.getNumWaitingCallers() == 0 && admitted.getCount() > 0) {
        Thread.onSpinWait();
      }

      assertEquals(1, admitted.getCount(), "a caller beyond the bound must wait");

      admission.release();
      admitted.await();

      assertEquals(0, admitted.getCount(), "a release must admit the waiting caller");
    } finally {
      beyondTheBound.join();
      admission.release();
    }
  }
}
