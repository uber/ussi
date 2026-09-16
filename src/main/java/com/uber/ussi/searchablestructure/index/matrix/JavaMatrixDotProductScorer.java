/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.matrix;

/** Pure Java dense matrix-vector dot-product scorer. */
final class JavaMatrixDotProductScorer implements MatrixDotProductScorer {
  private final DenseMatrix matrix;

  JavaMatrixDotProductScorer(DenseMatrix matrix) {
    this.matrix = matrix;
  }

  @Override
  public void score(float[] queryValues, float[] dotProducts) {
    MatrixDotProductScorers.validateScoreInputs(matrix, queryValues, dotProducts);
    int dimension = matrix.dimension();
    for (int chunk = 0; chunk < matrix.numChunks(); ++chunk) {
      float[] values = matrix.chunk(chunk);
      int firstRow = matrix.firstRowInChunk(chunk);
      int rowsInChunk = matrix.numRowsInChunk(chunk);
      for (int row = 0; row < rowsInChunk; ++row) {
        int offset = row * dimension;
        float dotProduct = 0.0f;
        for (int col = 0; col < dimension; ++col) {
          dotProduct += values[offset + col] * queryValues[col];
        }
        dotProducts[firstRow + row] = dotProduct;
      }
    }
  }
}
