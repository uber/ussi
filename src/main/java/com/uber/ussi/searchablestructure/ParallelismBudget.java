/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/**
 * How many threads one search may use.
 *
 * <p>Threads within a single search only pay off while cores are otherwise idle. The thread count
 * multiplied by the number of concurrent searches oversubscribes the machine as soon as it exceeds
 * the core count, which inflates tail latency, so the budget is the core count divided by the
 * concurrency and is re-derived as the concurrency changes.
 *
 * <p>A structure that can choose its parallelism per search reads {@link #budget()}. A structure
 * whose thread count is a process-global setting cannot, because such a setting is shared by every
 * search in flight and is expensive to change; it registers with {@link #onChange} instead and is
 * called while no search is running.
 */
public final class ParallelismBudget {

  private static final ParallelismBudget SHARED =
      new ParallelismBudget(Math.max(1, Runtime.getRuntime().availableProcessors()));

  private static final long REBUDGET_INTERVAL_MILLIS = 1_000;

  private final int maxThreadsPerSearch;
  private volatile int budget;
  private volatile IntConsumer onChange = threads -> {};
  private boolean attached;

  ParallelismBudget(int maxThreadsPerSearch) {
    if (maxThreadsPerSearch < 1) {
      throw new IllegalArgumentException("maxThreadsPerSearch must be >= 1.");
    }
    this.maxThreadsPerSearch = maxThreadsPerSearch;
    this.budget = maxThreadsPerSearch;
  }

  public static ParallelismBudget shared() {
    return SHARED;
  }

  /** Threads the caller may use for one search. */
  public int budget() {
    return budget;
  }

  /** Registers the holder of a process-global thread count. The last registration wins. */
  public void onChange(IntConsumer applier) {
    this.onChange = applier;
    applier.accept(budget);
  }

  /**
   * Starts re-deriving the budget, from the concurrency {@code concurrencySource} reports for each
   * interval and using {@code exclusively} to reach a moment with no search in flight. Called once
   * by the engine; an unattached budget stays at its full value.
   */
  public synchronized void attach(
      IntSupplier concurrencySource, Consumer<Runnable> exclusively) {
    if (attached) {
      throw new IllegalStateException("The budget is already attached.");
    }
    attached = true;
    ScheduledExecutorService rebudgeter =
        Executors.newSingleThreadScheduledExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "ussi-parallelism-budget");
              thread.setDaemon(true);
              return thread;
            });
    rebudgeter.scheduleWithFixedDelay(
        () -> update(concurrencySource.getAsInt(), exclusively),
        REBUDGET_INTERVAL_MILLIS,
        REBUDGET_INTERVAL_MILLIS,
        TimeUnit.MILLISECONDS);
  }

  /**
   * The budget for the given number of concurrent searches. Rounding down keeps the budget
   * multiplied by the concurrency within the core count. A concurrency below one is treated as one,
   * which covers an interval with no searches and leaves the divisor non-zero.
   */
  int budgetFor(int concurrency) {
    return Math.max(1, maxThreadsPerSearch / Math.max(1, concurrency));
  }

  /**
   * Re-derives the budget from the concurrency just observed. A registered process-global thread
   * count is changed through {@code exclusively}, which must run its argument with no search in
   * flight, since such a setting cannot be changed underneath a search that is using it.
   */
  void update(int concurrency, Consumer<Runnable> exclusively) {
    int updated = budgetFor(concurrency);
    if (updated == budget) {
      return;
    }
    exclusively.accept(
        () -> {
          onChange.accept(updated);
          budget = updated;
        });
  }
}
