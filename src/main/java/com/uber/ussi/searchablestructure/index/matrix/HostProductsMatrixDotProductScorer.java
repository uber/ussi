/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.matrix;

import com.uber.ussi.searchablestructure.result.RowNumAndSimilarity;
import com.uber.ussi.utils.BoundedSizeMaxHeap;
import java.util.concurrent.ArrayBlockingQueue;


/**
 * A batched scorer whose multiply leaves a dot product per row in this process's memory, and
 * which therefore chooses the rows it keeps here.
 *
 * <p>What remains for an implementation is the multiply itself. An implementation computing its
 * products elsewhere extends {@link BatchedMatrixDotProductScorer} instead and chooses there.
 */
abstract class HostProductsMatrixDotProductScorer extends BatchedMatrixDotProductScorer<float[]> {

  /**
   * A product per row is as long as the matrix has rows, so one is reused rather than allocated
   * per query. Bounded by the queries that may be scored at once, since no more are ever held.
   */
  private final ArrayBlockingQueue<float[]> spareProducts;

  HostProductsMatrixDotProductScorer(
      DenseMatrix matrix, MatrixRows rows, int maxNumQueriesInABatch) {
    super(matrix, rows, maxNumQueriesInABatch);
    this.spareProducts = new ArrayBlockingQueue<>(maxNumQueriesInABatch);
  }

  @Override
  protected final float[] newMultiplyResult() {
    float[] reused = spareProducts.poll();
    return reused != null ? reused : new float[getMatrix().numRows()];
  }

  @Override
  protected final void recycleMultiplyResult(float[] result) {
    spareProducts.offer(result);
  }

  @Override
  protected final float[][] newMultiplyResults(int numResults) {
    return new float[numResults][];
  }

  @Override
  protected final void addRows(
      float[] result, RowSelection selection, BoundedSizeMaxHeap<RowNumAndSimilarity> rows) {
    DotProductRows.addRows(result, getRows(), selection, rows);
  }
}
