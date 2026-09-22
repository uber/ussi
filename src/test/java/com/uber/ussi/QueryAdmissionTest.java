package com.uber.ussi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.uber.ussi.error.SearchCancelledException;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class QueryAdmissionTest {

  @Test
  void rejectsANonPositiveBound() {
    assertThrows(IllegalArgumentException.class, () -> new QueryAdmission(0));
    assertThrows(IllegalArgumentException.class, () -> new QueryAdmission(-1));
  }

  @Test
  void rejectsANonPositiveNewBound() {
    QueryAdmission admission = new QueryAdmission(2);

    assertThrows(IllegalArgumentException.class, () -> admission.setMaxNumConcurrentSearches(0));
    assertThrows(IllegalArgumentException.class, () -> admission.setMaxNumConcurrentSearches(-1));
    assertEquals(2, admission.getMaxNumConcurrentSearches(), "a rejected bound changes nothing");
  }

  /**
   * The bound is changed while every permit is held, and the permits released on the way out are
   * the new bound, so the semaphore ends up admitting the new number.
   */
  @Test
  void raisingAndLoweringTheBoundUnderExclusivityLeavesTheNewNumberOfPermits() {
    QueryAdmission admission = new QueryAdmission(2);

    admission.runExclusively(() -> admission.setMaxNumConcurrentSearches(4));

    assertEquals(4, admission.getMaxNumConcurrentSearches());
    for (int search = 0; search < 4; ++search) {
      admission.acquire();
    }
    assertEquals(4, admission.getNumConcurrentSearches(), "the raised bound admits four searches");
    for (int search = 0; search < 4; ++search) {
      admission.release();
    }

    admission.runExclusively(() -> admission.setMaxNumConcurrentSearches(1));

    assertEquals(1, admission.getMaxNumConcurrentSearches());
    admission.acquire();
    assertEquals(0, admission.getNumWaitingSearches());
    admission.release();
  }

  @Test
  void theSharedBoundIsTheCoreCount() {
    assertEquals(
        Math.max(1, Runtime.getRuntime().availableProcessors()),
        QueryAdmission.shared().getMaxNumConcurrentSearches());
  }

  @Test
  void admitsUpToItsBoundAndMakesTheNextSearchWait() throws Exception {
    QueryAdmission admission = new QueryAdmission(2);
    admission.acquire();
    admission.acquire();

    assertEquals(2, admission.getNumConcurrentSearches());

    CountDownLatch admitted = new CountDownLatch(1);
    Thread third = startAcquirer(admission, admitted, new ConcurrentLinkedQueue<>(), "third");
    try {
      // Bails out if the third search is wrongly admitted, so a broken bound fails rather than
      // spinning forever.
      awaitWaiting(admission, 1, () -> admitted.getCount() == 0);

      assertEquals(1, admitted.getCount(), "a third search must wait while both permits are held");

      admission.release();
      admitted.await();

      assertEquals(0, admitted.getCount(), "releasing a permit must admit the waiting search");
    } finally {
      third.join();
      admission.release();
    }
  }

  @Test
  void releasingReturnsCapacity() {
    QueryAdmission admission = new QueryAdmission(1);
    admission.acquire();
    assertEquals(1, admission.getNumConcurrentSearches());

    admission.release();

    assertEquals(0, admission.getNumConcurrentSearches());
  }

  /**
   * The property the bound exists to provide: a search that arrived earlier is admitted first, so a
   * search covering one index cannot lose its turn to a search that arrived later for another.
   */
  @Test
  void admitsWaitingSearchesInArrivalOrder() throws Exception {
    QueryAdmission admission = new QueryAdmission(1);
    admission.acquire();

    ConcurrentLinkedQueue<String> admissionOrder = new ConcurrentLinkedQueue<>();
    List<String> arrivalOrder = List.of("first", "second", "third", "fourth");
    Thread[] threads = new Thread[arrivalOrder.size()];
    CountDownLatch[] latches = new CountDownLatch[arrivalOrder.size()];
    for (int i = 0; i < arrivalOrder.size(); i++) {
      latches[i] = new CountDownLatch(1);
      threads[i] = startAcquirer(admission, latches[i], admissionOrder, arrivalOrder.get(i));
      // Each arrival is queued before the next one starts, which fixes the arrival order without
      // relying on the scheduler. Bails out if anything is admitted while the permit is held.
      awaitWaiting(admission, i + 1, () -> !admissionOrder.isEmpty());
    }

    try {
      // One permit circulates, so each release admits exactly the longest-waiting search.
      for (int i = 0; i < arrivalOrder.size(); i++) {
        admission.release();
        latches[i].await();
      }
    } finally {
      for (Thread thread : threads) {
        thread.join();
      }
    }

    assertEquals(arrivalOrder, List.copyOf(admissionOrder));
  }

  @Test
  void countsTheSearchesRunningRightNow() {
    QueryAdmission admission = new QueryAdmission(4);
    admission.acquire();
    admission.acquire();

    assertEquals(2, admission.getNumConcurrentSearches());

    admission.release();

    assertEquals(1, admission.getNumConcurrentSearches(), "a finished search is not counted");
  }

  @Test
  void runsExclusivelyOnlyWithNoConcurrentSearch() throws Exception {
    QueryAdmission admission = new QueryAdmission(2);
    admission.acquire();

    ConcurrentLinkedQueue<String> events = new ConcurrentLinkedQueue<>();
    CountDownLatch ran = new CountDownLatch(1);
    Thread exclusive =
        new Thread(
            () -> {
              admission.runExclusively(
                  () ->
                      events.add(
                          "ran with numConcurrentSearches="
                              + admission.getNumConcurrentSearches()));
              ran.countDown();
            });
    exclusive.setDaemon(true);
    exclusive.start();
    try {
      awaitWaiting(admission, 1, () -> ran.getCount() == 0);

      assertEquals(1, ran.getCount(), "exclusive work must wait for the running search");

      admission.release();
      ran.await();

      assertEquals(List.of("ran with numConcurrentSearches=2"), List.copyOf(events));
    } finally {
      exclusive.join();
    }
  }

  /**
   * A search arriving while another is already waiting must not overtake it. This is distinct from
   * the ordering above: waiting searches are woken in turn regardless, and it is only a newly
   * arriving search that can jump the queue. Repeated because the arrival races the release. An
   * ordered admission wins every race, so a passing run is not luck.
   */
  @Test
  void aNewArrivalNeverOvertakesAWaitingSearch() throws Exception {
    for (int trial = 0; trial < 200; trial++) {
      QueryAdmission admission = new QueryAdmission(1);
      admission.acquire();

      ConcurrentLinkedQueue<String> admissionOrder = new ConcurrentLinkedQueue<>();
      CountDownLatch earlyDone = new CountDownLatch(1);
      Thread early = startCirculatingAcquirer(admission, earlyDone, admissionOrder, "early");
      awaitWaiting(admission, 1, () -> !admissionOrder.isEmpty());

      // The late search starts before the permit is freed, so its arrival races the release.
      CountDownLatch lateDone = new CountDownLatch(1);
      Thread late = startCirculatingAcquirer(admission, lateDone, admissionOrder, "late");
      admission.release();

      earlyDone.await();
      lateDone.await();
      early.join();
      late.join();

      assertEquals(
          List.of("early", "late"),
          List.copyOf(admissionOrder),
          "a search that arrived later overtook one already waiting, on trial " + trial);
    }
  }

  @Test
  void acquireOnAnInterruptedThreadFailsAndKeepsTheInterrupt() throws Exception {
    QueryAdmission admission = new QueryAdmission(1);
    admission.acquire();

    ConcurrentLinkedQueue<Boolean> stillInterrupted = new ConcurrentLinkedQueue<>();
    ConcurrentLinkedQueue<Class<?>> thrown = new ConcurrentLinkedQueue<>();
    Thread thread =
        new Thread(
            () -> {
              Thread.currentThread().interrupt();
              try {
                admission.acquire();
              } catch (SearchCancelledException e) {
                thrown.add(e.getClass());
              }
              stillInterrupted.add(Thread.currentThread().isInterrupted());
            });
    thread.start();
    thread.join();

    assertEquals(List.of(SearchCancelledException.class), List.copyOf(thrown));
    assertEquals(List.of(Boolean.TRUE), List.copyOf(stillInterrupted));
    admission.release();
  }

  /** Acquires, records, then releases, so a single permit can circulate through every waiter. */
  private static Thread startCirculatingAcquirer(
      QueryAdmission admission,
      CountDownLatch done,
      ConcurrentLinkedQueue<String> admissionOrder,
      String name) {
    Thread thread =
        new Thread(
            () -> {
              admission.acquire();
              admissionOrder.add(name);
              admission.release();
              done.countDown();
            },
            name);
    thread.setDaemon(true);
    thread.start();
    return thread;
  }

  private static Thread startAcquirer(
      QueryAdmission admission,
      CountDownLatch admitted,
      ConcurrentLinkedQueue<String> admissionOrder,
      String name) {
    Thread thread =
        new Thread(
            () -> {
              admission.acquire();
              admissionOrder.add(name);
              admitted.countDown();
            },
            name);
    thread.setDaemon(true);
    thread.start();
    return thread;
  }

  /** Spins rather than sleeping, so nothing here depends on a timeout that CI load could blow. */
  private static void awaitWaiting(
      QueryAdmission admission, int expected, BooleanSupplier boundViolated) {
    while (admission.getNumWaitingSearches() < expected && !boundViolated.getAsBoolean()) {
      Thread.onSpinWait();
    }
    assertTrue(
        admission.getNumWaitingSearches() >= expected,
        "expected " + expected + " search(es) waiting, saw " + admission.getNumWaitingSearches());
  }
}
