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

  @Test
  void clampsWhatTheHostAsksForByTheOperatingSystemAndTheNativeBound() {
    int[][] requestedNativeMaxAndExpected = {
      // A host asking for one gets one.
      {1, Integer.MAX_VALUE, 1},
      // A host asking for more than the process may use is reduced to what it may use.
      {Integer.MAX_VALUE, Integer.MAX_VALUE, AVAILABLE},
      // A native bound below both is what binds.
      {Integer.MAX_VALUE, 1, 1},
      // A non-positive request is floored at one rather than disabling search.
      {0, Integer.MAX_VALUE, 1},
      {-5, Integer.MAX_VALUE, 1},
      // Neither bound binds below what the host asked for.
      {2, Integer.MAX_VALUE, Math.min(2, AVAILABLE)},
    };
    for (int[] testCase : requestedNativeMaxAndExpected) {
      ProcessorAllowance.resetForTests();
      ProcessorAllowance allowance = ProcessorAllowance.shared();
      allowance.setNativeMaxProcessorsSupplier(() -> testCase[1]);
      allowance.setNumProcessors(() -> testCase[0]);

      assertEquals(
          testCase[2],
          allowance.getNumProcessors(),
          "requested " + testCase[0] + " against a native bound of " + testCase[1]);
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
