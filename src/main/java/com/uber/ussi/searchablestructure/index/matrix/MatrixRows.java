/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.matrix;

import com.uber.ussi.comparator.DotProductScored;

/**
 * What selecting the rows a query keeps needs to know, beyond the dot products themselves.
 *
 * <p>A dot product is not a similarity: the comparator derives one from the dot product and the
 * unilateral value of each side. Selecting therefore needs the row numbers, their unilateral
 * values and the comparator's arithmetic, all of which belong to the index rather than to
 * whatever performs the multiply. A deleted row is excluded by the same arithmetic, since its
 * unilateral value is one no similarity can be derived from.
 *
 * <p>These are given to the scorer rather than applied by its caller, so that an implementation
 * may select its rows as soon as it has scored them and return only those, instead of returning
 * one value per row.
 */
final class MatrixRows {

  private final long[] rowNums;
  private final double[] rowUniValues;
  private final DotProductScored dotProductScored;

  MatrixRows(long[] rowNums, double[] rowUniValues, DotProductScored dotProductScored) {
    this.rowNums = rowNums;
    this.rowUniValues = rowUniValues;
    this.dotProductScored = dotProductScored;
  }

  int getNumRows() {
    return rowNums.length;
  }

  /** The row number the given row of the matrix holds. */
  long getRowNum(int matrixRowIndex) {
    return rowNums[matrixRowIndex];
  }

  /** Every row's unilateral value, in matrix row order. */
  double[] getRowUniValues() {
    return rowUniValues;
  }

  /** The similarity a dot product implies for the given row. */
  /** The measure the namespace configured, which a scorer may need to decide whether it serves. */
  DotProductScored getDotProductScored() {
    return dotProductScored;
  }

  float getSimilarity(double dotProduct, double queryUniValue, int matrixRowIndex) {
    return (float)
        dotProductScored.similarityFromDotProduct(
            dotProduct, queryUniValue, rowUniValues[matrixRowIndex]);
  }
}
