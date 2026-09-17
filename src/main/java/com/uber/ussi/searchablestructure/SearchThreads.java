/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure;

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
 * <p>Every structure that divides a search hands the pieces here, so that the threads in flight for
 * one search stay within the budget {@link ParallelismBudget} allows it and the searches in flight
 * together stay within the cores. A structure holding threads of its own would spend a budget the
 * others had already been promised.
 *
 * <p>A search runs as many pieces at a time as its budget allows and the rest wait their turn.
 * Waiting in turn would otherwise cost a search its place, since one returning for its next pieces
 * would queue behind every search that arrived in the meantime, so the pool serves by the ticket a
 * search takes once rather than by the order pieces were submitted.
 *
 * <p>The calling thread runs one piece of every turn rather than only waiting. This uses the thread
 * already here, and it keeps the search moving when every pool thread is busy.
 */
public final class SearchThreads {

  private static final ThreadPoolExecutor SEARCHERS = createSearchers();

  /** Taken once per query, so that every piece of one query is served at the query's arrival. */
  private static final AtomicLong NEXT_TICKET = new AtomicLong();

  /** The ticket of the query this thread is serving, while it is inside {@link #underOneTicket}. */
  private static final ThreadLocal<Long> CURRENT_TICKET = new ThreadLocal<>();

  private SearchThreads() {}

  /**
   * Runs {@code query} under one ticket, so that every structure it searches is served at the
   * query's arrival rather than at the arrival of each search within it.
   *
   * <p>A query visits its structures one after another, and each may divide its own search. Without
   * this, the pieces of a later structure would take a later ticket and queue behind the queries
   * that arrived while the earlier structures were being searched.
   */
  public static void underOneTicket(Runnable query) {
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
   * Runs {@code numPieces} pieces of one search, numbered from zero, and returns once all of them
   * have finished.
   *
   * <p>A piece that fails is rethrown once the rest have finished. Returning before then would
   * leave a piece reading a structure that the read lock its caller searches under is no longer
   * protecting.
   */
  public static void run(int numPieces, IntConsumer piece) {
    if (numPieces <= 0) {
      return;
    }
    if (numPieces == 1) {
      // Run on the calling thread, so a search in one piece pays no hand-off.
      piece.accept(0);
      return;
    }
    Long queryTicket = CURRENT_TICKET.get();
    long ticket = queryTicket != null ? queryTicket : NEXT_TICKET.getAndIncrement();
    int numAtOnce = Math.max(1, Math.min(numPieces, ParallelismBudget.shared().budget()));
    for (int firstOfTurn = 0; firstOfTurn < numPieces; firstOfTurn += numAtOnce) {
      int afterTurn = Math.min(numPieces, firstOfTurn + numAtOnce);
      List<Future<?>> handedOff = new ArrayList<>(afterTurn - firstOfTurn - 1);
      for (int pieceNumber = firstOfTurn + 1; pieceNumber < afterTurn; pieceNumber++) {
        int handedOffPiece = pieceNumber;
        handedOff.add(submit(ticket, () -> piece.accept(handedOffPiece)));
      }
      piece.accept(firstOfTurn);
      await(handedOff);
    }
  }

  /** Queues a piece to be served at its search's ticket rather than at its own submission. */
  private static Future<?> submit(long ticket, Runnable piece) {
    TicketedPiece<Void> ticketed =
        new TicketedPiece<>(
            ticket,
            () -> {
              piece.run();
              return null;
            });
    SEARCHERS.execute(ticketed);
    return ticketed;
  }

  /** Waits for every handed-off piece, and rethrows the first failure once all of them are done. */
  private static void await(List<Future<?>> handedOff) {
    RuntimeException failure = null;
    boolean interrupted = false;
    for (Future<?> piece : handedOff) {
      try {
        piece.get();
      } catch (InterruptedException e) {
        interrupted = true;
        failure = failure != null ? failure : new IllegalStateException(PIECE_FAILED, e);
      } catch (ExecutionException e) {
        // An undivided search would have thrown this from the caller's thread, so it is rethrown.
        RuntimeException thrown =
            e.getCause() instanceof RuntimeException runtimeCause
                ? runtimeCause
                : new IllegalStateException(PIECE_FAILED, e.getCause());
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

  private static final String PIECE_FAILED = "Failed to run one piece of a search.";

  private static ThreadPoolExecutor createSearchers() {
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

  /** A piece that waits its turn by its search's ticket, and by its own order within that. */
  private static final class TicketedPiece<T> extends FutureTask<T>
      implements Comparable<TicketedPiece<?>> {
    private static final AtomicLong NEXT_WITHIN_TICKET = new AtomicLong();

    private final long ticket;
    private final long withinTicket = NEXT_WITHIN_TICKET.getAndIncrement();

    TicketedPiece(long ticket, Callable<T> piece) {
      super(piece);
      this.ticket = ticket;
    }

    @Override
    public int compareTo(TicketedPiece<?> other) {
      int byTicket = Long.compare(ticket, other.ticket);
      return byTicket != 0 ? byTicket : Long.compare(withinTicket, other.withinTicket);
    }
  }
}
