/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.utils.parallel;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntConsumer;

/**
 * The threads one search may use, and the order they are given out in.
 *
 * <p>Every structure that divides a search submits its work units here, so that the concurrent
 * searches together stay within the cores. A structure holding threads of its own would spend
 * cores the others had already been promised.
 *
 * <p>A search submits every work unit it has. The pool holds one thread per core, so work units
 * wait in it while the cores are busy, and it serves them by the ticket a search takes once rather
 * than by the order they were submitted. A search therefore keeps its place while it is being
 * served, instead of queueing behind the searches that arrived in the meantime.
 *
 * <p>The calling thread runs one work unit rather than only waiting. This uses the thread already
 * here, and it keeps the search moving when every pool thread is busy.
 */
public final class SearchThreads {

  private static final ThreadPoolExecutor SEARCHERS = createThreadPool();

  /**
   * Taken once per query, so that every work unit of one query is served at the query's arrival.
   */
  private static final AtomicLong NEXT_TICKET = new AtomicLong();

  /**
   * The ticket of the query this thread is serving, while it is inside {@link #runUnderOneTicket
   * runUnderOneTicket()}.
   */
  private static final ThreadLocal<Long> CURRENT_TICKET = new ThreadLocal<>();

  private SearchThreads() {}

  /**
   * Runs {@code query} under one ticket, so that every structure it searches is served at the
   * query's arrival rather than at the arrival of each search within it.
   *
   * <p>A query visits its structures one after another, and each may divide its own search. Without
   * this, the work units of a later structure would take a later ticket and queue behind the
   * queries
   * that arrived while the earlier structures were being searched.
   */
  public static void runUnderOneTicket(Runnable query) {
    if (CURRENT_TICKET.get() != null) {
      query.run();
      return;
    }
    CURRENT_TICKET.set(NEXT_TICKET.getAndIncrement());
    try {
      query.run();
    } finally {
      CURRENT_TICKET.remove();
    }
  }

  /**
   * Runs {@code numWorkUnits} work units of one search, numbered from zero, and returns once all of
   * them
   * have finished.
   *
   * <p>A work unit that fails is rethrown once the rest have finished. Returning before then would
   * leave a work unit reading a structure that the read lock its caller searches under is no longer
   * protecting.
   */
  public static void runInParallel(int numWorkUnits, IntConsumer workUnit) {
    if (numWorkUnits <= 0) {
      return;
    }
    if (numWorkUnits == 1) {
      // Run on the calling thread, so a search of one work unit is not submitted at all.
      workUnit.accept(0);
      return;
    }
    Long queryTicket = CURRENT_TICKET.get();
    long ticket = queryTicket != null ? queryTicket : NEXT_TICKET.getAndIncrement();
    List<Future<?>> submitted = new ArrayList<>(numWorkUnits - 1);
    for (int workUnitNumber = 1; workUnitNumber < numWorkUnits; workUnitNumber++) {
      int submittedWorkUnit = workUnitNumber;
      submitted.add(submitWorkUnit(ticket, () -> workUnit.accept(submittedWorkUnit)));
    }
    workUnit.accept(0);
    awaitWorkUnits(submitted);
  }

  /** Queues a work unit to be served at its search's ticket rather than at its own submission. */
  private static Future<?> submitWorkUnit(long ticket, Runnable workUnit) {
    TicketedWorkUnit<Void> ticketed =
        new TicketedWorkUnit<>(
            ticket,
            () -> {
              workUnit.run();
              return null;
            });
    SEARCHERS.execute(ticketed);
    return ticketed;
  }

  /**
   * Waits for every submitted work unit, and rethrows the first failure once all of them are done.
   */
  private static void awaitWorkUnits(List<Future<?>> submitted) {
    RuntimeException failure = null;
    boolean interrupted = false;
    for (Future<?> submittedWorkUnit : submitted) {
      try {
        submittedWorkUnit.get();
      } catch (InterruptedException e) {
        interrupted = true;
        failure = failure != null ? failure : new IllegalStateException(WORK_UNIT_FAILED, e);
      } catch (ExecutionException e) {
        // An undivided search would have thrown this from the caller's thread, so it is rethrown.
        RuntimeException thrown =
            e.getCause() instanceof RuntimeException runtimeCause
                ? runtimeCause
                : new IllegalStateException(WORK_UNIT_FAILED, e.getCause());
        failure = failure != null ? failure : thrown;
      }
    }
    if (interrupted) {
      Thread.currentThread().interrupt();
    }
    if (failure != null) {
      throw failure;
    }
  }

  private static final String WORK_UNIT_FAILED = "Failed to run one work unit of a search.";

  private static ThreadPoolExecutor createThreadPool() {
    AtomicInteger threadNumber = new AtomicInteger(1);
    int numThreads = Math.max(1, Runtime.getRuntime().availableProcessors());
    return new ThreadPoolExecutor(
        numThreads,
        numThreads,
        0L,
        TimeUnit.MILLISECONDS,
        new PriorityBlockingQueue<>(),
        runnable -> {
          Thread thread = new Thread(runnable, "ussi-search-" + threadNumber.getAndIncrement());
          thread.setDaemon(true);
          return thread;
        });
  }

  /** A work unit ordered by its search's ticket, and by its own order within that search. */
  private static final class TicketedWorkUnit<T> extends FutureTask<T>
      implements Comparable<TicketedWorkUnit<?>> {
    private static final AtomicLong NEXT_WITHIN_TICKET = new AtomicLong();

    private final long ticket;
    private final long withinTicket = NEXT_WITHIN_TICKET.getAndIncrement();

    TicketedWorkUnit(long ticket, Callable<T> workUnit) {
      super(workUnit);
      this.ticket = ticket;
    }

    @Override
    public int compareTo(TicketedWorkUnit<?> other) {
      int byTicket = Long.compare(ticket, other.ticket);
      return byTicket != 0 ? byTicket : Long.compare(withinTicket, other.withinTicket);
    }
  }
}
