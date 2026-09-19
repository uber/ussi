/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.matrix;

import com.uber.ussi.searchablestructure.result.ResultHeaps;
import com.uber.ussi.searchablestructure.result.RowNumAndSimilarity;
import com.uber.ussi.utils.BoundedSizeMaxHeap;
import java.util.List;

/** Chooses the rows a query keeps from a dot product per row held in this process's memory. */
final class HostRowSelection {

  private HostRowSelection() {}

  /**
   * The rows whose similarity reaches the minimum, the best {@link RowSelection#getMaxNumRows()}
   * of them, skipping rows deleted since the matrix was built.
   */
  static List<RowNumAndSimilarity> selectRows(
      float[] dotProducts, MatrixRows rows, RowSelection selection) {
    BoundedSizeMaxHeap<RowNumAndSimilarity> kept =
        ResultHeaps.newTopResults(selection.getMaxNumRows());
    for (int matrixRowIndex = 0; matrixRowIndex < rows.getNumRows(); ++matrixRowIndex) {
      long rowNum = rows.getRowNum(matrixRowIndex);
      if (rows.isDeleted(rowNum)) {
        continue;
      }
      float similarity =
          rows.getSimilarity(
              dotProducts[matrixRowIndex], selection.getQueryUniValue(), matrixRowIndex);
      if (similarity >= selection.getMinSimilarity()) {
        kept.add(new RowNumAndSimilarity(rowNum, similarity));
      }
    }
    return kept.toList();
  }
}
