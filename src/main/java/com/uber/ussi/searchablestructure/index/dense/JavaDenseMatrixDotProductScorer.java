/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.dense;

/** Pure Java dense matrix-vector dot-product scorer. */
final class JavaDenseMatrixDotProductScorer implements DenseMatrixDotProductScorer {
  private final float[] rowMajorValues;
  private final int numRows;
  private final int dimension;

  JavaDenseMatrixDotProductScorer(float[] rowMajorValues, int numRows, int dimension) {
    this.rowMajorValues = rowMajorValues;
    this.numRows = numRows;
    this.dimension = dimension;
  }

  @Override
  public void score(float[] queryValues, float[] dotProducts) {
    DenseMatrixDotProductScorers.validateScoreInputs(
        rowMajorValues, numRows, dimension, queryValues, dotProducts);
    for (int row = 0; row < numRows; ++row) {
      int offset = row * dimension;
      float dotProduct = 0.0f;
      for (int col = 0; col < dimension; ++col) {
        dotProduct += rowMajorValues[offset + col] * queryValues[col];
      }
      dotProducts[row] = dotProduct;
    }
  }
}
