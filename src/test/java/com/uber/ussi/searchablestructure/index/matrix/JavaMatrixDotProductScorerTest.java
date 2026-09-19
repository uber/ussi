package com.uber.ussi.searchablestructure.index.matrix;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Random;
import org.junit.jupiter.api.Test;

class JavaMatrixDotProductScorerTest {

  @Test
  void rowRangesScoreWhatTheWholeMatrixScores() {
    // Chunks are sized so that some shapes hold their rows in one chunk and others across several,
    // since a range of rows submitted to a thread need not lie in the chunk the next range does.
    int[][] rowsAndDimensions = {
      {1, 1}, {1, 64}, {7, 3}, {64, 16}, {100, 128}, {257, 33}, {1_000, 64},
    };
    for (int[] shape : rowsAndDimensions) {
      int numRows = shape[0];
      int dimension = shape[1];
      for (int maxChunkValues : new int[] {dimension, dimension * 8, 1 << 20}) {
        String message =
            "numRows=" + numRows + " dimension=" + dimension + " chunkValues=" + maxChunkValues;
        DenseMatrix matrix = randomMatrix(numRows, dimension, maxChunkValues);
        float[] queryValues = randomValues(dimension, 7);

        float[] dotProducts = new float[numRows];
        new JavaMatrixDotProductScorer(matrix, TestMatrixRows.of(matrix.numRows())).score(queryValues, dotProducts);

        assertArrayEquals(wholeMatrixDotProducts(matrix, queryValues), dotProducts, 0.0f, message);
      }
    }
  }

  @Test
  void takesOneRangePerThreadOnlyOnceTheMultiplyIsWorthIt() {
    assertEquals(1, JavaMatrixDotProductScorer.getNumRanges(1, 1, 8), "one multiply-add");
    assertEquals(1, JavaMatrixDotProductScorer.getNumRanges(64, 63, 8), "just under the minimum");
    assertEquals(8, JavaMatrixDotProductScorer.getNumRanges(64, 64, 8), "at the minimum");
    assertEquals(
        4, JavaMatrixDotProductScorer.getNumRanges(4, 4_096, 8), "no range is without a row in it");
    assertEquals(
        1, JavaMatrixDotProductScorer.getNumRanges(10_000, 1_000, 1), "a search with one thread");
  }

  /** The dot products one thread over the whole matrix produces, computed row by row. */
  private static float[] wholeMatrixDotProducts(DenseMatrix matrix, float[] queryValues) {
    float[] dotProducts = new float[matrix.numRows()];
    int dimension = matrix.dimension();
    for (int chunk = 0; chunk < matrix.numChunks(); ++chunk) {
      float[] values = matrix.chunk(chunk);
      int firstRowOfChunk = matrix.firstRowInChunk(chunk);
      for (int row = 0; row < matrix.numRowsInChunk(chunk); ++row) {
        float dotProduct = 0.0f;
        for (int col = 0; col < dimension; ++col) {
          dotProduct += values[row * dimension + col] * queryValues[col];
        }
        dotProducts[firstRowOfChunk + row] = dotProduct;
      }
    }
    return dotProducts;
  }

  private static DenseMatrix randomMatrix(int numRows, int dimension, int maxChunkValues) {
    DenseMatrix matrix = DenseMatrix.allocate(numRows, dimension, maxChunkValues);
    for (int row = 0; row < numRows; ++row) {
      matrix.setRow(row, randomValues(dimension, row));
    }
    return matrix;
  }

  private static float[] randomValues(int dimension, int seed) {
    Random random = new Random(seed);
    float[] values = new float[dimension];
    for (int col = 0; col < dimension; ++col) {
      values[col] = random.nextFloat();
    }
    return values;
  }
}
