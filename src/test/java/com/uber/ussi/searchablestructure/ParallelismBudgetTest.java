package com.uber.ussi.searchablestructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ParallelismBudgetTest {

  private static final int CORES = 48;

  @Test
  void rejectsANonPositiveBound() {
    assertThrows(IllegalArgumentException.class, () -> new ParallelismBudget(0));
    assertThrows(IllegalArgumentException.class, () -> new ParallelismBudget(-1));
  }

  @Test
  void oneSearchGetsEveryThread() {
    assertEquals(CORES, new ParallelismBudget(CORES).budgetFor(1));
  }

  @Test
  void concurrencyAtOrAboveTheBoundGetsOneThread() {
    ParallelismBudget budget = new ParallelismBudget(CORES);

    assertEquals(1, budget.budgetFor(CORES));
    assertEquals(1, budget.budgetFor(CORES + 1));
    assertEquals(1, budget.budgetFor(Integer.MAX_VALUE));
  }

  @Test
  void noSearchesGetsEveryThreadRatherThanDividingByZero() {
    ParallelismBudget budget = new ParallelismBudget(CORES);

    assertEquals(CORES, budget.budgetFor(0));
    assertEquals(CORES, budget.budgetFor(-1));
  }

  @Test
  void roundsDownRatherThanUp() {
    // Seven searches on 48 cores get 6 threads each, not 7, which would oversubscribe.
    assertEquals(6, new ParallelismBudget(CORES).budgetFor(7));
  }

  /** The invariant the budget exists to hold. */
  @Test
  void budgetTimesConcurrencyStaysWithinTheBound() {
    ParallelismBudget budget = new ParallelismBudget(CORES);
    for (int concurrency = 1; concurrency <= CORES; concurrency++) {
      int threads = budget.budgetFor(concurrency);
      assertTrue(
          (long) threads * concurrency <= CORES,
          "threads " + threads + " times concurrency " + concurrency + " exceeds " + CORES);
    }
  }

  @Test
  void startsAtTheFullBoundSoAnIdleEngineUsesEveryCore() {
    assertEquals(CORES, new ParallelismBudget(CORES).budget());
  }

  @Test
  void registeringAppliesTheCurrentBudgetImmediately() {
    ParallelismBudget budget = new ParallelismBudget(CORES);
    List<Integer> applied = new ArrayList<>();

    budget.onChange(applied::add);

    assertEquals(List.of(CORES), applied);
  }

  @Test
  void updateAppliesANewBudgetExclusively() {
    ParallelismBudget budget = new ParallelismBudget(CORES);
    List<Integer> applied = new ArrayList<>();
    budget.onChange(applied::add);
    applied.clear();
    List<String> exclusiveCalls = new ArrayList<>();

    budget.update(
        CORES,
        task -> {
          exclusiveCalls.add("entered");
          task.run();
          exclusiveCalls.add("left");
        });

    assertEquals(List.of(1), applied);
    assertEquals(1, budget.budget());
    assertEquals(
        List.of("entered", "left"),
        exclusiveCalls,
        "a process-global count must only change with no search in flight");
  }

  @Test
  void updateLeavesAnUnchangedBudgetAlone() {
    ParallelismBudget budget = new ParallelismBudget(CORES);
    List<Integer> applied = new ArrayList<>();
    budget.onChange(applied::add);
    applied.clear();
    List<String> exclusiveCalls = new ArrayList<>();

    // One search in flight wants the full bound, which is where the budget already is.
    budget.update(1, task -> exclusiveCalls.add("entered"));

    assertEquals(List.of(), applied);
    assertEquals(List.of(), exclusiveCalls, "an unchanged budget must not quiesce the engine");
  }

  @Test
  void updateTracksConcurrencyUpAndBackDown() {
    ParallelismBudget budget = new ParallelismBudget(CORES);
    List<Integer> applied = new ArrayList<>();
    budget.onChange(applied::add);
    applied.clear();

    budget.update(CORES, Runnable::run);
    budget.update(4, Runnable::run);
    budget.update(1, Runnable::run);

    assertEquals(List.of(1, CORES / 4, CORES), applied);
  }
}
