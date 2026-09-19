package com.uber.ussi.searchablestructure.utils.parallel;

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

  /**
   * A holder of a process-global count serializes its callers, so one call holds the whole width
   * and there is nothing to divide between concurrent searches.
   */
  @Test
  void holdsOneSocketsCoresWhateverTheConcurrency() {
    int numCoresPerSocket = CORES / 4;
    int[][] concurrencyAndNumThreads = {
      {1, numCoresPerSocket},
      {2, numCoresPerSocket},
      {4, numCoresPerSocket},
      {numCoresPerSocket, numCoresPerSocket},
      {CORES, numCoresPerSocket},
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
    assertEquals(CORES, new ParallelismBudget(CORES, CORES).getNumThreadsPerSearchFor(1));
  }

  @Test
  void concurrencyAtOrAboveTheBoundGetsOneThread() {
    ParallelismBudget budget = new ParallelismBudget(CORES, CORES);

    assertEquals(1, budget.getNumThreadsPerSearchFor(CORES));
    assertEquals(1, budget.getNumThreadsPerSearchFor(CORES + 1));
    assertEquals(1, budget.getNumThreadsPerSearchFor(Integer.MAX_VALUE));
  }

  @Test
  void noSearchesGetsEveryThreadRatherThanDividingByZero() {
    ParallelismBudget budget = new ParallelismBudget(CORES, CORES);

    assertEquals(CORES, budget.getNumThreadsPerSearchFor(0));
    assertEquals(CORES, budget.getNumThreadsPerSearchFor(-1));
  }

  @Test
  void roundsDownRatherThanUp() {
    // Seven searches on 48 cores get 6 threads each, not 7, which would oversubscribe.
    assertEquals(6, new ParallelismBudget(CORES, CORES).getNumThreadsPerSearchFor(7));
  }

  /** The invariant the budget exists to hold. */
  @Test
  void budgetTimesConcurrencyStaysWithinTheBound() {
    ParallelismBudget budget = new ParallelismBudget(CORES, CORES);
    for (int concurrency = 1; concurrency <= CORES; concurrency++) {
      int threads = budget.getNumThreadsPerSearchFor(concurrency);
      assertTrue(
          (long) threads * concurrency <= CORES,
          "threads " + threads + " times concurrency " + concurrency + " exceeds " + CORES);
    }
  }

  @Test
  void startsAtTheFullBoundSoAnIdleEngineUsesEveryCore() {
    assertEquals(CORES, new ParallelismBudget(CORES, CORES).getNumThreadsPerSearch());
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

  /**
   * Attaches to install the runner, then stops the interval work so the test drives update itself.
   */
  private static void attachThen(
      ParallelismBudget budget, java.util.function.Consumer<Runnable> exclusively) {
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

  /**
   * The count a holder keeps no longer follows the concurrency, so an update leaves it alone and
   * suspends nothing, while the threads one search may use still follow.
   */
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

    assertEquals(List.of(), applied, "a constant count is never re-applied");
    assertEquals(1, budget.getNumThreadsPerSearch());
    assertEquals(
        List.of(), exclusiveCalls, "a count that does not change must not suspend any search");
  }

  @Test
  void averagesTheReadingsOfOneWindowBeforeUpdating() {
    // Ten readings averaging four concurrent searches, arriving as a burst and a lull, so a
    // maximum would read eight and an average four.
    int[] readings = {8, 8, 8, 8, 8, 0, 0, 0, 0, 0};
    ParallelismBudget budget = new ParallelismBudget(CORES, CORES);
    List<Integer> applied = new ArrayList<>();
    budget.onChange(applied::add);
    applied.clear();
    attachThen(budget, Runnable::run);

    for (int reading = 0; reading < readings.length - 1; reading++) {
      budget.sample(readings[reading]);
      assertEquals(
          CORES,
          budget.getNumThreadsPerSearch(),
          "a window still filling must not move either count");
    }
    budget.sample(readings[readings.length - 1]);

    assertEquals(CORES / 4, budget.getNumThreadsPerSearch(), "the average of the window");
    assertEquals(List.of(), applied, "a constant process-global count is never re-applied");
  }

  @Test
  void changingOnlyTheBudgetDoesNotQuiesceTheEngine() {
    // Four cores a socket, so eight concurrent searches and nine both leave the shared count at
    // one while the budget falls from four to three.
    ParallelismBudget budget = new ParallelismBudget(32, 4);
    List<Integer> applied = new ArrayList<>();
    budget.onChange(applied::add);
    List<String> exclusiveCalls = new ArrayList<>();
    attachThen(
        budget,
        task -> {
          exclusiveCalls.add("entered");
          task.run();
        });
    budget.update(8);
    applied.clear();
    exclusiveCalls.clear();

    budget.update(9);

    assertEquals(3, budget.getNumThreadsPerSearch(), "the budget follows the concurrency");
    assertEquals(List.of(), applied, "the shared count is unchanged");
    assertEquals(
        List.of(), exclusiveCalls, "only a change of the shared count may quiesce the engine");
  }

  /**
   * A process whose structures all choose their own thread count registers nothing, so the shared
   * count it derives applies to no one and the searches it would stop are all other structures'.
   */
  @Test
  void registeringNoSharedThreadCountQuiescesTheEngineForNothing() {
    ParallelismBudget budget = new ParallelismBudget(32, 4);
    List<String> exclusiveCalls = new ArrayList<>();
    attachThen(
        budget,
        task -> {
          exclusiveCalls.add("entered");
          task.run();
        });

    // Four concurrent searches move the shared count from four to one.
    budget.update(4);

    assertEquals(8, budget.getNumThreadsPerSearch(), "the budget still follows the concurrency");
    assertEquals(
        List.of(), exclusiveCalls, "a count no structure holds must not quiesce the engine");
  }

  @Test
  void updateLeavesAnUnchangedBudgetAlone() {
    ParallelismBudget budget = new ParallelismBudget(CORES, CORES);
    List<Integer> applied = new ArrayList<>();
    budget.onChange(applied::add);
    applied.clear();
    List<String> exclusiveCalls = new ArrayList<>();
    attachThen(budget, task -> exclusiveCalls.add("entered"));

    // One concurrent search wants the full bound, which is where the budget already is.
    budget.update(1);

    assertEquals(List.of(), applied);
    assertEquals(List.of(), exclusiveCalls, "an unchanged budget must not quiesce the engine");
  }

  @Test
  void rebudgetsWhileAtLeastOneEngineIsAttached() {
    ParallelismBudget budget = new ParallelismBudget(CORES, CORES);

    budget.attach(() -> 1, Runnable::run);
    budget.attach(() -> 1, Runnable::run);

    assertEquals(2, budget.getNumAttachments());
    assertTrue(budget.isRebudgeting());

    // One engine closing must not stop the other engine's budget.
    budget.detach();

    assertEquals(1, budget.getNumAttachments());
    assertTrue(budget.isRebudgeting());

    budget.detach();

    assertEquals(0, budget.getNumAttachments());
    assertFalse(budget.isRebudgeting(), "the last engine detaching stops the work");
  }

  @Test
  void detachingMoreOftenThanAttachingDoesNothing() {
    ParallelismBudget budget = new ParallelismBudget(CORES, CORES);

    budget.detach();
    budget.attach(() -> 1, Runnable::run);
    budget.detach();
    budget.detach();

    assertEquals(0, budget.getNumAttachments());
    assertFalse(budget.isRebudgeting());
  }

  @Test
  void anUnattachedBudgetStaysAtItsFullValue() {
    ParallelismBudget budget = new ParallelismBudget(CORES, CORES);

    assertFalse(budget.isRebudgeting());
    assertEquals(CORES, budget.getNumThreadsPerSearch());
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

    assertEquals(List.of(), applied, "a constant process-global count is never re-applied");
  }
}
