/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.parallel;

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
 * the core count, which inflates tail latency. The budget is therefore the core count divided by
 * the concurrent searches, and is re-derived as that number changes.
 *
 * <p>A structure choosing its own thread count per search reads {@link #getNumThreadsPerSearch()}.
 * A structure whose thread count is a process-global setting cannot, because such a setting is
 * shared by every concurrent search and is expensive to change. Such a structure registers with
 * {@link #onChange onChange()} instead, and is called while no search is running.
 *
 * <p>The two counts are divided out of different numbers of cores. Work submitted to {@link
 * SearchThreads} waits in its queue and spreads over every core the machine has. A process-global
 * thread count belongs to a library holding threads of its own, which occupy their cores for as
 * long as the setting stands, and such threads gain nothing past the cores of one socket that
 * {@link ProcessorTopology} reports.
 */
public final class ParallelismBudget {

  private static final long REBUDGET_INTERVAL_MILLIS = 1_000;

  private static final ParallelismBudget SHARED =
      new ParallelismBudget(
          Math.max(1, Runtime.getRuntime().availableProcessors()),
          ProcessorTopology.getNumCoresPerSocket());

  private final int maxNumThreadsPerSearch;
  private final int maxNumSharedThreads;
  private volatile int numSharedThreads;
  private volatile int numThreadsPerSearch;
  private volatile IntConsumer onChange = threads -> {};
  /**
   * Until {@link #attach attach()} there are no searches, so running a change inline is already
   * exclusive.
   */
  private volatile Consumer<Runnable> exclusively = Runnable::run;
  private int numAttachments;
  @Nullable private ScheduledExecutorService rebudgeter;

  ParallelismBudget(int maxNumThreadsPerSearch, int maxNumSharedThreads) {
    if (maxNumThreadsPerSearch < 1) {
      throw new IllegalArgumentException("maxNumThreadsPerSearch must be >= 1.");
    }
    if (maxNumSharedThreads < 1 || maxNumSharedThreads > maxNumThreadsPerSearch) {
      throw new IllegalArgumentException(
          "maxNumSharedThreads must be >= 1 and <= maxNumThreadsPerSearch.");
    }
    this.maxNumThreadsPerSearch = maxNumThreadsPerSearch;
    this.maxNumSharedThreads = maxNumSharedThreads;
    this.numThreadsPerSearch = maxNumThreadsPerSearch;
    this.numSharedThreads = maxNumSharedThreads;
  }

  public static ParallelismBudget shared() {
    return SHARED;
  }

  /** Threads the caller may use for one search. */
  public int getNumThreadsPerSearch() {
    return numThreadsPerSearch;
  }

  /**
   * Registers the holder of a process-global thread count and applies the current count to it. The
   * last registration wins.
   *
   * <p>Applied with no search running, because registration happens whenever a structure is built
   * and a structure can be built while other searches are running. Such a setting is shared by
   * every concurrent search, so changing it underneath one is not safe.
   */
  public void onChange(IntConsumer applier) {
    exclusively.accept(
        () -> {
          this.onChange = applier;
          applier.accept(numSharedThreads);
        });
  }

  /**
   * Starts re-deriving both counts, from the concurrent searches {@code
   * numConcurrentSearchesSource} reports for each interval, using {@code exclusively} to reach a
   * moment with no search running. An unattached budget stays at its full value.
   *
   * <p>Every attachment is ended by {@link #detach()}, and the work runs while at least one
   * attachment stands. Concurrent searches are counted across the process, so every attachment
   * reports the same number, and the source of the first is the one used.
   */
  public synchronized void attach(
      IntSupplier numConcurrentSearchesSource, Consumer<Runnable> exclusively) {
    if (numAttachments++ > 0) {
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
        () -> update(numConcurrentSearchesSource.getAsInt()),
        REBUDGET_INTERVAL_MILLIS,
        REBUDGET_INTERVAL_MILLIS,
        TimeUnit.MILLISECONDS);
  }

  /** Stops re-deriving both counts once the last attachment has ended. */
  public synchronized void detach() {
    if (numAttachments == 0 || --numAttachments > 0) {
      return;
    }
    rebudgeter.shutdownNow();
    rebudgeter = null;
  }

  int getNumAttachments() {
    return numAttachments;
  }

  boolean isRebudgeting() {
    return rebudgeter != null;
  }

  /**
   * The threads one search may use at the given number of concurrent searches. Rounding
   * down keeps the threads of all of them within the core count. A number below one is treated as
   * one, which covers an interval holding no search and leaves the divisor non-zero.
   */
  int getNumThreadsPerSearchFor(int numConcurrentSearches) {
    return Math.max(1, maxNumThreadsPerSearch / Math.max(1, numConcurrentSearches));
  }

  /** The process-global threads to hold at the given number of concurrent searches. */
  int getNumSharedThreadsFor(int numConcurrentSearches) {
    return Math.max(1, maxNumSharedThreads / Math.max(1, numConcurrentSearches));
  }

  /** Re-derives both counts from the concurrent searches just counted. */
  void update(int numConcurrentSearches) {
    int updatedNumThreadsPerSearch = getNumThreadsPerSearchFor(numConcurrentSearches);
    int updatedNumSharedThreads = getNumSharedThreadsFor(numConcurrentSearches);
    if (updatedNumThreadsPerSearch == numThreadsPerSearch
        && updatedNumSharedThreads == numSharedThreads) {
      return;
    }
    exclusively.accept(
        () -> {
          onChange.accept(updatedNumSharedThreads);
          numThreadsPerSearch = updatedNumThreadsPerSearch;
          numSharedThreads = updatedNumSharedThreads;
        });
  }
}
