/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ProcessorAllowanceTest {

  private static final int AVAILABLE = Math.max(1, Runtime.getRuntime().availableProcessors());

  @AfterEach
  void resetTheSharedAllowance() {
    ProcessorAllowance.resetForTests();
  }

  @Test
  void defaultsToTheProcessorsTheProcessMayRunOn() {
    assertEquals(AVAILABLE, ProcessorAllowance.shared().getNumProcessors());
  }

  /** Exercises the arithmetic directly, so no case leaves a bound behind for another test. */
  @Test
  void clampsWhatTheHostAsksForByTheOperatingSystemAndTheNativeBound() {
    int[][] requestedAvailableNativeMaxAndExpected = {
      // A host asking for one gets one.
      {1, 16, Integer.MAX_VALUE, 1},
      // A host asking for more than the process may run on is reduced to what it may run on.
      {Integer.MAX_VALUE, 16, Integer.MAX_VALUE, 16},
      // A native bound below both is what binds.
      {Integer.MAX_VALUE, 16, 4, 4},
      // A non-positive request is floored at one rather than disabling search.
      {0, 16, Integer.MAX_VALUE, 1},
      {-5, 16, Integer.MAX_VALUE, 1},
      // Neither bound binds below what the host asked for.
      {2, 16, Integer.MAX_VALUE, 2},
      // A non-positive bound is floored rather than disabling search.
      {8, 0, Integer.MAX_VALUE, 1},
      {8, 16, 0, 1},
    };
    for (int[] testCase : requestedAvailableNativeMaxAndExpected) {
      assertEquals(
          testCase[3],
          ProcessorAllowance.clamp(testCase[0], testCase[1], testCase[2]),
          "requested " + testCase[0] + " against " + testCase[1] + " available and a native bound "
              + "of " + testCase[2]);
    }
  }

  /** The supplier exists so a host may shrink uSSI's share while it is busy with other work. */
  @Test
  void followsTheSupplierAcrossRefreshes() {
    ProcessorAllowance allowance = ProcessorAllowance.shared();
    int[] requested = {1};
    allowance.setNumProcessors(() -> requested[0]);

    assertEquals(1, allowance.getNumProcessors());

    requested[0] = AVAILABLE;
    allowance.refresh();

    assertEquals(AVAILABLE, allowance.getNumProcessors());
  }

  @Test
  void rejectsANullSupplier() {
    ProcessorAllowance allowance = ProcessorAllowance.shared();

    assertThrows(NullPointerException.class, () -> allowance.setNumProcessors(null));
    assertThrows(NullPointerException.class, () -> allowance.setNativeMaxProcessorsSupplier(null));
  }
}
