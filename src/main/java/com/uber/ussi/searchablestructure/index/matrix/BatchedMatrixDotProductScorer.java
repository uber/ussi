/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.matrix;

import java.util.ArrayDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Scores several queries against one matrix in a single multiply, when several are waiting.
 *
 * <p>A multiply against the whole matrix costs about what reading the matrix costs, so queries
 * that share one multiply share that cost, and the underlying library reaches an arithmetic
 * intensity a single query cannot give it. The gain therefore grows with the number of queries
 * combined, which is why the alternative of dividing the machine between concurrent queries loses
 * as concurrency rises: it makes each multiply narrower exactly when there is most to share.
 *
 * <p>No query ever waits for a query that has not arrived. A caller enqueues its own and then
 * contends to perform the multiply; whichever caller wins takes everything enqueued at that
 * instant and scores all of it, and the rest wait only for the multiply already running. The
 * combined count is therefore a measurement of the offered load rather than a configured window,
 * it is one when the machine is idle, and no arrival policy or timer is needed to obtain it.
 *
 * <p>Callers are serialized, so the implementation holds the machine's full width for one
 * multiply rather than dividing it between concurrent multiplies, and one set of working buffers
 * suffices for the whole scorer.
 */
abstract class BatchedMatrixDotProductScorer implements MatrixDotProductScorer {

  /**
   * How long a waiting caller sleeps before contending again. Short enough that a caller whose
   * query was not taken resumes promptly, long enough that waiting callers do not spin.
   */
  private static final long WAIT_MICROS = 50;

  private final DenseMatrix matrix;
  private final int maxNumQueriesInAMultiply;
  private final ReentrantLock multiplying = new ReentrantLock();
  private final ArrayDeque<Query> waiting = new ArrayDeque<>();
  // Read and written only by the caller holding multiplying.
  private final Query[] taken;
  private final float[][] takenQueryValues;
  private final float[][] takenDotProducts;
  private final AtomicLong numMultiplies = new AtomicLong();
  private final AtomicLong numQueriesMultiplied = new AtomicLong();
  private volatile boolean closed;

  BatchedMatrixDotProductScorer(DenseMatrix matrix, int maxNumQueriesInAMultiply) {
    if (maxNumQueriesInAMultiply < 1) {
      throw new IllegalArgumentException("maxNumQueriesInAMultiply must be >= 1.");
    }
    this.matrix = matrix;
    this.maxNumQueriesInAMultiply = maxNumQueriesInAMultiply;
    this.taken = new Query[maxNumQueriesInAMultiply];
    this.takenQueryValues = new float[maxNumQueriesInAMultiply][];
    this.takenDotProducts = new float[maxNumQueriesInAMultiply][];
  }

  @Override
  public final void score(float[] queryValues, float[] dotProducts) {
    if (closed) {
      throw new IllegalStateException("This scorer is already closed.");
    }
    MatrixDotProductScorers.validateScoreInputs(matrix, queryValues, dotProducts);
    Query query = new Query(queryValues, dotProducts);
    synchronized (waiting) {
      waiting.addLast(query);
    }
    while (!query.isScored()) {
      if (multiplying.tryLock()) {
        try {
          if (query.isScored()) {
            return;
          }
          multiplyWhateverIsWaiting();
        } finally {
          multiplying.unlock();
        }
      } else {
        query.awaitBriefly();
      }
    }
  }

  @Override
  public final void close() {
    if (!closed) {
      closed = true;
      releaseResources();
    }
  }

  /** Scores one query against the whole matrix, writing one product per row. */
  protected abstract void multiplyOneQuery(float[] queryValues, float[] dotProducts);

  /**
   * Scores the first {@code numQueries} of {@code queryValues} against the whole matrix in one
   * multiply, writing each query's products into its own entry of {@code dotProducts}.
   *
   * <p>Never called with fewer than two queries, since one query does not repay what combining
   * costs.
   */
  protected abstract void multiplyQueries(
      float[][] queryValues, float[][] dotProducts, int numQueries);

  /** Releases whatever the implementation allocated. Called once. */
  protected abstract void releaseResources();

  protected final DenseMatrix getMatrix() {
    return matrix;
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
        multiplyOneQuery(taken[0].queryValues, taken[0].dotProducts);
      } else {
        for (int i = 0; i < numQueries; ++i) {
          takenQueryValues[i] = taken[i].queryValues;
          takenDotProducts[i] = taken[i].dotProducts;
        }
        multiplyQueries(takenQueryValues, takenDotProducts, numQueries);
      }
      numMultiplies.incrementAndGet();
      numQueriesMultiplied.addAndGet(numQueries);
    } finally {
      // Released even when the multiply failed, so a caller is never left waiting on a multiply
      // that will not happen again.
      for (int i = 0; i < numQueries; ++i) {
        taken[i].scored();
        taken[i] = null;
      }
    }
  }

  /** One query waiting to be scored, and the array its products belong in. */
  private static final class Query {
    private final float[] queryValues;
    private final float[] dotProducts;
    private final CountDownLatch scored = new CountDownLatch(1);

    Query(float[] queryValues, float[] dotProducts) {
      this.queryValues = queryValues;
      this.dotProducts = dotProducts;
    }

    boolean isScored() {
      return scored.getCount() == 0;
    }

    void scored() {
      scored.countDown();
    }

    void awaitBriefly() {
      try {
        scored.await(WAIT_MICROS, TimeUnit.MICROSECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Interrupted while waiting for a dense multiply.", e);
      }
    }
  }
}
