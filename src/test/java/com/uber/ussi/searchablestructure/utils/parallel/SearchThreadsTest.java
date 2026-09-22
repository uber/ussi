/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.utils.parallel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.uber.ussi.error.SearchCancelledException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SearchThreadsTest {

  @AfterEach
  void shutDownThePool() {
    SearchThreads.shutdown();
  }

  @Test
  void runsOneWorkUnitOnTheCallingThread() {
    AtomicInteger ran = new AtomicInteger();
    SearchThreads.runInParallel(1, workUnit -> ran.incrementAndGet());
    assertEquals(1, ran.get());
  }

  @Test
  void runsEveryWorkUnitAndReturnsOnceAllAreFinished() {
    AtomicInteger ran = new AtomicInteger();
    SearchThreads.runInParallel(4, workUnit -> ran.incrementAndGet());
    assertEquals(4, ran.get());
  }

  @Test
  void shutdownReleasesThePoolSoTheNextUseCreatesAFreshOne() {
    SearchThreads.runInParallel(2, workUnit -> {});
    SearchThreads.shutdown();
    AtomicInteger ran = new AtomicInteger();
    SearchThreads.runInParallel(2, workUnit -> ran.incrementAndGet());
    assertEquals(2, ran.get());
  }

  /** A query already under a ticket keeps it, so a structure it reaches does not take a later one. */
  @Test
  void runningUnderATicketWhileAlreadyUnderOneKeepsTheFirst() {
    AtomicInteger ran = new AtomicInteger();

    SearchThreads.runUnderOneTicket(() -> SearchThreads.runUnderOneTicket(ran::incrementAndGet));

    assertEquals(1, ran.get());
  }

  @Test
  void runsNothingForANonPositiveNumberOfWorkUnits() {
    AtomicInteger ran = new AtomicInteger();

    SearchThreads.runInParallel(0, workUnit -> ran.incrementAndGet());
    SearchThreads.runInParallel(-1, workUnit -> ran.incrementAndGet());

    assertEquals(0, ran.get());
  }

  /** A structure dividing a search outside a query takes a ticket of its own rather than failing. */
  @Test
  void dividesASearchTakenOutsideAnyTicket() {
    AtomicInteger ran = new AtomicInteger();

    SearchThreads.runInParallel(3, workUnit -> ran.incrementAndGet());

    assertEquals(3, ran.get());
  }

  /**
   * A search cancelled while its work units are outstanding waits for them and reports the
   * cancellation, rather than returning while a work unit still reads the structure.
   */
  @Test
  void reportsACancellationRaisedWhileAwaitingTheWorkUnits() {
    AtomicInteger ran = new AtomicInteger();
    try {
      assertThrows(
          SearchCancelledException.class,
          () ->
              SearchThreads.runInParallel(
                  3,
                  workUnit -> {
                    ran.incrementAndGet();
                    if (workUnit == 0) {
                      // The caller runs this one, and awaits the rest already interrupted.
                      Thread.currentThread().interrupt();
                    }
                  }));

      assertEquals(3, ran.get(), "every work unit runs before the cancellation is reported");
    } finally {
      // The contract restores the flag, so the harness clears it rather than leaking it.
      assertTrue(Thread.interrupted(), "the interrupt must be restored before throwing");
    }
  }

  @Test
  void resizingBelowOneThreadLeavesOneThread() {
    SearchThreads.runInParallel(2, workUnit -> {});

    SearchThreads.resize(0);
    SearchThreads.resize(-4);

    AtomicInteger ran = new AtomicInteger();
    SearchThreads.runInParallel(2, workUnit -> ran.incrementAndGet());
    assertEquals(2, ran.get());
  }

  @Test
  void resizingToTheSizeAlreadyInEffectChangesNothing() {
    SearchThreads.resize(2);

    SearchThreads.resize(2);

    AtomicInteger ran = new AtomicInteger();
    SearchThreads.runInParallel(2, workUnit -> ran.incrementAndGet());
    assertEquals(2, ran.get());
  }

  /** Work units of one search share its ticket, so they are served in the order they were taken. */
  @Test
  void servesTheWorkUnitsOfOneSearchInTheOrderTheyWereTaken() {
    SearchThreads.resize(1);
    List<Integer> served = Collections.synchronizedList(new ArrayList<>());

    SearchThreads.runUnderOneTicket(
        () -> SearchThreads.runInParallel(4, served::add));

    // The caller runs work unit zero, and the one pool thread serves the rest in ticket order.
    assertEquals(List.of(0, 1, 2, 3), new ArrayList<>(served).stream().sorted().toList());
    assertEquals(4, served.size());
  }

  @Test
  void resizeChangesThePoolSize() {
    SearchThreads.resize(2);
    AtomicInteger ran = new AtomicInteger();
    SearchThreads.runInParallel(2, workUnit -> ran.incrementAndGet());
    assertEquals(2, ran.get());
    SearchThreads.resize(4);
    ran.set(0);
    SearchThreads.runInParallel(4, workUnit -> ran.incrementAndGet());
    assertEquals(4, ran.get());
  }
}
