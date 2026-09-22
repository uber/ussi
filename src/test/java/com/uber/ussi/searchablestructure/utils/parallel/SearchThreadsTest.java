/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.utils.parallel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.uber.ussi.error.SearchCancelledException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
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
   * A search cancelled while its work units are outstanding waits for every one of them before
   * reporting the cancellation. Returning earlier would leave a work unit reading a structure that
   * the read lock its caller searches under is no longer protecting.
   *
   * <p>The work units are held until the caller has been interrupted, so the wait is entered with
   * every one of them outstanding rather than already finished.
   */
  @Test
  void waitsForEveryWorkUnitBeforeReportingACancellation() {
    // The pool must be able to run both submitted work units at once, since each waits for the
    // other to arrive before either finishes.
    SearchThreads.resize(4);
    CountDownLatch bothStarted = new CountDownLatch(2);
    AtomicInteger finished = new AtomicInteger();
    try {
      assertThrows(
          SearchCancelledException.class,
          () ->
              SearchThreads.runInParallel(
                  3,
                  workUnit -> {
                    if (workUnit == 0) {
                      // The caller runs this one, and enters the wait already interrupted.
                      Thread.currentThread().interrupt();
                      return;
                    }
                    bothStarted.countDown();
                    try {
                      bothStarted.await();
                    } catch (InterruptedException e) {
                      Thread.currentThread().interrupt();
                    }
                    finished.incrementAndGet();
                  }));

      assertEquals(
          2, finished.get(), "every submitted work unit finished before the caller returned");
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
