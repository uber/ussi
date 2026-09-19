package com.uber.ussi.searchablestructure.index.matrix;

/**
 * Row data for a test: row numbers in matrix order, unilateral values of zero, and a similarity
 * that is the dot product plus the row's unilateral value, so that a deleted row's value carries
 * into the similarity as it does for a real comparator.
 */
final class TestMatrixRows {

  private TestMatrixRows() {}

  static MatrixRows of(int numRows) {
    long[] rowNums = new long[numRows];
    for (int row = 0; row < numRows; ++row) {
      rowNums[row] = row;
    }
    return new MatrixRows(
        rowNums,
        new double[numRows],
        (dotProduct, queryUniValue, rowUniValue) -> dotProduct + rowUniValue);
  }
}
