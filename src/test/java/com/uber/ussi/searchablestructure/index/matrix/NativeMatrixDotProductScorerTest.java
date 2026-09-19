package com.uber.ussi.searchablestructure.index.matrix;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The scorer over a native library, exercised against a library whose buffers are float arrays.
 * Every property asserted here is the scorer's own, so none of it depends on which library is
 * underneath or on one being installed.
 */
class NativeMatrixDotProductScorerTest {
  private static final float DELTA = 1e-3f;

  @Test
  void scoresAMatrixAgainstAQuery() {
    DenseMatrix matrix = TestDenseMatrices.of(new float[] {1f, 2f, 3f, 4f}, 2, 2);
    try (NativeMatrixDotProductScorer<float[]> scorer =
        new NativeMatrixDotProductScorer<>(matrix, new FakeNativeBlas())) {
      float[] dotProducts = new float[2];

      scorer.score(new float[] {0.5f, 2f}, dotProducts);

      assertArrayEquals(new float[] {4.5f, 9.5f}, dotProducts, DELTA);
    }
  }

  /** Buffers are reused, so repeated scoring must not keep allocating them. */
  @Test
  void scoringRepeatedlyAllocatesNoFurtherBuffers() {
    DenseMatrix matrix = TestDenseMatrices.of(new float[] {1f, 0f, 0f, 1f}, 2, 2);
    FakeNativeBlas blas = new FakeNativeBlas();
    try (NativeMatrixDotProductScorer<float[]> scorer =
        new NativeMatrixDotProductScorer<>(matrix, blas)) {
      float[] dotProducts = new float[2];
      scorer.score(new float[] {1f, 2f}, dotProducts);
      int afterFirstScore = blas.numAllocations.get();

      for (int i = 0; i < 100; ++i) {
        float[] repeated = new float[2];
        scorer.score(new float[] {1f, 2f}, repeated);

        assertArrayEquals(dotProducts, repeated, DELTA, "score " + i + " differed");
      }

      assertEquals(
          afterFirstScore,
          blas.numAllocations.get(),
          "a hundred further scores on one thread must reuse the first score's buffers");
    }
  }

  @Test
  void theProductsBufferIsSizedForTheChunkOfMostRows() {
    // Five rows at two per chunk gives chunks of 2, 2 and 1, so the last is not the largest.
    DenseMatrix uneven =
        TestDenseMatrices.of(
            new float[] {1f, 1f, 2f, 2f, 3f, 3f, 4f, 4f, 5f, 5f}, 5, 2, /* maxChunkValues */ 4);

    assertEquals(3, uneven.numChunks());
    assertEquals(1, uneven.numRowsInChunk(2), "the last chunk is the smallest");
    assertEquals(2, NativeMatrixDotProductScorer.getMaxNumRowsInAChunk(uneven));
    assertEquals(
        1,
        NativeMatrixDotProductScorer.getMaxNumRowsInAChunk(
            TestDenseMatrices.of(new float[] {1f}, 1, 1)));
    assertEquals(
        0,
        NativeMatrixDotProductScorer.getMaxNumRowsInAChunk(
            TestDenseMatrices.of(new float[0], 0, 0)));
  }

  @Test
  void aChunkedMatrixIsScoredChunkByChunkIntoOneArray() {
    // Two rows per chunk over five rows, so the last chunk is smaller than the rest.
    DenseMatrix matrix =
        TestDenseMatrices.of(
            new float[] {1f, 1f, 2f, 2f, 3f, 3f, 4f, 4f, 5f, 5f}, 5, 2, /* maxChunkValues */ 4);
    assertTrue(matrix.numChunks() > 1, "the test needs several chunks");

    try (NativeMatrixDotProductScorer<float[]> scorer =
        new NativeMatrixDotProductScorer<>(matrix, new FakeNativeBlas())) {
      float[] dotProducts = new float[5];

      scorer.score(new float[] {1f, 1f}, dotProducts);

      assertArrayEquals(new float[] {2f, 4f, 6f, 8f, 10f}, dotProducts, DELTA);
    }
  }

  @Test
  void reusedBuffersDoNotLeakBetweenQueries() {
    // A second query must not observe the first query's values left in the buffer.
    DenseMatrix matrix = TestDenseMatrices.of(new float[] {1f, 1f, 1f, 1f, 1f, 1f}, 3, 2);
    try (NativeMatrixDotProductScorer<float[]> scorer =
        new NativeMatrixDotProductScorer<>(matrix, new FakeNativeBlas())) {
      float[] first = new float[3];
      float[] second = new float[3];

      scorer.score(new float[] {10f, 20f}, first);
      scorer.score(new float[] {1f, 2f}, second);

      assertArrayEquals(new float[] {30f, 30f, 30f}, first, DELTA);
      assertArrayEquals(new float[] {3f, 3f, 3f}, second, DELTA);
    }
  }

  /**
   * Scores execute concurrently, so a set of buffers must never be held by two of them. Shared
   * buffers would appear as a score returning another query's products.
   */
  @Test
  void concurrentScoresDoNotShareBuffers() throws Exception {
    int dimension = 8;
    int numRows = 16;
    Random random = new Random(4);
    float[] values = new float[numRows * dimension];
    for (int i = 0; i < values.length; ++i) {
      values[i] = random.nextFloat();
    }
    DenseMatrix matrix = TestDenseMatrices.of(values, numRows, dimension);

    try (NativeMatrixDotProductScorer<float[]> scorer =
        new NativeMatrixDotProductScorer<>(matrix, new FakeNativeBlas())) {
      int numThreads = 8;
      // Each thread owns a distinct query, and the products it must keep obtaining.
      List<float[]> queries = new ArrayList<>();
      List<float[]> expected = new ArrayList<>();
      for (int thread = 0; thread < numThreads; ++thread) {
        float[] query = new float[dimension];
        for (int i = 0; i < dimension; ++i) {
          query[i] = thread + i;
        }
        float[] products = new float[numRows];
        scorer.score(query, products);
        queries.add(query);
        expected.add(products);
      }

      ConcurrentLinkedQueue<String> mismatches = new ConcurrentLinkedQueue<>();
      CountDownLatch start = new CountDownLatch(1);
      CountDownLatch finished = new CountDownLatch(numThreads);
      for (int thread = 0; thread < numThreads; ++thread) {
        final int id = thread;
        Thread worker =
            new Thread(
                () -> {
                  try {
                    start.await();
                    float[] products = new float[numRows];
                    for (int round = 0; round < 200; ++round) {
                      scorer.score(queries.get(id), products);
                      for (int row = 0; row < numRows; ++row) {
                        if (Math.abs(products[row] - expected.get(id)[row]) > DELTA) {
                          mismatches.add("thread " + id + " round " + round + " row " + row);
                          return;
                        }
                      }
                    }
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  } finally {
                    finished.countDown();
                  }
                });
        worker.setDaemon(true);
        worker.start();
      }
      start.countDown();
      finished.await();

      assertEquals(List.of(), List.copyOf(mismatches));
    }
  }

  /** The library's bound is what limits the callers inside it, not the buffer pool. */
  @Test
  void admitsNoMoreConcurrentCallersThanTheLibraryPermits() throws Exception {
    DenseMatrix matrix = TestDenseMatrices.of(new float[] {1f, 1f, 1f, 1f}, 2, 2);
    FakeNativeBlas blas = new FakeNativeBlas(/* maxNumConcurrentCallers */ 1);
    try (NativeMatrixDotProductScorer<float[]> scorer =
        new NativeMatrixDotProductScorer<>(matrix, blas)) {
      int numThreads = 4;
      CountDownLatch start = new CountDownLatch(1);
      CountDownLatch finished = new CountDownLatch(numThreads);
      for (int thread = 0; thread < numThreads; ++thread) {
        Thread worker =
            new Thread(
                () -> {
                  try {
                    start.await();
                    for (int round = 0; round < 50; ++round) {
                      scorer.score(new float[] {1f, 1f}, new float[2]);
                    }
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  } finally {
                    finished.countDown();
                  }
                });
        worker.setDaemon(true);
        worker.start();
      }
      start.countDown();
      finished.await();

      assertEquals(
          1,
          blas.maxNumConcurrentMultiplies.get(),
          "the library was entered by more callers than it admits");
    }
  }

  @Test
  void closingReleasesTheChunksAndTheBuffers() {
    DenseMatrix matrix = TestDenseMatrices.of(new float[] {1f, 2f}, 1, 2);
    FakeNativeBlas blas = new FakeNativeBlas();
    NativeMatrixDotProductScorer<float[]> scorer =
        new NativeMatrixDotProductScorer<>(matrix, blas);
    scorer.score(new float[] {1f, 1f}, new float[1]);

    scorer.close();

    // The one matrix chunk, plus the query and products buffers of the one set that scoring
    // allocated.
    assertEquals(3, blas.numFrees.get(), "freed " + blas.numFrees.get() + " buffers");

    scorer.close();

    assertEquals(3, blas.numFrees.get(), "closing twice must not free twice");
  }

  @Test
  void rejectsScoringAfterClose() {
    NativeMatrixDotProductScorer<float[]> scorer =
        new NativeMatrixDotProductScorer<>(
            TestDenseMatrices.of(new float[] {1f}, 1, 1), new FakeNativeBlas());

    scorer.close();

    assertThrows(
        IllegalStateException.class, () -> scorer.score(new float[] {1f}, new float[] {0f}));
  }

  /**
   * A library whose buffer is a float array, multiplying in Java. Holding real values lets a
   * score's arithmetic be asserted, and counting the calls lets its buffer use be asserted.
   */
  private static final class FakeNativeBlas implements NativeBlas<float[]> {
    private final NativeBlasAdmission admission;
    private final AtomicInteger numAllocations = new AtomicInteger();
    private final AtomicInteger numFrees = new AtomicInteger();
    private final AtomicInteger numConcurrentMultiplies = new AtomicInteger();
    private final AtomicInteger maxNumConcurrentMultiplies = new AtomicInteger();
    private final List<float[]> live = Collections.synchronizedList(new ArrayList<>());

    FakeNativeBlas() {
      this(Integer.MAX_VALUE);
    }

    FakeNativeBlas(int maxNumConcurrentCallers) {
      this.admission = new NativeBlasAdmission(maxNumConcurrentCallers);
    }

    @Override
    public NativeBlasAdmission getAdmission() {
      return admission;
    }

    @Override
    public float[] allocate(float[] values) {
      return track(values.clone());
    }

    @Override
    public float[] allocate(int numValues) {
      return track(new float[numValues]);
    }

    @Override
    public void free(float[] buffer) {
      numFrees.incrementAndGet();
      live.remove(buffer);
    }

    @Override
    public void write(float[] buffer, float[] values, int numValues) {
      System.arraycopy(values, 0, buffer, 0, numValues);
    }

    @Override
    public void read(float[] buffer, float[] values, int offset, int numValues) {
      System.arraycopy(buffer, 0, values, offset, numValues);
    }

    @Override
    public void multiply(
        int numRows, int numColumns, float[] matrix, float[] vector, float[] products) {
      maxNumConcurrentMultiplies.accumulateAndGet(
          numConcurrentMultiplies.incrementAndGet(), Math::max);
      try {
        for (int row = 0; row < numRows; ++row) {
          float product = 0f;
          for (int column = 0; column < numColumns; ++column) {
            product += matrix[row * numColumns + column] * vector[column];
          }
          products[row] = product;
        }
      } finally {
        numConcurrentMultiplies.decrementAndGet();
      }
    }

    private float[] track(float[] buffer) {
      numAllocations.incrementAndGet();
      live.add(buffer);
      return buffer;
    }
  }
}
