package com.uber.ussi.searchablestructure.index.matrix;

import java.util.Arrays;

/** Builds a {@link DenseMatrix} from the flat row-major literals the scorer tests use. */
final class TestDenseMatrices {

  private TestDenseMatrices() {}

  static DenseMatrix of(float[] rowMajorValues, int numRows, int dimension) {
    return of(rowMajorValues, numRows, dimension, DenseMatrix.DEFAULT_MAX_CHUNK_VALUES);
  }

  /** {@code maxChunkValues} small enough forces several chunks, which is otherwise unreachable. */
  static DenseMatrix of(
      float[] rowMajorValues, int numRows, int dimension, int maxChunkValues) {
    DenseMatrix matrix = DenseMatrix.allocate(numRows, dimension, maxChunkValues);
    for (int row = 0; row < numRows; ++row) {
      matrix.setRow(
          row, Arrays.copyOfRange(rowMajorValues, row * dimension, (row + 1) * dimension));
    }
    return matrix;
  }
}
