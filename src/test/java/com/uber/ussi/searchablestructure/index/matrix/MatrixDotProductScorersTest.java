package com.uber.ussi.searchablestructure.index.matrix;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MatrixDotProductScorersTest {
  private static final float DELTA = 1e-6f;

  @Test
  void createReturnsUsableScorer() {
    try (MatrixDotProductScorer scorer =
        MatrixDotProductScorers.create(new float[] {1f, 2f, 3f, 4f}, 2, 2)) {
      float[] dotProducts = new float[2];

      scorer.score(new float[] {0.5f, 2f}, dotProducts);

      assertEquals(4.5f, dotProducts[0], DELTA);
      assertEquals(9.5f, dotProducts[1], DELTA);
    }
  }

  @Test
  void createUsesDefaultOpenBlasScorerWhenNativePathIsAvailable() {
    assumeTrue(OpenBlasMatrixDotProductScorer.isSupportedPlatform());
    FakeOpenBlas fakeOpenBlas = new FakeOpenBlas();

    withFakeOpenBlas(
        fakeOpenBlas,
        () -> {
          try (MatrixDotProductScorer scorer =
              MatrixDotProductScorers.create(new float[] {1f}, 1, 1)) {
            float[] dotProducts = new float[1];

            scorer.score(new float[] {2f}, dotProducts);

            assertEquals(2f, dotProducts[0], DELTA);
          }
        });

    assertEquals(1, fakeOpenBlas.gemvCalls);
    assertEquals(2, fakeOpenBlas.nativeLoadProbeCalls);
    assertEquals(3, fakeOpenBlas.deallocateCalls);
  }

  @Test
  void createUsesOpenBlasSupplierWhenAvailable() {
    MatrixDotProductScorer expectedScorer =
        new MatrixDotProductScorer() {
          @Override
          public void score(float[] queryValues, float[] dotProducts) {}
        };

    try (MatrixDotProductScorer scorer =
        MatrixDotProductScorers.create(
            new float[] {1f, 2f}, 1, 2, /* openBlasAvailable */ true, () -> expectedScorer)) {
      assertSame(expectedScorer, scorer);
    }
  }

  @Test
  void createBuildsJavaScorerWhenOpenBlasUnavailable() {
    try (MatrixDotProductScorer scorer =
        MatrixDotProductScorers.create(
            new float[] {1f, 2f},
            1,
            2,
            /* openBlasAvailable */ false,
            () -> {
              throw new AssertionError(
                  "supplier must not be invoked when OpenBLAS is unavailable.");
            })) {
      assertInstanceOf(JavaMatrixDotProductScorer.class, scorer);
    }
  }

  @Test
  void createFallsBackToJavaScorerOnLinkageError() {
    try (MatrixDotProductScorer scorer =
        MatrixDotProductScorers.create(
            new float[] {1f, 2f},
            1,
            2,
            /* openBlasAvailable */ true,
            () -> {
              throw new UnsatisfiedLinkError("native missing");
            })) {
      assertInstanceOf(JavaMatrixDotProductScorer.class, scorer);
    }
  }

  @Test
  void createFallsBackToJavaScorerWhenRuntimeExceptionWrapsLinkageError() {
    try (MatrixDotProductScorer scorer =
        MatrixDotProductScorers.create(
            new float[] {1f, 2f},
            1,
            2,
            /* openBlasAvailable */ true,
            () -> {
              throw new RuntimeException(new UnsatisfiedLinkError("native missing"));
            })) {
      assertInstanceOf(JavaMatrixDotProductScorer.class, scorer);
    }
  }

  @Test
  void createRethrowsRuntimeExceptionNotCausedByLinkageError() {
    assertThrows(
        IllegalStateException.class,
        () ->
            MatrixDotProductScorers.create(
                new float[] {1f, 2f},
                1,
                2,
                /* openBlasAvailable */ true,
                () -> {
                  throw new IllegalStateException("boom");
                }));
  }

  @Test
  void openBlasConstructorThrowsWhenUnavailable() {
    assertThrows(
        IllegalStateException.class,
        () ->
            new OpenBlasMatrixDotProductScorer(
                new float[] {1f}, 1, 1, /* availabilitySupplier */ () -> false));
  }

  @Test
  void openBlasScorerRestoresThreadCountAfterScore() {
    FakeOpenBlas fakeOpenBlas = new FakeOpenBlas();
    int expectedRestoredThreads = 1;
    fakeOpenBlas.threadCount = expectedRestoredThreads;

    withFakeOpenBlas(
        fakeOpenBlas,
        () -> {
          try (OpenBlasMatrixDotProductScorer scorer =
              new OpenBlasMatrixDotProductScorer(
                  new float[] {1f}, 1, 1, /* availabilitySupplier */ () -> true)) {
            float[] dotProducts = new float[1];

            scorer.score(new float[] {2f}, dotProducts);

            assertEquals(2f, dotProducts[0], DELTA);
          }
        });

    assertEquals(
        List.of(Math.max(1, Runtime.getRuntime().availableProcessors()), expectedRestoredThreads),
        fakeOpenBlas.threadCountUpdates);
  }

  @Test
  void isAvailableReturnsFalseOnUnsupportedPlatform() {
    assertFalse(OpenBlasMatrixDotProductScorer.isAvailable(false, () -> {}));
  }

  @Test
  void isAvailableReturnsFalseWhenNativeProbeFails() {
    assertFalse(
        OpenBlasMatrixDotProductScorer.isAvailable(
            true,
            () -> {
              throw new RuntimeException("native probe failed");
            }));
  }

  @Test
  void isAvailableReturnsTrueWhenNativeProbeAndPointerProbeSucceed() {
    FakeOpenBlas fakeOpenBlas = new FakeOpenBlas();

    withFakeOpenBlas(
        fakeOpenBlas,
        () ->
            assertTrue(
                OpenBlasMatrixDotProductScorer.isAvailable(
                    true, () -> fakeOpenBlas.nativeLoadProbeCalls++)));

    assertEquals(1, fakeOpenBlas.nativeLoadProbeCalls);
    assertEquals(1, fakeOpenBlas.deallocateCalls);
  }

  @Test
  void defaultScorerCloseCanBeCalled() {
    MatrixDotProductScorer scorer =
        new MatrixDotProductScorer() {
          @Override
          public void score(float[] queryValues, float[] dotProducts) {}
        };

    scorer.close();
  }

  @Test
  void openBlasScorerRejectsScoreAfterClose() {
    FakeOpenBlas fakeOpenBlas = new FakeOpenBlas();

    withFakeOpenBlas(
        fakeOpenBlas,
        () -> {
          OpenBlasMatrixDotProductScorer scorer =
              new OpenBlasMatrixDotProductScorer(
                  new float[] {1f}, 1, 1, /* availabilitySupplier */ () -> true);

          scorer.close();

          assertThrows(
              IllegalStateException.class, () -> scorer.score(new float[] {1f}, new float[] {0f}));
          scorer.close();
        });

    assertEquals(1, fakeOpenBlas.deallocateCalls);
  }

  @Test
  void validateMatrixRejectsNegativeNumRows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> MatrixDotProductScorers.validateMatrix(new float[0], -1, 2));
  }

  @Test
  void validateMatrixRejectsNegativeDimension() {
    assertThrows(
        IllegalArgumentException.class,
        () -> MatrixDotProductScorers.validateMatrix(new float[0], 1, -1));
  }

  @Test
  void validateMatrixRejectsLengthMismatch() {
    assertThrows(
        IllegalArgumentException.class,
        () -> MatrixDotProductScorers.validateMatrix(new float[] {1f}, 1, 2));
  }

  @Test
  void validateMatrixRejectsHugeDimensions() {
    assertThrows(
        IllegalArgumentException.class,
        () -> MatrixDotProductScorers.validateMatrix(null, 46_342, 46_342));
  }

  @Test
  void validateMatrixAcceptsMatchingLength() {
    MatrixDotProductScorers.validateMatrix(new float[] {1f, 2f}, 1, 2);
  }

  @Test
  void validateScoreInputsRejectsQueryLengthMismatch() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            MatrixDotProductScorers.validateScoreInputs(
                new float[] {1f, 2f}, 1, 2, new float[] {1f}, new float[] {0f}));
  }

  @Test
  void validateScoreInputsRejectsDotProductsLengthMismatch() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            MatrixDotProductScorers.validateScoreInputs(
                new float[] {1f, 2f}, 1, 2, new float[] {1f, 2f}, new float[] {0f, 0f}));
  }

  @Test
  void validateScoreInputsAcceptsConsistentDimensions() {
    MatrixDotProductScorers.validateScoreInputs(
        new float[] {1f, 2f}, 1, 2, new float[] {1f, 2f}, new float[] {0f});
  }

  private static void withFakeOpenBlas(FakeOpenBlas fakeOpenBlas, Runnable runnable) {
    OpenBlasMatrixDotProductScorer.FloatArrayPointerFactory originalArrayPointerFactory =
        OpenBlasMatrixDotProductScorer.floatArrayPointerFactory;
    OpenBlasMatrixDotProductScorer.FloatSizePointerFactory originalSizePointerFactory =
        OpenBlasMatrixDotProductScorer.floatSizePointerFactory;
    OpenBlasMatrixDotProductScorer.FloatPointerDeallocator originalDeallocator =
        OpenBlasMatrixDotProductScorer.floatPointerDeallocator;
    OpenBlasMatrixDotProductScorer.FloatPointerArrayReader originalArrayReader =
        OpenBlasMatrixDotProductScorer.floatPointerArrayReader;
    OpenBlasMatrixDotProductScorer.SgemvOperation originalSgemvOperation =
        OpenBlasMatrixDotProductScorer.sgemvOperation;
    Runnable originalNativeLoadProbe = OpenBlasMatrixDotProductScorer.blasNativeLoadProbe;
    java.util.function.IntSupplier originalThreadCountSupplier =
        OpenBlasMatrixDotProductScorer.blasThreadCountSupplier;
    java.util.function.IntConsumer originalThreadCountSetter =
        OpenBlasMatrixDotProductScorer.blasThreadCountSetter;
    try {
      OpenBlasMatrixDotProductScorer.floatArrayPointerFactory = values -> null;
      OpenBlasMatrixDotProductScorer.floatSizePointerFactory = size -> null;
      OpenBlasMatrixDotProductScorer.floatPointerDeallocator =
          pointer -> fakeOpenBlas.deallocateCalls++;
      OpenBlasMatrixDotProductScorer.floatPointerArrayReader = (pointer, values) -> values[0] = 2f;
      OpenBlasMatrixDotProductScorer.sgemvOperation =
          (order,
              transA,
              numRowsA,
              numColsA,
              alpha,
              matrix,
              lda,
              query,
              incX,
              beta,
              dotProducts,
              incY) -> fakeOpenBlas.gemvCalls++;
      OpenBlasMatrixDotProductScorer.blasNativeLoadProbe =
          () -> fakeOpenBlas.nativeLoadProbeCalls++;
      OpenBlasMatrixDotProductScorer.blasThreadCountSupplier = () -> fakeOpenBlas.threadCount;
      OpenBlasMatrixDotProductScorer.blasThreadCountSetter =
          numThreads -> {
            fakeOpenBlas.threadCount = numThreads;
            fakeOpenBlas.threadCountUpdates.add(numThreads);
          };
      runnable.run();
    } finally {
      OpenBlasMatrixDotProductScorer.floatArrayPointerFactory = originalArrayPointerFactory;
      OpenBlasMatrixDotProductScorer.floatSizePointerFactory = originalSizePointerFactory;
      OpenBlasMatrixDotProductScorer.floatPointerDeallocator = originalDeallocator;
      OpenBlasMatrixDotProductScorer.floatPointerArrayReader = originalArrayReader;
      OpenBlasMatrixDotProductScorer.sgemvOperation = originalSgemvOperation;
      OpenBlasMatrixDotProductScorer.blasNativeLoadProbe = originalNativeLoadProbe;
      OpenBlasMatrixDotProductScorer.blasThreadCountSupplier = originalThreadCountSupplier;
      OpenBlasMatrixDotProductScorer.blasThreadCountSetter = originalThreadCountSetter;
    }
  }

  private static final class FakeOpenBlas {
    private int threadCount = 1;
    private int nativeLoadProbeCalls;
    private int deallocateCalls;
    private int gemvCalls;
    private final List<Integer> threadCountUpdates = new ArrayList<>();
  }
}
