package com.uber.ussi.searchablestructure.index.matrix;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class MatrixDotProductScorerTest {
  private static final int RANDOM_PROFILE_PHOTO_NUM_ROWS = 1000;
  private static final int RANDOM_PROFILE_PHOTO_DIMENSION = 4096;
  private static final long RANDOM_PROFILE_PHOTO_SEED = 20260616L;
  private static final float DELTA = 1e-3f;

  @Test
  void openBlasScorerMatchesJavaScorer() {
    assumeTrue(OpenBlasMatrixDotProductScorer.isAvailable());
    float[] matrix =
        new float[] {
          1.0f, 0.0f, 2.0f,
          0.5f, 0.5f, 0.5f,
          0.0f, 3.0f, 1.0f
        };
    float[] query = new float[] {0.25f, 0.5f, 0.75f};
    float[] javaDots = new float[3];
    float[] openBlasDots = new float[3];
    JavaMatrixDotProductScorer javaScorer =
        new JavaMatrixDotProductScorer(matrix, /* numRows */ 3, /* dimension */ 3);
    try (OpenBlasMatrixDotProductScorer openBlasScorer =
        new OpenBlasMatrixDotProductScorer(
            matrix,
            /* numRows */ 3,
            /* dimension */ 3,
            OpenBlasMatrixDotProductScorer::isAvailable)) {
      javaScorer.score(query, javaDots);
      openBlasScorer.score(query, openBlasDots);
    }

    for (int i = 0; i < javaDots.length; ++i) {
      assertEquals(javaDots[i], openBlasDots[i], DELTA);
    }
  }

  /**
   * A nine-value matrix is too small for OpenBLAS to spread over the threads {@code score} gives it
   * or to reach its vectorized kernels, so the case above agrees with the Java scorer the easy way.
   * Profile-photo dimensions reach both, where the kernels accumulate in an order of their own
   * choosing and agreement holds only to {@link #DELTA}.
   */
  @Test
  void openBlasScorerMatchesJavaScorerOverProfilePhotoSizedEmbeddings() {
    assumeTrue(OpenBlasMatrixDotProductScorer.isAvailable());
    EmbeddingData embeddingData =
        generatedProfilePhotoEmbeddingsData(
            RANDOM_PROFILE_PHOTO_NUM_ROWS,
            RANDOM_PROFILE_PHOTO_DIMENSION,
            RANDOM_PROFILE_PHOTO_SEED);
    int numRows = embeddingData.numRows;
    int dimension = embeddingData.dimension;
    float[] matrix = embeddingData.rowMajorValues;
    float[] query = embeddingData.getFirstRowAsQuery();
    float[] javaDots = new float[numRows];
    float[] openBlasDots = new float[numRows];
    JavaMatrixDotProductScorer javaScorer =
        new JavaMatrixDotProductScorer(matrix, numRows, dimension);

    try (OpenBlasMatrixDotProductScorer openBlasScorer =
        new OpenBlasMatrixDotProductScorer(
            matrix, numRows, dimension, OpenBlasMatrixDotProductScorer::isAvailable)) {
      javaScorer.score(query, javaDots);
      openBlasScorer.score(query, openBlasDots);
    }

    for (int i = 0; i < numRows; ++i) {
      assertEquals(javaDots[i], openBlasDots[i], DELTA);
    }
  }

  private static EmbeddingData generatedProfilePhotoEmbeddingsData(
      int numRows, int dimension, long seed) {
    java.util.Random random = new java.util.Random(seed);
    float[] rowMajorValues = new float[numRows * dimension];
    for (int i = 0; i < rowMajorValues.length; ++i) {
      rowMajorValues[i] = random.nextFloat() - 0.5f;
    }
    normalizeRows(rowMajorValues, numRows, dimension);
    return new EmbeddingData(rowMajorValues, numRows, dimension);
  }

  /**
   * Embeddings of this kind arrive unit-normalized, and the scale matters to what the comparison
   * above can catch: a row against itself then scores 1, where {@link #DELTA} is a real constraint.
   * Values small enough to keep every dot product well under the tolerance would agree whatever the
   * kernels computed.
   */
  private static void normalizeRows(float[] rowMajorValues, int numRows, int dimension) {
    for (int row = 0; row < numRows; ++row) {
      int offset = row * dimension;
      double squaredNorm = 0.0d;
      for (int i = 0; i < dimension; ++i) {
        squaredNorm += (double) rowMajorValues[offset + i] * rowMajorValues[offset + i];
      }
      float norm = (float) Math.sqrt(squaredNorm);
      for (int i = 0; i < dimension; ++i) {
        rowMajorValues[offset + i] /= norm;
      }
    }
  }

  private static final class EmbeddingData {
    private final float[] rowMajorValues;
    private final int numRows;
    private final int dimension;

    private EmbeddingData(float[] rowMajorValues, int numRows, int dimension) {
      this.rowMajorValues = rowMajorValues;
      this.numRows = numRows;
      this.dimension = dimension;
    }

    private float[] getFirstRowAsQuery() {
      return Arrays.copyOfRange(rowMajorValues, 0, dimension);
    }
  }
}
