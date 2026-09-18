package com.uber.ussi.searchablestructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ParallelismBudgetTest {

  private static final int CORES = 48;

  @Test
  void rejectsANonPositiveBound() {
    assertThrows(IllegalArgumentException.class, () -> new ParallelismBudget(0, 0));
    assertThrows(IllegalArgumentException.class, () -> new ParallelismBudget(-1, -1));
  }

  @Test
  void rejectsMoreSharedThreadsThanThreadsPerSearch() {
    assertThrows(IllegalArgumentException.class, () -> new ParallelismBudget(CORES, CORES + 1));
    assertThrows(IllegalArgumentException.class, () -> new ParallelismBudget(CORES, 0));
  }

  @Test
  void dividesTheSharedThreadsOutOfOneSocketsCores() {
    int numCoresPerSocket = CORES / 4;
    int[][] concurrencyAndNumThreads = {
      {1, numCoresPerSocket},
      {2, numCoresPerSocket / 2},
      {4, numCoresPerSocket / 4},
      {numCoresPerSocket, 1},
      {CORES, 1},
      {0, numCoresPerSocket},
      {-1, numCoresPerSocket},
    };
    ParallelismBudget budget = new ParallelismBudget(CORES, numCoresPerSocket);
    for (int[] testCase : concurrencyAndNumThreads) {
      assertEquals(
          testCase[1],
          budget.getNumSharedThreadsFor(testCase[0]),
          "concurrency " + testCase[0]);
    }
  }

  @Test
  void appliesTheSharedThreadsAndNotTheBudgetOnRegistration() {
    int numCoresPerSocket = CORES / 4;
    ParallelismBudget budget = new ParallelismBudget(CORES, numCoresPerSocket);
    int[] appliedNumThreads = new int[1];

    budget.onChange(threads -> appliedNumThreads[0] = threads);

    assertEquals(numCoresPerSocket, appliedNumThreads[0]);
  }

  @Test
  void oneSearchGetsEveryThread() {
    assertEquals(CORES, new ParallelismBudget(CORES, CORES).budgetFor(1));
  }

  @Test
  void concurrencyAtOrAboveTheBoundGetsOneThread() {
    ParallelismBudget budget = new ParallelismBudget(CORES, CORES);

    assertEquals(1, budget.budgetFor(CORES));
    assertEquals(1, budget.budgetFor(CORES + 1));
    assertEquals(1, budget.budgetFor(Integer.MAX_VALUE));
  }

  @Test
  void noSearchesGetsEveryThreadRatherThanDividingByZero() {
    ParallelismBudget budget = new ParallelismBudget(CORES, CORES);

    assertEquals(CORES, budget.budgetFor(0));
    assertEquals(CORES, budget.budgetFor(-1));
  }

  @Test
  void roundsDownRatherThanUp() {
    // Seven searches on 48 cores get 6 threads each, not 7, which would oversubscribe.
    assertEquals(6, new ParallelismBudget(CORES, CORES).budgetFor(7));
  }

  /** The invariant the budget exists to hold. */
  @Test
  void budgetTimesConcurrencyStaysWithinTheBound() {
    ParallelismBudget budget = new ParallelismBudget(CORES, CORES);
    for (int concurrency = 1; concurrency <= CORES; concurrency++) {
      int threads = budget.budgetFor(concurrency);
      assertTrue(
          (long) threads * concurrency <= CORES,
          "threads " + threads + " times concurrency " + concurrency + " exceeds " + CORES);
    }
  }

  @Test
  void startsAtTheFullBoundSoAnIdleEngineUsesEveryCore() {
    assertEquals(CORES, new ParallelismBudget(CORES, CORES).budget());
  }

  /** A structure can be built while other searches run, so registering must also be exclusive. */
  @Test
  void registeringAppliesTheBudgetExclusively() {
    ParallelismBudget budget = new ParallelismBudget(CORES, CORES);
    List<String> exclusiveCalls = new ArrayList<>();
    attachThen(
        budget,
        task -> {
          exclusiveCalls.add("entered");
          task.run();
        });

    budget.onChange(threads -> exclusiveCalls.add("applied " + threads));

    assertEquals(List.of("entered", "applied " + CORES), exclusiveCalls);
  }

  /** Attaches to install the runner, then stops the interval work so the test drives update itself. */
  private static void attachThen(ParallelismBudget budget, java.util.function.Consumer<Runnable> exclusively) {
    budget.attach(() -> 0, exclusively);
    budget.detach();
  }

  @Test
  void registeringAppliesTheCurrentBudgetImmediately() {
    ParallelismBudget budget = new ParallelismBudget(CORES, CORES);
    List<Integer> applied = new ArrayList<>();

    budget.onChange(applied::add);

    assertEquals(List.of(CORES), applied);
  }

  @Test
  void updateAppliesANewBudgetExclusively() {
    ParallelismBudget budget = new ParallelismBudget(CORES, CORES);
    List<String> exclusiveCalls = new ArrayList<>();
    attachThen(
        budget,
        task -> {
          exclusiveCalls.add("entered");
          task.run();
          exclusiveCalls.add("left");
        });
    List<Integer> applied = new ArrayList<>();
    budget.onChange(applied::add);
    applied.clear();
    exclusiveCalls.clear();

    budget.update(CORES);

    assertEquals(List.of(1), applied);
    assertEquals(1, budget.budget());
    assertEquals(
        List.of("entered", "left"),
        exclusiveCalls,
        "a process-global count must only change with no search in flight");
  }

  @Test
  void updateLeavesAnUnchangedBudgetAlone() {
    ParallelismBudget budget = new ParallelismBudget(CORES, CORES);
    List<Integer> applied = new ArrayList<>();
    budget.onChange(applied::add);
    applied.clear();
    List<String> exclusiveCalls = new ArrayList<>();
    attachThen(budget, task -> exclusiveCalls.add("entered"));

    // One search in flight wants the full bound, which is where the budget already is.
    budget.update(1);

    assertEquals(List.of(), applied);
    assertEquals(List.of(), exclusiveCalls, "an unchanged budget must not quiesce the engine");
  }

  @Test
  void rebudgetsWhileAtLeastOneEngineIsAttached() {
    ParallelismBudget budget = new ParallelismBudget(CORES, CORES);

    budget.attach(() -> 1, Runnable::run);
    budget.attach(() -> 1, Runnable::run);

    assertEquals(2, budget.attachments());
    assertTrue(budget.isRebudgeting());

    // One engine closing must not stop the other engine's budget.
    budget.detach();

    assertEquals(1, budget.attachments());
    assertTrue(budget.isRebudgeting());

    budget.detach();

    assertEquals(0, budget.attachments());
    assertFalse(budget.isRebudgeting(), "the last engine detaching stops the work");
  }

  @Test
  void detachingMoreOftenThanAttachingDoesNothing() {
    ParallelismBudget budget = new ParallelismBudget(CORES, CORES);

    budget.detach();
    budget.attach(() -> 1, Runnable::run);
    budget.detach();
    budget.detach();

    assertEquals(0, budget.attachments());
    assertFalse(budget.isRebudgeting());
  }

  @Test
  void anUnattachedBudgetStaysAtItsFullValue() {
    ParallelismBudget budget = new ParallelismBudget(CORES, CORES);

    assertFalse(budget.isRebudgeting());
    assertEquals(CORES, budget.budget());
  }

  @Test
  void updateTracksConcurrencyUpAndBackDown() {
    ParallelismBudget budget = new ParallelismBudget(CORES, CORES);
    List<Integer> applied = new ArrayList<>();
    budget.onChange(applied::add);
    applied.clear();

    budget.update(CORES);
    budget.update(4);
    budget.update(1);

    assertEquals(List.of(1, CORES / 4, CORES), applied);
  }
}
