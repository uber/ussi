/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.matrix;

import com.uber.ussi.searchablestructure.result.ResultHeaps;
import com.uber.ussi.searchablestructure.result.RowNumAndSimilarity;
import com.uber.ussi.searchablestructure.utils.parallel.ParallelismBudget;
import com.uber.ussi.searchablestructure.utils.parallel.SearchThreads;
import com.uber.ussi.utils.BoundedSizeMaxHeap;
import java.util.List;

/**
 * Pure Java dense matrix-vector dot-product scorer.
 *
 * <p>The multiply runs in parallel over the threads one search may use, each thread taking a range
 * of the matrix's rows. The ranges do not overlap, so the threads write disjoint stretches of the
 * dot products and need nothing to coordinate them beyond waiting for all of them to finish.
 *
 * <p>The ranges run on the threads every other search runs on, so that the threads of one search
 * stay within its budget and the concurrent searches together stay within the cores.
 *
 * <p>The OpenBLAS scorer is threaded by OpenBLAS itself, from the same budget. This scorer is what
 * runs where that one is unavailable.
 */
final class JavaMatrixDotProductScorer implements MatrixDotProductScorer {

  /**
   * Multiply-adds a score must do before its rows are worth dividing between threads. Below this,
   * submitting the ranges costs more than the multiply it shortens.
   *
   * <p>Carried over from the row-visit minimum a divided scan uses, and unmeasured for this
   * multiply.
   */
  private static final long MIN_NUM_MULTIPLY_ADDS_TO_DIVIDE = 4_096;

  private final DenseMatrix matrix;
  private final MatrixRows rows;

  JavaMatrixDotProductScorer(DenseMatrix matrix, MatrixRows rows) {
    this.matrix = matrix;
    this.rows = rows;
  }

  @Override
  public List<RowNumAndSimilarity> selectRows(float[] queryValues, RowSelection selection) {
    float[] dotProducts = new float[matrix.numRows()];
    score(queryValues, dotProducts);
    BoundedSizeMaxHeap<RowNumAndSimilarity> kept =
        ResultHeaps.newTopResults(selection.getMaxResults());
    DotProductRows.addRows(dotProducts, rows, selection, kept);
    return kept.toList();
  }

  /**
   * Batching queries was measured and not adopted here. The gain elsewhere comes from sharing a
   * cost a library pays once per call whatever the query count, and this multiply has no such
   * cost: it moves a few gigabytes a second, far below what one core can read, so there is no
   * traffic for queries to share.
   */
  void score(float[] queryValues, float[] dotProducts) {
    MatrixDotProductScorers.validateQueryLength(matrix, queryValues);
    int numRows = matrix.numRows();
    int numRanges =
        getNumRanges(
            numRows, matrix.dimension(), ParallelismBudget.shared().getNumThreadsPerSearch());
    int rowsPerRange = (numRows + numRanges - 1) / numRanges;
    SearchThreads.runInParallel(
        numRanges,
        range -> {
          int firstRow = range * rowsPerRange;
          int afterLastRow = Math.min(numRows, firstRow + rowsPerRange);
          if (firstRow < afterLastRow) {
            scoreRows(firstRow, afterLastRow, queryValues, dotProducts);
          }
        });
  }

  /**
   * Ranges to divide {@code numRows} into: one per thread this search may use once the multiply is
   * worth dividing between threads, and one range before that. No range is without a row in it.
   */
  static int getNumRanges(int numRows, int dimension, int numThreads) {
    if ((long) numRows * dimension < MIN_NUM_MULTIPLY_ADDS_TO_DIVIDE) {
      return 1;
    }
    return Math.max(1, Math.min(numThreads, numRows));
  }

  /** Scores the rows of one range, which lie in one chunk of the matrix or across several. */
  private void scoreRows(int firstRow, int afterLastRow, float[] queryValues, float[] dotProducts) {
    int dimension = matrix.dimension();
    for (int chunk = 0; chunk < matrix.numChunks(); ++chunk) {
      int firstRowOfChunk = matrix.firstRowInChunk(chunk);
      int firstRowToScore = Math.max(firstRow, firstRowOfChunk);
      int afterLastRowToScore =
          Math.min(afterLastRow, firstRowOfChunk + matrix.numRowsInChunk(chunk));
      if (firstRowToScore >= afterLastRowToScore) {
        continue;
      }
      float[] values = matrix.chunk(chunk);
      for (int row = firstRowToScore; row < afterLastRowToScore; ++row) {
        int offset = (row - firstRowOfChunk) * dimension;
        float dotProduct = 0.0f;
        for (int col = 0; col < dimension; ++col) {
          dotProduct += values[offset + col] * queryValues[col];
        }
        dotProducts[row] = dotProduct;
      }
    }
  }
}
