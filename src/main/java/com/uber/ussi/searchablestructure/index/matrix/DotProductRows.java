/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.matrix;

import com.uber.ussi.searchablestructure.result.RowNumAndSimilarity;
import com.uber.ussi.utils.BoundedSizeMaxHeap;

/** Adds the rows a query keeps, from a dot product per row held in this process's memory. */
final class DotProductRows {

  private DotProductRows() {}

  /** Adds every row that is not deleted and reaches the minimum similarity. */
  static void addRows(
      float[] dotProducts,
      MatrixRows rows,
      RowSelection selection,
      BoundedSizeMaxHeap<RowNumAndSimilarity> into) {
    for (int matrixRowIndex = 0; matrixRowIndex < rows.getNumRows(); ++matrixRowIndex) {
      long rowNum = rows.getRowNum(matrixRowIndex);
      if (rows.isDeleted(rowNum)) {
        continue;
      }
      float similarity =
          rows.getSimilarity(
              dotProducts[matrixRowIndex], selection.getQueryUniValue(), matrixRowIndex);
      if (similarity >= selection.getMinSimilarity()) {
        into.add(new RowNumAndSimilarity(rowNum, similarity));
      }
    }
  }
}
