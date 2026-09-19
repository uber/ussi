/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.matrix;

import com.uber.ussi.searchablestructure.result.RowNumAndSimilarity;
import java.util.List;

/**
 * A batched scorer whose multiply leaves a dot product per row in this process's memory, and
 * which therefore chooses the rows it keeps here.
 *
 * <p>What remains for an implementation is the multiply itself. An implementation computing its
 * products elsewhere extends {@link BatchedMatrixDotProductScorer} instead and chooses there.
 */
abstract class HostProductsMatrixDotProductScorer extends BatchedMatrixDotProductScorer<float[]> {

  HostProductsMatrixDotProductScorer(
      DenseMatrix matrix, MatrixRows rows, int maxNumQueriesInAMultiply) {
    super(matrix, rows, maxNumQueriesInAMultiply);
  }

  @Override
  protected final float[] newMultiplyResult() {
    return new float[getMatrix().numRows()];
  }

  @Override
  protected final List<RowNumAndSimilarity> selectRowsFrom(
      float[] result, RowSelection selection) {
    return HostRowSelection.selectRows(result, getRows(), selection);
  }
}
