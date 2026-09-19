package com.uber.ussi.searchablestructure.index.matrix;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class MatrixDotProductScorerTest {
  private static final int RANDOM_PROFILE_PHOTO_NUM_ROWS = 1000;
  private static final int RANDOM_PROFILE_PHOTO_DIMENSION = 4096;
  private static final long RANDOM_PROFILE_PHOTO_SEED = 20260616L;
  private static final RowSelection SELECTION =
      new RowSelection(0, Float.NEGATIVE_INFINITY, 10);
  private static final float DELTA = 1e-3f;

  @Test
  void openBlasScorerMatchesJavaScorer() {
    assumeTrue(OpenBlas.isAvailable());
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
        javaScorerOver(TestDenseMatrices.of(matrix, 3, 3));
    try (NativeMatrixDotProductScorer<org.bytedeco.javacpp.FloatPointer> openBlasScorer =
        new NativeMatrixDotProductScorer<>(
            TestDenseMatrices.of(matrix, 3, 3),
            TestMatrixRows.of(3),
            OpenBlas.shared())) {
      javaScorer.score(query, javaDots);
      openBlasScorer.multiplyOneQuery(query, SELECTION, openBlasDots);
    }

    for (int i = 0; i < javaDots.length; ++i) {
      assertEquals(javaDots[i], openBlasDots[i], DELTA);
    }
  }

  /**
   * Both scorers must place a chunk's results at the rows that chunk holds, not at the start of the
   * output. A single-chunk matrix cannot catch that, since there the two are the same.
   */
  @Test
  void bothScorersPlaceChunkResultsAtTheRightRows() {
    int numRows = 5;
    int dimension = 3;
    float[] values =
        new float[] {
          1.0f, 0.0f, 2.0f,
          0.5f, 0.5f, 0.5f,
          0.0f, 3.0f, 1.0f,
          2.0f, 1.0f, 0.0f,
          1.0f, 1.0f, 1.0f
        };
    float[] query = new float[] {0.25f, 0.5f, 0.75f};

    float[] reference = new float[numRows];
    javaScorerOver(TestDenseMatrices.of(values, numRows, dimension))
        .score(query, reference);

    // Two rows per chunk, so three chunks with the last one short.
    DenseMatrix chunked = TestDenseMatrices.of(values, numRows, dimension, /* maxChunkValues */ 7);
    assertEquals(3, chunked.numChunks());

    float[] fromJava = new float[numRows];
    new JavaMatrixDotProductScorer(chunked, TestMatrixRows.of(chunked.numRows()))
        .score(query, fromJava);

    assertArrayEquals(reference, fromJava, DELTA);

    try (NativeMatrixDotProductScorer<org.bytedeco.javacpp.FloatPointer> openBlasScorer =
        new NativeMatrixDotProductScorer<>(
            chunked, TestMatrixRows.of((chunked).numRows()), OpenBlas.shared())) {
      float[] fromOpenBlas = new float[numRows];

      openBlasScorer.multiplyOneQuery(query, SELECTION, fromOpenBlas);

      assertArrayEquals(reference, fromOpenBlas, DELTA);
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
    assumeTrue(OpenBlas.isAvailable());
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
        javaScorerOver(TestDenseMatrices.of(matrix, numRows, dimension));

    try (NativeMatrixDotProductScorer<org.bytedeco.javacpp.FloatPointer> openBlasScorer =
        new NativeMatrixDotProductScorer<>(
            TestDenseMatrices.of(matrix, numRows, dimension),
            TestMatrixRows.of(numRows),
            OpenBlas.shared())) {
      javaScorer.score(query, javaDots);
      openBlasScorer.multiplyOneQuery(query, SELECTION, openBlasDots);
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

  private static JavaMatrixDotProductScorer javaScorerOver(DenseMatrix matrix) {
    return new JavaMatrixDotProductScorer(matrix, TestMatrixRows.of(matrix.numRows()));
  }
}
