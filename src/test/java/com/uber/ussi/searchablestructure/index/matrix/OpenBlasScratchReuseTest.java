package com.uber.ussi.searchablestructure.index.matrix;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** The buffers a score needs are reused, and reuse does not let concurrent scores collide. */
class OpenBlasScratchReuseTest {
  private static final float DELTA = 1e-3f;

  /** The point of the change: scoring repeatedly must not keep allocating native buffers. */
  @Test
  void scoringRepeatedlyAllocatesNoFurtherBuffers() {
    DenseMatrix matrix = TestDenseMatrices.of(new float[] {1f, 0f, 0f, 1f}, 2, 2);
    OpenBlasMatrixDotProductScorer.FloatSizePointerFactory original =
        OpenBlasMatrixDotProductScorer.floatSizePointerFactory;
    AtomicInteger buffersCreated = new AtomicInteger();
    try {
      OpenBlasMatrixDotProductScorer.floatSizePointerFactory =
          size -> {
            buffersCreated.incrementAndGet();
            return original.create(size);
          };
      try (OpenBlasMatrixDotProductScorer scorer =
          new OpenBlasMatrixDotProductScorer(
              matrix, OpenBlasMatrixDotProductScorer::isAvailable)) {
        float[] dotProducts = new float[2];
        scorer.score(new float[] {1f, 2f}, dotProducts);
        int afterFirstScore = buffersCreated.get();

        for (int i = 0; i < 100; ++i) {
          float[] repeated = new float[2];
          scorer.score(new float[] {1f, 2f}, repeated);

          assertArrayEquals(dotProducts, repeated, DELTA, "score " + i + " differed");
        }

        assertEquals(
            afterFirstScore,
            buffersCreated.get(),
            "a hundred further scores on one thread must reuse the first score's buffers");
      }
    } finally {
      OpenBlasMatrixDotProductScorer.floatSizePointerFactory = original;
    }
  }

  @Test
  void theScoreBufferIsSizedForTheWidestChunk() {
    // Five rows at two per chunk gives chunks of 2, 2 and 1, so the last is not the widest.
    DenseMatrix uneven =
        TestDenseMatrices.of(
            new float[] {1f, 1f, 2f, 2f, 3f, 3f, 4f, 4f, 5f, 5f}, 5, 2, /* maxChunkValues */ 4);

    assertEquals(3, uneven.numChunks());
    assertEquals(1, uneven.numRowsInChunk(2), "the last chunk is the narrow one");
    assertEquals(2, OpenBlasMatrixDotProductScorer.widestChunk(uneven));
    assertEquals(
        1, OpenBlasMatrixDotProductScorer.widestChunk(TestDenseMatrices.of(new float[] {1f}, 1, 1)));
    assertEquals(
        0, OpenBlasMatrixDotProductScorer.widestChunk(TestDenseMatrices.of(new float[0], 0, 0)));
  }

  @Test
  void reusedBuffersDoNotLeakBetweenQueries() {
    // A second query must not see the first query's values left in the buffer.
    DenseMatrix matrix = TestDenseMatrices.of(new float[] {1f, 1f, 1f, 1f, 1f, 1f}, 3, 2);
    try (OpenBlasMatrixDotProductScorer scorer =
        new OpenBlasMatrixDotProductScorer(
            matrix, OpenBlasMatrixDotProductScorer::isAvailable)) {
      float[] first = new float[3];
      float[] second = new float[3];

      scorer.score(new float[] {10f, 20f}, first);
      scorer.score(new float[] {1f, 2f}, second);

      assertArrayEquals(new float[] {30f, 30f, 30f}, first, DELTA);
      assertArrayEquals(new float[] {3f, 3f, 3f}, second, DELTA);
    }
  }

  /**
   * Several scores run at once, so a buffer must never be held by two of them. A shared buffer
   * would show up as a score picking up another query's values.
   */
  @Test
  void concurrentScoresDoNotShareABuffer() throws Exception {
    int dimension = 8;
    int numRows = 16;
    Random random = new Random(4);
    float[] values = new float[numRows * dimension];
    for (int i = 0; i < values.length; ++i) {
      values[i] = random.nextFloat();
    }
    DenseMatrix matrix = TestDenseMatrices.of(values, numRows, dimension);

    try (OpenBlasMatrixDotProductScorer scorer =
        new OpenBlasMatrixDotProductScorer(
            matrix, OpenBlasMatrixDotProductScorer::isAvailable)) {
      int threads = 8;
      // Each thread owns a distinct query, and knows the answer it must keep getting.
      List<float[]> queries = new ArrayList<>();
      List<float[]> expected = new ArrayList<>();
      for (int t = 0; t < threads; ++t) {
        float[] query = new float[dimension];
        for (int i = 0; i < dimension; ++i) {
          query[i] = t + i;
        }
        float[] answer = new float[numRows];
        scorer.score(query, answer);
        queries.add(query);
        expected.add(answer);
      }

      ConcurrentLinkedQueue<String> mismatches = new ConcurrentLinkedQueue<>();
      CountDownLatch start = new CountDownLatch(1);
      CountDownLatch finished = new CountDownLatch(threads);
      for (int t = 0; t < threads; ++t) {
        final int id = t;
        Thread worker =
            new Thread(
                () -> {
                  try {
                    start.await();
                    float[] scores = new float[numRows];
                    for (int round = 0; round < 200; ++round) {
                      scorer.score(queries.get(id), scores);
                      for (int row = 0; row < numRows; ++row) {
                        if (Math.abs(scores[row] - expected.get(id)[row]) > DELTA) {
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

  @Test
  void closingReleasesTheBuffersAndTheMatrix() {
    DenseMatrix matrix = TestDenseMatrices.of(new float[] {1f, 2f}, 1, 2);
    List<Object> released = new ArrayList<>();
    OpenBlasMatrixDotProductScorer.FloatPointerDeallocator originalDeallocator =
        OpenBlasMatrixDotProductScorer.floatPointerDeallocator;
    try {
      OpenBlasMatrixDotProductScorer scorer =
          new OpenBlasMatrixDotProductScorer(
              matrix, OpenBlasMatrixDotProductScorer::isAvailable);
      scorer.score(new float[] {1f, 1f}, new float[1]);
      OpenBlasMatrixDotProductScorer.floatPointerDeallocator = released::add;

      scorer.close();

      // The matrix chunk, plus the query and score buffers of the scratch that scoring created.
      assertEquals(3, released.size(), "released " + released.size() + " buffers");
      scorer.close();

      assertEquals(3, released.size(), "closing twice must not release twice");
    } finally {
      OpenBlasMatrixDotProductScorer.floatPointerDeallocator = originalDeallocator;
    }
  }

  @Test
  void aChunkedMatrixSizesTheBufferForItsWidestChunk() {
    // Two rows per chunk over five rows, so the last chunk is narrower than the rest.
    DenseMatrix matrix =
        TestDenseMatrices.of(
            new float[] {1f, 1f, 2f, 2f, 3f, 3f, 4f, 4f, 5f, 5f}, 5, 2, /* maxChunkValues */ 4);
    assertTrue(matrix.numChunks() > 1, "the test needs several chunks");

    try (OpenBlasMatrixDotProductScorer scorer =
        new OpenBlasMatrixDotProductScorer(
            matrix, OpenBlasMatrixDotProductScorer::isAvailable)) {
      float[] dotProducts = new float[5];

      scorer.score(new float[] {1f, 1f}, dotProducts);

      assertArrayEquals(new float[] {2f, 4f, 6f, 8f, 10f}, dotProducts, DELTA);
    }
  }
}
