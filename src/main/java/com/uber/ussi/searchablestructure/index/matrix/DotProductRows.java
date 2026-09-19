/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.matrix;

import com.uber.ussi.searchablestructure.result.RowNumAndSimilarity;
import com.uber.ussi.utils.BoundedSizeMaxHeap;

/** Adds the rows a query keeps, from a dot product per row held in this process's memory. */
final class DotProductRows {

  private DotProductRows() {}

  /**
   * Adds every row reaching the minimum similarity. A deleted row reaches nothing, since the
   * similarity derived from its unilateral value is not a number.
   */
  static void addRows(
      float[] dotProducts,
      MatrixRows rows,
      RowSelection selection,
      BoundedSizeMaxHeap<RowNumAndSimilarity> into) {
    for (int matrixRowIndex = 0; matrixRowIndex < rows.getNumRows(); ++matrixRowIndex) {
      float similarity =
          rows.getSimilarity(
              dotProducts[matrixRowIndex], selection.getQueryUniValue(), matrixRowIndex);
      if (similarity >= selection.getMinSimilarity()) {
        into.add(new RowNumAndSimilarity(rows.getRowNum(matrixRowIndex), similarity));
      }
    }
  }
}
