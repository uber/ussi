/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.matrix;

import com.uber.ussi.searchablestructure.ParallelismBudget;
import com.uber.ussi.searchablestructure.SearchThreads;

/**
 * Pure Java dense matrix-vector dot-product scorer.
 *
 * <p>The multiply is multi-threaded over the threads one search may use, each thread taking a range
 * of the matrix's rows. The ranges do not overlap, so the threads write disjoint stretches of the
 * dot products and need nothing to coordinate them beyond waiting for all of them to finish.
 *
 * <p>The ranges run on the threads every other search runs on, so that the threads in flight for
 * one search stay within its budget and the searches in flight together stay within the cores.
 *
 * <p>The OpenBLAS scorer is threaded by OpenBLAS itself, from the same budget. This scorer is what
 * runs where that one is unavailable.
 */
final class JavaMatrixDotProductScorer implements MatrixDotProductScorer {

  /**
   * Multiply-adds a score must do before its rows are worth multi-threading. Below this the
   * hand-off costs more than the multiply it shortens.
   *
   * <p>Carried over from the row-visit minimum a split scan uses, and unmeasured for this multiply.
   */
  private static final long MIN_MULTIPLY_ADDS_TO_SPLIT = 4_096;

  private final DenseMatrix matrix;

  JavaMatrixDotProductScorer(DenseMatrix matrix) {
    this.matrix = matrix;
  }

  @Override
  public void score(float[] queryValues, float[] dotProducts) {
    MatrixDotProductScorers.validateScoreInputs(matrix, queryValues, dotProducts);
    int numRows = matrix.numRows();
    int numRanges = numRangesFor(numRows, matrix.dimension(), ParallelismBudget.shared().budget());
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
   * worth multi-threading, and one range before that. No range is without a row in it.
   */
  static int numRangesFor(int numRows, int dimension, int parallelism) {
    if ((long) numRows * dimension < MIN_MULTIPLY_ADDS_TO_SPLIT) {
      return 1;
    }
    return Math.max(1, Math.min(parallelism, numRows));
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
