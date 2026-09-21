/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.matrix;

import com.uber.ussi.searchablestructure.result.RowNumAndSimilarity;
import com.uber.ussi.searchablestructure.result.ResultHeaps;
import com.uber.ussi.utils.BoundedSizeMaxHeap;
import com.uber.ussi.error.SearchCancelledException;
import java.util.ArrayDeque;
import java.util.List;
import javax.annotation.Nullable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Scores several queries against one matrix in a single multiply, when several are waiting.
 *
 * <p>A multiply against the whole matrix costs about what reading the matrix costs. Queries that
 * share one multiply therefore share that cost, and the underlying implementation reaches an
 * arithmetic intensity a single query cannot give it. The gain grows with the number of queries
 * batched, which is why the alternative of dividing the machine between concurrent queries loses as
 * concurrency rises: it makes each multiply narrower exactly when there is most to share.
 *
 * <p>No query waits for a query that has not arrived. A caller enqueues its own and then contends
 * to perform the multiply. Whichever caller acquires it multiplies everything enqueued at that
 * instant, and the others wait only for the multiply already running. The batch size is therefore a
 * measurement of the offered load rather than a configured window. It is one when the machine is
 * idle, and no arrival policy or timer is needed to obtain it.
 *
 * <p>Callers are serialized, so one multiply at a time may use every thread the implementation is
 * configured for, rather than the threads being divided among concurrent multiplies.
 *
 * <p>{@code S} is what one multiply produces for one query, which the implementation reads back in
 * {@link #addRows addRows()} and nothing else interprets. An implementation that discards rows as
 * it scores them therefore returns only what it kept, rather than one value per row.
 *
 * <p>Selecting runs on the thread that asked for the query, after the multiply that scored it has
 * finished and released the next one. Several queries therefore select at the same time and overlap
 * the following multiply, which a serialized multiply cannot do.
 */
abstract class BatchedMatrixDotProductScorer<S> implements MatrixDotProductScorer {

  /**
   * How long a waiting caller sleeps before contending again. Short enough that a caller whose
   * query was not taken resumes promptly, long enough that waiting callers do not spin.
   */
  private static final long WAIT_MICROS = 50;

  private final DenseMatrix matrix;
  private final MatrixRows rows;
  private final int maxNumQueriesInABatch;
  private final ReentrantLock multiplying = new ReentrantLock();
  private final ArrayDeque<Query<S>> waiting = new ArrayDeque<>();
  // Read and written only by the caller holding multiplying.
  private final Query<S>[] batched;
  private final float[][] batchedQueryValues;
  private final RowSelection[] batchedSelections;
  @Nullable private S[] batchedResults;
  private final AtomicLong numMultiplies = new AtomicLong();
  private final AtomicLong numQueriesMultiplied = new AtomicLong();
  private volatile boolean closed;

  @SuppressWarnings("unchecked")
  BatchedMatrixDotProductScorer(DenseMatrix matrix, MatrixRows rows, int maxNumQueriesInABatch) {
    if (maxNumQueriesInABatch < 1) {
      throw new IllegalArgumentException("maxNumQueriesInABatch must be >= 1.");
    }
    this.matrix = matrix;
    this.rows = rows;
    this.maxNumQueriesInABatch = maxNumQueriesInABatch;
    this.batched = new Query[maxNumQueriesInABatch];
    this.batchedQueryValues = new float[maxNumQueriesInABatch][];
    this.batchedSelections = new RowSelection[maxNumQueriesInABatch];
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
    BoundedSizeMaxHeap<RowNumAndSimilarity> rows =
        ResultHeaps.newTopResults(selection.getMaxResults());
    try {
      addRows(query.result, selection, rows);
    } finally {
      recycleMultiplyResult(query.result);
    }
    return rows.toList();
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
   * <p>Never called with fewer than two queries, since one query does not repay what batching
   * costs. The selections are given so that an implementation able to select its rows during the
   * multiply has what selecting needs.
   */
  protected abstract void multiplyQueries(
      float[][] queryValues, RowSelection[] selections, S[] into, int numQueries);

  /**
   * Adds to {@code rows} what this query keeps, from what its multiply produced.
   *
   * <p>The bound and the ordering belong to the heap. An implementation that has already selected
   * its rows adds what survived, one holding a similarity for every row adds every row reaching the
   * minimum, and both reach the same answer. The heap holds its rows in a plain list until they
   * exceed the bound, so adding no more than the bound never builds a queue.
   */
  protected abstract void addRows(
      S result, RowSelection selection, BoundedSizeMaxHeap<RowNumAndSimilarity> rows);

  /** Somewhere to return a result whose rows have been added, so the next query may reuse it. */
  protected void recycleMultiplyResult(S result) {}

  /** An array to hold one multiply's results, which only the implementation can create. */
  protected abstract S[] newMultiplyResults(int numResults);

  /** Releases whatever the implementation allocated. Called once. */
  protected abstract void releaseResources();

  protected final DenseMatrix getMatrix() {
    return matrix;
  }

  @Override
  public boolean hasOwnMatrixCopy() {
    return true;
  }

  protected final MatrixRows getRows() {
    return rows;
  }

  /** The most queries one multiply may carry, which sizes the working buffers. */
  protected final int getMaxNumQueriesInABatch() {
    return maxNumQueriesInABatch;
  }

  /** Multiplies so far, and the queries they carried, which report how the load batched. */
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
      while (!waiting.isEmpty() && numQueries < maxNumQueriesInABatch) {
        batched[numQueries++] = waiting.pollFirst();
      }
    }
    if (numQueries == 0) {
      return;
    }
    try {
      if (numQueries == 1) {
        multiplyOneQuery(batched[0].queryValues, batched[0].selection, batched[0].result);
      } else {
        if (batchedResults == null) {
          batchedResults = newMultiplyResults(maxNumQueriesInABatch);
        }
        for (int i = 0; i < numQueries; ++i) {
          batchedQueryValues[i] = batched[i].queryValues;
          batchedSelections[i] = batched[i].selection;
          batchedResults[i] = batched[i].result;
        }
        multiplyQueries(batchedQueryValues, batchedSelections, batchedResults, numQueries);
      }
      numMultiplies.incrementAndGet();
      numQueriesMultiplied.addAndGet(numQueries);
    } finally {
      // Released even when the multiply failed, so a caller is never left waiting on a multiply
      // that will not happen again.
      for (int i = 0; i < numQueries; ++i) {
        batched[i].multiplied();
        batched[i] = null;
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
        throw new SearchCancelledException("Cancelled while waiting for a dense multiply.", e);
      }
    }
  }
}
