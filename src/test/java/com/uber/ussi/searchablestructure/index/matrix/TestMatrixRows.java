package com.uber.ussi.searchablestructure.index.matrix;

/** Row data for a test: row numbers in matrix order, nothing deleted, similarity is the product. */
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
        rowNum -> false,
        (dotProduct, queryUniValue, rowUniValue) -> dotProduct);
  }
}
