/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.matrix;

import com.uber.ussi.searchablestructure.result.ResultHeaps;
import com.uber.ussi.searchablestructure.result.RowNumAndSimilarity;
import com.uber.ussi.utils.BoundedSizeMaxHeap;

/** Adds the rows a query keeps, from a dot product per row held in this process's memory. */
final class DotProductRows {

  private DotProductRows() {}

  /**
   * Adds every row reaching the minimum similarity. A deleted row reaches nothing, since the
   * similarity derived from its unilateral value is not a number.
   *
   * <p>The minimum rises as the heap fills, so a row that cannot reach what the heap already holds
   * is rejected before a result is made for it. Without that, a query asking for the best rows of
   * the whole matrix makes one result per row and lets the heap discard almost all of them. That
   * was measured to cost more than the multiply that produced the dot products.
   */
  static void addRows(
      float[] dotProducts,
      MatrixRows rows,
      RowSelection selection,
      BoundedSizeMaxHeap<RowNumAndSimilarity> into) {
    float minSimilarity = selection.getMinSimilarity();
    for (int matrixRowIndex = 0; matrixRowIndex < rows.getNumRows(); ++matrixRowIndex) {
      float similarity =
          rows.getSimilarity(
              dotProducts[matrixRowIndex], selection.getQueryUniValue(), matrixRowIndex);
      if (similarity >= minSimilarity) {
        into.add(new RowNumAndSimilarity(rows.getRowNum(matrixRowIndex), similarity));
        minSimilarity = ResultHeaps.tightenedMinSimilarity(into, minSimilarity);
      }
    }
  }
}
