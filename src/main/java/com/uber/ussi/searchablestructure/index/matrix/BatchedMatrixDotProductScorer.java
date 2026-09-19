/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.matrix;

import com.uber.ussi.searchablestructure.result.RowNumAndSimilarity;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Scores several queries against one matrix in a single multiply, when several are waiting.
 *
 * <p>A multiply against the whole matrix costs about what reading the matrix costs, so queries
 * that share one multiply share that cost, and the underlying implementation reaches an
 * arithmetic intensity a single query cannot give it. The gain grows with the number of queries
 * combined, which is why the alternative of dividing the machine between concurrent queries loses
 * as concurrency rises: it makes each multiply narrower exactly when there is most to share.
 *
 * <p>No query waits for a query that has not arrived. A caller enqueues its own and then contends
 * to perform the multiply; whichever caller acquires it multiplies everything enqueued at that
 * instant, and the others wait only for the multiply already running. The combined count is
 * therefore a measurement of the offered load rather than a configured window, it is one when the
 * machine is idle, and no arrival policy or timer is needed to obtain it.
 *
 * <p>Callers are serialized, so an implementation holds the machine's full width for one multiply
 * rather than dividing it between concurrent multiplies.
 *
 * <p>{@code S} is what one multiply produces for one query, which an implementation reads back in
 * {@link #collectRows collectRows()} and nothing else interprets. An implementation computing its
 * products in memory this process cannot read returns whatever it kept there, and chooses rows
 * where it computed them rather than copying a product per row back.
 *
 * <p>Choosing runs on the thread that asked for the query, after the multiply that scored it has
 * finished and released the next one. Several queries therefore choose at the same time and
 * overlap the following multiply, which the multiply itself cannot do.
 */
abstract class BatchedMatrixDotProductScorer<S> implements MatrixDotProductScorer {

  /**
   * How long a waiting caller sleeps before contending again. Short enough that a caller whose
   * query was not taken resumes promptly, long enough that waiting callers do not spin.
   */
  private static final long WAIT_MICROS = 50;

  private final DenseMatrix matrix;
  private final MatrixRows rows;
  private final int maxNumQueriesInAMultiply;
  private final ReentrantLock multiplying = new ReentrantLock();
  private final ArrayDeque<Query<S>> waiting = new ArrayDeque<>();
  // Read and written only by the caller holding multiplying.
  private final Query<S>[] taken;
  private final float[][] takenQueryValues;
  private final RowSelection[] takenSelections;
  private final List<S> takenResults;
  private final AtomicLong numMultiplies = new AtomicLong();
  private final AtomicLong numQueriesMultiplied = new AtomicLong();
  private volatile boolean closed;

  @SuppressWarnings("unchecked")
  BatchedMatrixDotProductScorer(DenseMatrix matrix, MatrixRows rows, int maxNumQueriesInAMultiply) {
    if (maxNumQueriesInAMultiply < 1) {
      throw new IllegalArgumentException("maxNumQueriesInAMultiply must be >= 1.");
    }
    this.matrix = matrix;
    this.rows = rows;
    this.maxNumQueriesInAMultiply = maxNumQueriesInAMultiply;
    this.taken = new Query[maxNumQueriesInAMultiply];
    this.takenQueryValues = new float[maxNumQueriesInAMultiply][];
    this.takenSelections = new RowSelection[maxNumQueriesInAMultiply];
    this.takenResults = new ArrayList<>(maxNumQueriesInAMultiply);
  }

  @Override
  public final List<RowNumAndSimilarity> selectRows(
      float[] queryValues, RowSelection selection) {
    if (closed) {
      throw new IllegalStateException("This scorer is already closed.");
    }
    MatrixDotProductScorers.validateQueryLength(matrix, queryValues);
    Query<S> query = new Query<>(queryValues, selection, newMultiplyResult());
    synchronized (waiting) {
      waiting.addLast(query);
    }
    while (!query.isMultiplied()) {
      if (multiplying.tryLock()) {
        try {
          if (query.isMultiplied()) {
            break;
          }
          multiplyWhateverIsWaiting();
        } finally {
          multiplying.unlock();
        }
      } else {
        query.awaitBriefly();
      }
    }
    RowCollector collector = new RowCollector(selection);
    collectRows(query.result, selection, collector);
    return collector.toList();
  }

  @Override
  public final void close() {
    if (!closed) {
      closed = true;
      releaseResources();
    }
  }

  /** Somewhere for one query's multiply to put what it produced. Reused for nothing else. */
  protected abstract S newMultiplyResult();

  /** Scores one query against the whole matrix into {@code into}. */
  protected abstract void multiplyOneQuery(float[] queryValues, RowSelection selection, S into);

  /**
   * Scores the first {@code numQueries} of {@code queryValues} against the whole matrix in one
   * multiply, each into its own entry of {@code into}.
   *
   * <p>Never called with fewer than two queries, since one query does not repay what combining
   * costs. The selections are given so that an implementation able to choose rows during the
   * multiply has what choosing needs.
   */
  protected abstract void multiplyQueries(
      float[][] queryValues, RowSelection[] selections, List<S> into, int numQueries);

  /**
   * Offers to {@code collector} the rows worth keeping, from what this query's multiply produced.
   *
   * <p>The bound and the ordering belong to the collector, so an implementation that has already
   * reduced its rows offers what survived and one holding a similarity for every row offers every
   * row, and both reach the same answer.
   */
  protected abstract void collectRows(S result, RowSelection selection, RowCollector collector);

  /** Releases whatever the implementation allocated. Called once. */
  protected abstract void releaseResources();

  protected final DenseMatrix getMatrix() {
    return matrix;
  }

  protected final MatrixRows getRows() {
    return rows;
  }

  /** Multiplies so far, and the queries they carried, which report how the load combined. */
  final long getNumMultiplies() {
    return numMultiplies.get();
  }

  final long getNumQueriesMultiplied() {
    return numQueriesMultiplied.get();
  }

  /** Takes the queue as it stands and scores all of it. Never waits for an arrival. */
  private void multiplyWhateverIsWaiting() {
    int numQueries = 0;
    synchronized (waiting) {
      while (!waiting.isEmpty() && numQueries < maxNumQueriesInAMultiply) {
        taken[numQueries++] = waiting.pollFirst();
      }
    }
    if (numQueries == 0) {
      return;
    }
    try {
      if (numQueries == 1) {
        multiplyOneQuery(taken[0].queryValues, taken[0].selection, taken[0].result);
      } else {
        takenResults.clear();
        for (int i = 0; i < numQueries; ++i) {
          takenQueryValues[i] = taken[i].queryValues;
          takenSelections[i] = taken[i].selection;
          takenResults.add(taken[i].result);
        }
        multiplyQueries(takenQueryValues, takenSelections, takenResults, numQueries);
      }
      numMultiplies.incrementAndGet();
      numQueriesMultiplied.addAndGet(numQueries);
    } finally {
      // Released even when the multiply failed, so a caller is never left waiting on a multiply
      // that will not happen again.
      for (int i = 0; i < numQueries; ++i) {
        taken[i].multiplied();
        taken[i] = null;
      }
    }
  }

  /** One query waiting to be multiplied, and what its multiply will produce. */
  private static final class Query<S> {
    private final float[] queryValues;
    private final RowSelection selection;
    private final S result;
    private final CountDownLatch multiplied = new CountDownLatch(1);

    Query(float[] queryValues, RowSelection selection, S result) {
      this.queryValues = queryValues;
      this.selection = selection;
      this.result = result;
    }

    boolean isMultiplied() {
      return multiplied.getCount() == 0;
    }

    void multiplied() {
      multiplied.countDown();
    }

    void awaitBriefly() {
      try {
        multiplied.await(WAIT_MICROS, TimeUnit.MICROSECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Interrupted while waiting for a dense multiply.", e);
      }
    }
  }
}
