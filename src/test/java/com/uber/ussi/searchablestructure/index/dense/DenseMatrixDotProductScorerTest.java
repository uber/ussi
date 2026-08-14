package com.uber.ussi.searchablestructure.index.dense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class DenseMatrixDotProductScorerTest {
  private static final int RANDOM_PROFILE_PHOTO_NUM_ROWS = 1000;
  private static final int RANDOM_PROFILE_PHOTO_DIMENSION = 4096;
  private static final long RANDOM_PROFILE_PHOTO_SEED = 20260616L;
  private static final float DELTA = 1e-3f;
  private static volatile float benchmarkSink;

  @Test
  void openBlasScorerMatchesJavaScorer() {
    assumeTrue(OpenBlasDenseMatrixDotProductScorer.isAvailable());
    float[] matrix =
        new float[] {
          1.0f, 0.0f, 2.0f,
          0.5f, 0.5f, 0.5f,
          0.0f, 3.0f, 1.0f
        };
    float[] query = new float[] {0.25f, 0.5f, 0.75f};
    float[] javaDots = new float[3];
    float[] openBlasDots = new float[3];
    JavaDenseMatrixDotProductScorer javaScorer =
        new JavaDenseMatrixDotProductScorer(matrix, /* numRows */ 3, /* dimension */ 3);
    try (OpenBlasDenseMatrixDotProductScorer openBlasScorer =
        new OpenBlasDenseMatrixDotProductScorer(
            matrix,
            /* numRows */ 3,
            /* dimension */ 3,
            OpenBlasDenseMatrixDotProductScorer::isAvailable)) {
      javaScorer.score(query, javaDots);
      openBlasScorer.score(query, openBlasDots);
    }

    for (int i = 0; i < javaDots.length; ++i) {
      assertEquals(javaDots[i], openBlasDots[i], DELTA);
    }
  }

  @Test
  void randomProfilePhotoEmbeddingBenchmarkReportsOpenBlasSpeedup() {
    assumeTrue(OpenBlasDenseMatrixDotProductScorer.isAvailable());
    EmbeddingData embeddingData =
        generatedProfilePhotoEmbeddingsData(
            RANDOM_PROFILE_PHOTO_NUM_ROWS,
            RANDOM_PROFILE_PHOTO_DIMENSION,
            RANDOM_PROFILE_PHOTO_SEED);
    int numRows = embeddingData.numRows;
    int dimension = embeddingData.dimension;
    int numQueries = 20;
    int numWarmups = 5;
    int openBlasThreads = Math.max(1, Runtime.getRuntime().availableProcessors());
    float[] matrix = embeddingData.rowMajorValues;
    float[][] queries = embeddingData.getFirstRowsAsQueries(numQueries);
    float[] javaDots = new float[numRows];
    float[] openBlasDots = new float[numRows];
    JavaDenseMatrixDotProductScorer javaScorer =
        new JavaDenseMatrixDotProductScorer(matrix, numRows, dimension);

    try (OpenBlasDenseMatrixDotProductScorer openBlasScorer =
        new OpenBlasDenseMatrixDotProductScorer(
            matrix, numRows, dimension, OpenBlasDenseMatrixDotProductScorer::isAvailable)) {
      javaScorer.score(queries[0], javaDots);
      openBlasScorer.score(queries[0], openBlasDots);
      for (int i = 0; i < numRows; ++i) {
        assertEquals(javaDots[i], openBlasDots[i], DELTA);
      }

      warmUp(javaScorer, queries, javaDots, numWarmups);
      warmUp(openBlasScorer, queries, openBlasDots, numWarmups);

      long javaNanos = timeScorer(javaScorer, queries, javaDots);
      long openBlasNanos = timeScorer(openBlasScorer, queries, openBlasDots);
      double javaAvgMillis = nanosToMillis(javaNanos) / numQueries;
      double openBlasAvgMillis = nanosToMillis(openBlasNanos) / numQueries;
      double speedup = (double) javaNanos / openBlasNanos;

      System.out.printf(
          "Random profile-photo embedding dot-product benchmark: rows=%d, dimension=%d, "
              + "queries=%d, openBlasThreads=%d, javaAvgMs=%.3f, openBlasAvgMs=%.3f, "
              + "speedup=%.2fx, checksum=%.6f%n",
          numRows,
          dimension,
          numQueries,
          openBlasThreads,
          javaAvgMillis,
          openBlasAvgMillis,
          speedup,
          benchmarkSink);
    }
  }

  private static void warmUp(
      DenseMatrixDotProductScorer scorer, float[][] queries, float[] dotProducts, int numWarmups) {
    for (int i = 0; i < numWarmups; ++i) {
      scorer.score(queries[i % queries.length], dotProducts);
    }
  }

  private static long timeScorer(
      DenseMatrixDotProductScorer scorer, float[][] queries, float[] dotProducts) {
    long startNanos = System.nanoTime();
    float checksum = 0.0f;
    for (float[] query : queries) {
      scorer.score(query, dotProducts);
      checksum +=
          dotProducts[0]
              + dotProducts[dotProducts.length / 2]
              + dotProducts[dotProducts.length - 1];
    }
    benchmarkSink = checksum;
    return System.nanoTime() - startNanos;
  }

  private static double nanosToMillis(long nanos) {
    return nanos / 1_000_000.0d;
  }

  private static EmbeddingData generatedProfilePhotoEmbeddingsData(
      int numRows, int dimension, long seed) {
    java.util.Random random = new java.util.Random(seed);
    float[] rowMajorValues = new float[numRows * dimension];
    for (int i = 0; i < rowMajorValues.length; ++i) {
      rowMajorValues[i] = (random.nextFloat() - 0.5f) / dimension;
    }
    return new EmbeddingData(rowMajorValues, numRows, dimension);
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

    private float[][] getFirstRowsAsQueries(int numQueries) {
      if (numQueries > numRows) {
        throw new IllegalArgumentException("numQueries must be <= numRows.");
      }
      float[][] queries = new float[numQueries][];
      for (int query = 0; query < numQueries; ++query) {
        int offset = query * dimension;
        queries[query] = Arrays.copyOfRange(rowMajorValues, offset, offset + dimension);
      }
      return queries;
    }
  }
}
