/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.matrix;

import com.uber.ussi.comparator.DotProductScored;

/**
 * What choosing the rows a query keeps needs to know, beyond the dot products themselves.
 *
 * <p>A dot product is not a similarity: the comparator derives one from the dot product and the
 * unilateral value of each side. Nor is every row eligible, since a deleted row keeps its place
 * in the matrix until the matrix is rebuilt. Choosing therefore needs the row numbers, their
 * unilateral values, the deletions and the comparator's arithmetic, all of which belong to the
 * index rather than to whatever performs the multiply.
 *
 * <p>These are given to the scorer so that choosing can happen wherever the products are, rather
 * than only on the host. An implementation that computes its products somewhere the host cannot
 * read holds the same values in the same place and chooses there, returning the few rows it kept
 * instead of a product for every row.
 */
final class MatrixRows {

  private final long[] rowNums;
  private final double[] rowUniValues;
  private final DeletedRows deletedRows;
  private final DotProductScored dotProductScored;

  MatrixRows(
      long[] rowNums,
      double[] rowUniValues,
      DeletedRows deletedRows,
      DotProductScored dotProductScored) {
    this.rowNums = rowNums;
    this.rowUniValues = rowUniValues;
    this.deletedRows = deletedRows;
    this.dotProductScored = dotProductScored;
  }

  int getNumRows() {
    return rowNums.length;
  }

  /** The row number the given row of the matrix holds. */
  long getRowNum(int matrixRowIndex) {
    return rowNums[matrixRowIndex];
  }

  /** Every row's unilateral value, in matrix row order, for an implementation that keeps a copy. */
  double[] getRowUniValues() {
    return rowUniValues;
  }

  boolean isDeleted(long rowNum) {
    return deletedRows.isDeleted(rowNum);
  }

  /** The similarity a dot product implies for the given row. */
  float getSimilarity(double dotProduct, double queryUniValue, int matrixRowIndex) {
    return (float)
        dotProductScored.similarityFromDotProduct(
            dotProduct, queryUniValue, rowUniValues[matrixRowIndex]);
  }

  /** Whether a row has been deleted since the matrix was built. */
  interface DeletedRows {
    boolean isDeleted(long rowNum);
  }
}
