/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.utils.parallel;

import com.uber.ussi.ProcessorAllowance;
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
 * <p>The budget derives two thread counts, for two ways of using threads that cannot share one
 * number.
 *
 * <p>{@link #getNumThreadsPerSearch()} is read by a structure that divides its own search and
 * submits the pieces to {@link SearchThreads}. It is the processors the machine has divided by the
 * concurrent searches. Such work waits in a queue when the cores are busy and otherwise spreads
 * over every core, so the threads of all the concurrent searches together stay within the cores.
 *
 * <p>The threads per batch is held by a native library rather than by any one structure. It is
 * applied to that library through {@link #onNumThreadsPerBatchChange onNumThreadsPerBatchChange()}
 * rather than read, because one setting serves every search, and changing it while a call is
 * dispatching work may deadlock or corrupt memory. It is the cores of one socket that {@link
 * ProcessorTopology} reports, undivided: a library holding such a count serializes its callers and
 * multiplies the queries that accumulate as one batch, so one call at a time uses all of those
 * threads and there is nothing to divide. Threads of such a library gain nothing past one socket,
 * since two hardware threads of a core share that core's execution units and a socket reaches
 * another socket's memory over a link.
 *
 * <p>Being undivided, the threads per batch never changes after its holder registers, so no search
 * is suspended to apply it.
 */
public final class ParallelismBudget {

  private static final long SAMPLE_INTERVAL_MILLIS = 1_000;

  /**
   * Stands in until a process-global thread count is registered, and is compared against to tell
   * that none is.
   */
  private static final IntConsumer NO_HOLDER = threads -> {};

  /**
   * Readings averaged into one update, which bounds how often a process-global thread count moves
   * and so how often the engine is quiesced to move it.
   */
  private static final int NUM_SAMPLES_PER_UPDATE = 10;

  private static final ParallelismBudget SHARED =
      new ParallelismBudget(
          ProcessorAllowance.shared().getNumProcessors(),
          ProcessorTopology.getNumCoresPerSocket());

  private volatile int maxNumThreadsPerSearch;
  private final int socketCores;
  private volatile int maxNumThreadsPerBatch;
  private volatile int numThreadsPerBatch;
  private volatile int numThreadsPerSearch;
  private volatile IntConsumer onNumThreadsPerBatchChange = NO_HOLDER;
  /**
   * Applied under {@link #exclusively} when the processor allowance changes, so the admission
   * semaphore and the search pool resize with no search running. Registered by the facade that
   * owns the admission.
   */
  private volatile IntConsumer onAllowanceChange = allowance -> {};
  /**
   * Until {@link #attach attach()} there are no searches, so running a change inline is already
   * exclusive.
   */
  private volatile Consumer<Runnable> exclusively = Runnable::run;
  private int numAttachments;
  @Nullable private ScheduledExecutorService rebudgeter;
  // Read and written by the rebudgeter's single thread alone.
  private int numSamples;
  private long numConcurrentSearchesSampled;

  ParallelismBudget(int maxNumThreadsPerSearch, int maxNumThreadsPerBatch) {
    if (maxNumThreadsPerSearch < 1) {
      throw new IllegalArgumentException("maxNumThreadsPerSearch must be >= 1.");
    }
    if (maxNumThreadsPerBatch < 1 || maxNumThreadsPerBatch > maxNumThreadsPerSearch) {
      throw new IllegalArgumentException(
          "maxNumThreadsPerBatch must be >= 1 and <= maxNumThreadsPerSearch.");
    }
    this.maxNumThreadsPerSearch = maxNumThreadsPerSearch;
    this.socketCores = maxNumThreadsPerBatch;
    this.maxNumThreadsPerBatch = maxNumThreadsPerBatch;
    this.numThreadsPerSearch = maxNumThreadsPerSearch;
    this.numThreadsPerBatch = maxNumThreadsPerBatch;
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
   * every concurrent search, so modifying it during one is unsafe.
   */
  public synchronized void onNumThreadsPerBatchChange(IntConsumer applier) {
    exclusively.accept(
        () -> {
          this.onNumThreadsPerBatchChange = applier;
          applier.accept(numThreadsPerBatch);
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
    numSamples = 0;
    numConcurrentSearchesSampled = 0;
    rebudgeter.scheduleWithFixedDelay(
        () -> sample(numConcurrentSearchesSource.getAsInt()),
        SAMPLE_INTERVAL_MILLIS,
        SAMPLE_INTERVAL_MILLIS,
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

  /**
   * Re-derives {@link #maxNumThreadsPerSearch} from the processor allowance and re-derives both
   * counts from the current concurrency. Must be called under {@link #exclusively}, since a running
   * search reads {@link #numThreadsPerSearch} and a native library reads {@link
   * #numThreadsPerBatch}.
   */
  void applyAllowanceChange(int numConcurrentSearches) {
    int newMax = ProcessorAllowance.shared().getNumProcessors();
    if (newMax < 1) {
      newMax = 1;
    }
    maxNumThreadsPerSearch = newMax;
    maxNumThreadsPerBatch = Math.min(socketCores, newMax);
    onAllowanceChange.accept(newMax);
    update(numConcurrentSearches);
  }

  /**
   * Registers the callback applied when the processor allowance changes, so the admission
   * semaphore and the search pool resize under a moment with no search running.
   */
  public void onAllowanceChange(IntConsumer callback) {
    onAllowanceChange = java.util.Objects.requireNonNull(callback, "callback");
  }

  int getNumAttachments() {
    return numAttachments;
  }

  boolean isRebudgeting() {
    return rebudgeter != null;
  }

  /**
   * The threads one search may use at the given number of concurrent searches. Rounding down keeps
   * the threads of all of them within the core count. A number below one is treated as one, which
   * covers an interval holding no search and leaves the divisor non-zero.
   */
  int getNumThreadsPerSearchFor(int numConcurrentSearches) {
    return Math.max(1, maxNumThreadsPerSearch / Math.max(1, numConcurrentSearches));
  }

  /**
   * The process-global threads to hold at the given number of concurrent searches, which is every
   * thread the holder may have whatever the concurrency.
   *
   * <p>Such a count is divided only where concurrent searches call the library at once, and the
   * holders of one serialize their callers instead, so there is nothing to divide: one call holds
   * the whole width and the searches behind it wait. Dividing would narrow every call exactly as
   * load rises, which measurement showed to be the worse arrangement by a wide margin.
   *
   * <p>The count is therefore constant, which means it is applied once when its holder registers
   * and never again, so no search is ever suspended to change it.
   */
  int getNumThreadsPerBatchFor(int numConcurrentSearches) {
    return maxNumThreadsPerBatch;
  }

  /**
   * Takes one reading of the concurrent searches, and re-derives both counts once {@link
   * #NUM_SAMPLES_PER_UPDATE} readings are in.
   *
   * <p>The readings are averaged rather than maximised. A maximum over an interval is biased upward
   * by the length of that interval, so the estimate it yields depends on the sampling window rather
   * than on the load. The mean is invariant to the window: a process serving one long search at a
   * time estimates one concurrent search under any number of readings, so the window is chosen for
   * the cost of updating alone.
   */
  void sample(int numConcurrentSearches) {
    numConcurrentSearchesSampled += Math.max(0, numConcurrentSearches);
    if (++numSamples < NUM_SAMPLES_PER_UPDATE) {
      return;
    }
    int averageNumConcurrentSearches =
        (int) Math.round((double) numConcurrentSearchesSampled / numSamples);
    numSamples = 0;
    numConcurrentSearchesSampled = 0;
    if (isRebudgeting() && allowanceChanged()) {
      exclusively.accept(() -> applyAllowanceChange(averageNumConcurrentSearches));
    } else {
      update(averageNumConcurrentSearches);
    }
  }

  private boolean allowanceChanged() {
    ProcessorAllowance.shared().refresh();
    return ProcessorAllowance.shared().getNumProcessors() != maxNumThreadsPerSearch;
  }

  /**
   * Re-derives both counts from the given number of concurrent searches.
   *
   * <p>The threads one search may use is read by each search for itself, so it is assigned here and
   * needs no moment without searches. A process-global thread count is one setting every concurrent
   * search shares. A library holding one may deadlock or corrupt memory if it is modified while a
   * call is dispatching work. It is therefore applied with no search running, and only when it
   * differs from the count already in effect.
   *
   * <p>Reaching a moment without searches suspends every search in the process, including those of
   * structures holding no process-global count of their own. It is therefore reached only when such
   * a count is registered and its value has changed. A process in which none is registered, and a
   * process whose load leaves the count unchanged, are never suspended.
   */
  void update(int numConcurrentSearches) {
    numThreadsPerSearch = getNumThreadsPerSearchFor(numConcurrentSearches);
    int updatedNumThreadsPerBatch = getNumThreadsPerBatchFor(numConcurrentSearches);
    synchronized (this) {
      if (updatedNumThreadsPerBatch == numThreadsPerBatch) {
        return;
      }
      if (onNumThreadsPerBatchChange == NO_HOLDER) {
        numThreadsPerBatch = updatedNumThreadsPerBatch;
        return;
      }
    }
    exclusively.accept(
        () -> {
          onNumThreadsPerBatchChange.accept(updatedNumThreadsPerBatch);
          numThreadsPerBatch = updatedNumThreadsPerBatch;
        });
  }
}
