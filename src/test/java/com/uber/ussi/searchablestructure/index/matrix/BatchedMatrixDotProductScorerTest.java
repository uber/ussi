package com.uber.ussi.searchablestructure.index.matrix;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.uber.ussi.searchablestructure.result.RowNumAndSimilarity;
import com.uber.ussi.utils.BoundedSizeMaxHeap;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

/** Batching waiting queries into one multiply, independent of what performs the multiply. */
class BatchedMatrixDotProductScorerTest {
  private static final float DELTA = 1e-6f;
  private static final RowSelection SELECTION =
      new RowSelection(0, Float.NEGATIVE_INFINITY, 10);

  @Test
  void rejectsABoundBelowOneQuery() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new RecordingScorer(TestDenseMatrices.of(new float[] {1f}, 1, 1), 0));
  }

  /** An uncontended query is multiplied on its own, since one query does not repay batching. */
  @Test
  void aLoneQueryTakesTheSingleQueryPath() {
    RecordingScorer scorer = new RecordingScorer(TestDenseMatrices.of(new float[] {2f}, 1, 1), 8);

    List<RowNumAndSimilarity> kept = scorer.selectRows(new float[] {3f}, SELECTION);

    assertEquals(List.of(1), List.copyOf(scorer.numQueriesPerMultiply));
    assertEquals(1, kept.size(), "the single-query path must still score the query");
    assertEquals(6f, kept.get(0).getSimilarity(), DELTA);
    assertEquals(1, scorer.getNumMultiplies());
    assertEquals(1, scorer.getNumQueriesMultiplied());
  }

  /**
   * Queries waiting while a multiply runs are taken together by whichever caller performs the
   * next one, so the batch size follows the offered load without any caller waiting for an
   * arrival.
   */
  @Test
  void queriesWaitingTogetherAreMultipliedTogether() throws Exception {
    RecordingScorer scorer = new RecordingScorer(TestDenseMatrices.of(new float[] {2f}, 1, 1), 8);
    int numThreads = 4;
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch finished = new CountDownLatch(numThreads);
    for (int thread = 0; thread < numThreads; ++thread) {
      Thread worker =
          new Thread(
              () -> {
                try {
                  start.await();
                  for (int round = 0; round < 100; ++round) {
                    List<RowNumAndSimilarity> kept =
                        scorer.selectRows(new float[] {3f}, SELECTION);
                    assertEquals(6f, kept.get(0).getSimilarity(), DELTA);
                  }
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                } finally {
                  finished.countDown();
                }
              });
      worker.setDaemon(true);
      worker.start();
    }
    start.countDown();
    finished.await();

    assertEquals(numThreads * 100, scorer.getNumQueriesMultiplied(), "every query must be scored");
    assertTrue(
        scorer.getNumMultiplies() <= scorer.getNumQueriesMultiplied(),
        "a multiply may carry several queries but never fewer than one");
    for (int numQueries : scorer.numQueriesPerMultiply) {
      assertTrue(numQueries >= 1 && numQueries <= 8, "batched " + numQueries + " queries");
    }
  }

  @Test
  void rejectsScoringAfterClose() {
    RecordingScorer scorer = new RecordingScorer(TestDenseMatrices.of(new float[] {1f}, 1, 1), 8);

    scorer.close();
    scorer.close();

    assertEquals(1, scorer.numReleases, "resources must be released once");
    assertThrows(
        IllegalStateException.class, () -> scorer.selectRows(new float[] {1f}, SELECTION));
  }

  @Test
  void rejectsAQueryOfTheWrongLength() {
    RecordingScorer scorer =
        new RecordingScorer(TestDenseMatrices.of(new float[] {1f, 2f}, 1, 2), 8);

    assertThrows(
        IllegalArgumentException.class, () -> scorer.selectRows(new float[] {1f}, SELECTION));
  }

  /** A scorer whose multiply is plain Java, recording how many queries each one carried. */
  private static final class RecordingScorer extends BatchedMatrixDotProductScorer<float[]> {
    private final ConcurrentLinkedQueue<Integer> numQueriesPerMultiply =
        new ConcurrentLinkedQueue<>();
    private int numReleases;

    RecordingScorer(DenseMatrix matrix, int maxNumQueriesInABatch) {
      super(matrix, TestMatrixRows.of(matrix.numRows()), maxNumQueriesInABatch);
    }

    @Override
    protected float[] newMultiplyResult() {
      return new float[getMatrix().numRows()];
    }

    @Override
    protected float[][] newMultiplyResults(int numResults) {
      return new float[numResults][];
    }

    @Override
    protected void addRows(
        float[] result, RowSelection selection, BoundedSizeMaxHeap<RowNumAndSimilarity> rows) {
      DotProductRows.addRows(result, getRows(), selection, rows);
    }

    @Override
    protected void multiplyOneQuery(
        float[] queryValues, RowSelection selection, float[] dotProducts) {
      numQueriesPerMultiply.add(1);
      multiply(queryValues, dotProducts);
    }

    @Override
    protected void multiplyQueries(
        float[][] queryValues, RowSelection[] selections, float[][] dotProducts, int numQueries) {
      numQueriesPerMultiply.add(numQueries);
      for (int query = 0; query < numQueries; ++query) {
        multiply(queryValues[query], dotProducts[query]);
      }
    }

    @Override
    protected void releaseResources() {
      numReleases++;
    }

    private void multiply(float[] queryValues, float[] dotProducts) {
      DenseMatrix matrix = getMatrix();
      for (int row = 0; row < matrix.numRows(); ++row) {
        float product = 0f;
        for (int column = 0; column < matrix.dimension(); ++column) {
          product +=
              matrix.chunk(0)[row * matrix.dimension() + column] * queryValues[column];
        }
        dotProducts[row] = product;
      }
    }
  }
}
