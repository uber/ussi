/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.matrix;

/** Chooses the rows a query keeps from a dot product per row held in this process's memory. */
final class HostRowSelection {

  private HostRowSelection() {}

  /** Offers every row that is not deleted, leaving the minimum and the bound to the collector. */
  static void collectRows(
      float[] dotProducts, MatrixRows rows, RowSelection selection, RowCollector collector) {
    for (int matrixRowIndex = 0; matrixRowIndex < rows.getNumRows(); ++matrixRowIndex) {
      long rowNum = rows.getRowNum(matrixRowIndex);
      if (rows.isDeleted(rowNum)) {
        continue;
      }
      collector.offer(
          rowNum,
          rows.getSimilarity(
              dotProducts[matrixRowIndex], selection.getQueryUniValue(), matrixRowIndex));
    }
  }
}
