/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import javax.annotation.Nullable;

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
 *
 * <p>The two counts are divided out of different numbers of cores. Work handed to {@link
 * SearchThreads} waits in a queue between its turns and spreads over every core the machine has. A
 * process-global thread count belongs to a library holding threads of its own, which occupy their
 * cores for as long as the setting stands, and such threads gain nothing past the cores of one
 * socket that {@link ProcessorTopology} reports.
 */
public final class ParallelismBudget {

  private static final long REBUDGET_INTERVAL_MILLIS = 1_000;

  private static final ParallelismBudget SHARED =
      new ParallelismBudget(
          Math.max(1, Runtime.getRuntime().availableProcessors()),
          ProcessorTopology.getNumCoresPerSocket());

  private final int maxThreadsPerSearch;
  private final int maxNumSharedThreads;
  private volatile int numSharedThreads;
  private volatile int budget;
  private volatile IntConsumer onChange = threads -> {};
  // Until an engine attaches there are no searches, so running a change inline is already
  // exclusive.
  private volatile Consumer<Runnable> exclusively = Runnable::run;
  private int attachments;
  @Nullable private ScheduledExecutorService rebudgeter;

  ParallelismBudget(int maxThreadsPerSearch, int maxNumSharedThreads) {
    if (maxThreadsPerSearch < 1) {
      throw new IllegalArgumentException("maxThreadsPerSearch must be >= 1.");
    }
    if (maxNumSharedThreads < 1 || maxNumSharedThreads > maxThreadsPerSearch) {
      throw new IllegalArgumentException(
          "maxNumSharedThreads must be >= 1 and <= maxThreadsPerSearch.");
    }
    this.maxThreadsPerSearch = maxThreadsPerSearch;
    this.maxNumSharedThreads = maxNumSharedThreads;
    this.budget = maxThreadsPerSearch;
    this.numSharedThreads = maxNumSharedThreads;
  }

  public static ParallelismBudget shared() {
    return SHARED;
  }

  /** Threads the caller may use for one search. */
  public int budget() {
    return budget;
  }

  /**
   * Registers the holder of a process-global thread count and applies the current budget to it. The
   * last registration wins.
   *
   * <p>Applied with no search in flight, because registration happens whenever a structure is built
   * and a structure can be built while other searches are running. Such a setting is shared by
   * every search in flight, so changing it underneath one is not safe.
   */
  public void onChange(IntConsumer applier) {
    exclusively.accept(
        () -> {
          this.onChange = applier;
          applier.accept(numSharedThreads);
        });
  }

  /**
   * Starts re-deriving the budget, from the concurrency {@code concurrencySource} reports for each
   * interval and using {@code exclusively} to reach a moment with no search in flight. An
   * unattached budget stays at its full value.
   *
   * <p>Each engine attaches and {@link #detach() detaches}, and the work runs while at least one is
   * attached. Concurrency is measured process-wide, so every engine reports the same thing and the
   * first attachment's source is the one used.
   */
  public synchronized void attach(IntSupplier concurrencySource, Consumer<Runnable> exclusively) {
    if (attachments++ > 0) {
      return;
    }
    this.exclusively = exclusively;
    rebudgeter =
        Executors.newSingleThreadScheduledExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "ussi-parallelism-budget");
              thread.setDaemon(true);
              return thread;
            });
    rebudgeter.scheduleWithFixedDelay(
        () -> update(concurrencySource.getAsInt()),
        REBUDGET_INTERVAL_MILLIS,
        REBUDGET_INTERVAL_MILLIS,
        TimeUnit.MILLISECONDS);
  }

  /** Stops re-deriving the budget once the last engine has detached. */
  public synchronized void detach() {
    if (attachments == 0 || --attachments > 0) {
      return;
    }
    rebudgeter.shutdownNow();
    rebudgeter = null;
  }

  int attachments() {
    return attachments;
  }

  boolean isRebudgeting() {
    return rebudgeter != null;
  }

  /**
   * The budget for the given number of concurrent searches. Rounding down keeps the budget
   * multiplied by the concurrency within the core count. A concurrency below one is treated as one,
   * which covers an interval with no searches and leaves the divisor non-zero.
   */
  int budgetFor(int concurrency) {
    return Math.max(1, maxThreadsPerSearch / Math.max(1, concurrency));
  }

  /** Process-global threads for the given number of concurrent searches. */
  int getNumSharedThreadsFor(int concurrency) {
    return Math.max(1, maxNumSharedThreads / Math.max(1, concurrency));
  }

  /** Re-derives both counts from the concurrency just observed. */
  void update(int concurrency) {
    int updatedBudget = budgetFor(concurrency);
    int updatedNumSharedThreads = getNumSharedThreadsFor(concurrency);
    if (updatedBudget == budget && updatedNumSharedThreads == numSharedThreads) {
      return;
    }
    exclusively.accept(
        () -> {
          onChange.accept(updatedNumSharedThreads);
          budget = updatedBudget;
          numSharedThreads = updatedNumSharedThreads;
        });
  }
}
