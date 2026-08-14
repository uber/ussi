package com.uber.ussi.searchablestructure.index.dense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.uber.ussi.utils.Utils;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DenseMatrixDotProductScorersTest {
  private static final float DELTA = 1e-6f;

  @Test
  void createReturnsUsableScorer() {
    try (DenseMatrixDotProductScorer scorer =
        DenseMatrixDotProductScorers.create(new float[] {1f, 2f, 3f, 4f}, 2, 2)) {
      float[] dotProducts = new float[2];

      scorer.score(new float[] {0.5f, 2f}, dotProducts);

      assertEquals(4.5f, dotProducts[0], DELTA);
      assertEquals(9.5f, dotProducts[1], DELTA);
    }
  }

  @Test
  void createUsesDefaultOpenBlasScorerWhenNativePathIsAvailable() {
    assumeTrue(isOpenBlasSupportedPlatform());
    FakeOpenBlas fakeOpenBlas = new FakeOpenBlas();

    withFakeOpenBlas(
        fakeOpenBlas,
        () -> {
          try (DenseMatrixDotProductScorer scorer =
              DenseMatrixDotProductScorers.create(new float[] {1f}, 1, 1)) {
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
    DenseMatrixDotProductScorer expectedScorer =
        new DenseMatrixDotProductScorer() {
          @Override
          public void score(float[] queryValues, float[] dotProducts) {}
        };

    try (DenseMatrixDotProductScorer scorer =
        DenseMatrixDotProductScorers.create(
            new float[] {1f, 2f}, 1, 2, /* openBlasAvailable */ true, () -> expectedScorer)) {
      assertSame(expectedScorer, scorer);
    }
  }

  @Test
  void createBuildsJavaScorerWhenOpenBlasUnavailable() {
    try (DenseMatrixDotProductScorer scorer =
        DenseMatrixDotProductScorers.create(
            new float[] {1f, 2f},
            1,
            2,
            /* openBlasAvailable */ false,
            () -> {
              throw new AssertionError(
                  "supplier must not be invoked when OpenBLAS is unavailable.");
            })) {
      assertInstanceOf(JavaDenseMatrixDotProductScorer.class, scorer);
    }
  }

  @Test
  void createFallsBackToJavaScorerOnLinkageError() {
    try (DenseMatrixDotProductScorer scorer =
        DenseMatrixDotProductScorers.create(
            new float[] {1f, 2f},
            1,
            2,
            /* openBlasAvailable */ true,
            () -> {
              throw new UnsatisfiedLinkError("native missing");
            })) {
      assertInstanceOf(JavaDenseMatrixDotProductScorer.class, scorer);
    }
  }

  @Test
  void createFallsBackToJavaScorerWhenRuntimeExceptionWrapsLinkageError() {
    try (DenseMatrixDotProductScorer scorer =
        DenseMatrixDotProductScorers.create(
            new float[] {1f, 2f},
            1,
            2,
            /* openBlasAvailable */ true,
            () -> {
              throw new RuntimeException(new UnsatisfiedLinkError("native missing"));
            })) {
      assertInstanceOf(JavaDenseMatrixDotProductScorer.class, scorer);
    }
  }

  @Test
  void createRethrowsRuntimeExceptionNotCausedByLinkageError() {
    assertThrows(
        IllegalStateException.class,
        () ->
            DenseMatrixDotProductScorers.create(
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
            new OpenBlasDenseMatrixDotProductScorer(
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
          try (OpenBlasDenseMatrixDotProductScorer scorer =
              new OpenBlasDenseMatrixDotProductScorer(
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
    assertFalse(OpenBlasDenseMatrixDotProductScorer.isAvailable(false, () -> {}));
  }

  @Test
  void isAvailableReturnsFalseWhenNativeProbeFails() {
    assertFalse(
        OpenBlasDenseMatrixDotProductScorer.isAvailable(
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
                OpenBlasDenseMatrixDotProductScorer.isAvailable(
                    true, () -> fakeOpenBlas.nativeLoadProbeCalls++)));

    assertEquals(1, fakeOpenBlas.nativeLoadProbeCalls);
    assertEquals(1, fakeOpenBlas.deallocateCalls);
  }

  @Test
  void defaultScorerCloseCanBeCalled() {
    DenseMatrixDotProductScorer scorer =
        new DenseMatrixDotProductScorer() {
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
          OpenBlasDenseMatrixDotProductScorer scorer =
              new OpenBlasDenseMatrixDotProductScorer(
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
        () -> DenseMatrixDotProductScorers.validateMatrix(new float[0], -1, 2));
  }

  @Test
  void validateMatrixRejectsNegativeDimension() {
    assertThrows(
        IllegalArgumentException.class,
        () -> DenseMatrixDotProductScorers.validateMatrix(new float[0], 1, -1));
  }

  @Test
  void validateMatrixRejectsLengthMismatch() {
    assertThrows(
        IllegalArgumentException.class,
        () -> DenseMatrixDotProductScorers.validateMatrix(new float[] {1f}, 1, 2));
  }

  @Test
  void validateMatrixRejectsHugeShape() {
    assertThrows(
        IllegalArgumentException.class,
        () -> DenseMatrixDotProductScorers.validateMatrix(null, 46_342, 46_342));
  }

  @Test
  void validateMatrixAcceptsMatchingLength() {
    DenseMatrixDotProductScorers.validateMatrix(new float[] {1f, 2f}, 1, 2);
  }

  @Test
  void validateScoreInputsRejectsQueryLengthMismatch() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            DenseMatrixDotProductScorers.validateScoreInputs(
                new float[] {1f, 2f}, 1, 2, new float[] {1f}, new float[] {0f}));
  }

  @Test
  void validateScoreInputsRejectsDotProductsLengthMismatch() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            DenseMatrixDotProductScorers.validateScoreInputs(
                new float[] {1f, 2f}, 1, 2, new float[] {1f, 2f}, new float[] {0f, 0f}));
  }

  @Test
  void validateScoreInputsAcceptsConsistentShapes() {
    DenseMatrixDotProductScorers.validateScoreInputs(
        new float[] {1f, 2f}, 1, 2, new float[] {1f, 2f}, new float[] {0f});
  }

  private static void withFakeOpenBlas(FakeOpenBlas fakeOpenBlas, Runnable runnable) {
    OpenBlasDenseMatrixDotProductScorer.FloatArrayPointerFactory originalArrayPointerFactory =
        OpenBlasDenseMatrixDotProductScorer.floatArrayPointerFactory;
    OpenBlasDenseMatrixDotProductScorer.FloatSizePointerFactory originalSizePointerFactory =
        OpenBlasDenseMatrixDotProductScorer.floatSizePointerFactory;
    OpenBlasDenseMatrixDotProductScorer.FloatPointerDeallocator originalDeallocator =
        OpenBlasDenseMatrixDotProductScorer.floatPointerDeallocator;
    OpenBlasDenseMatrixDotProductScorer.FloatPointerArrayReader originalArrayReader =
        OpenBlasDenseMatrixDotProductScorer.floatPointerArrayReader;
    OpenBlasDenseMatrixDotProductScorer.SgemvOperation originalSgemvOperation =
        OpenBlasDenseMatrixDotProductScorer.sgemvOperation;
    Runnable originalNativeLoadProbe = OpenBlasDenseMatrixDotProductScorer.blasNativeLoadProbe;
    java.util.function.IntSupplier originalThreadCountSupplier =
        OpenBlasDenseMatrixDotProductScorer.blasThreadCountSupplier;
    java.util.function.IntConsumer originalThreadCountSetter =
        OpenBlasDenseMatrixDotProductScorer.blasThreadCountSetter;
    try {
      OpenBlasDenseMatrixDotProductScorer.floatArrayPointerFactory = values -> null;
      OpenBlasDenseMatrixDotProductScorer.floatSizePointerFactory = size -> null;
      OpenBlasDenseMatrixDotProductScorer.floatPointerDeallocator =
          pointer -> fakeOpenBlas.deallocateCalls++;
      OpenBlasDenseMatrixDotProductScorer.floatPointerArrayReader =
          (pointer, values) -> values[0] = 2f;
      OpenBlasDenseMatrixDotProductScorer.sgemvOperation =
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
      OpenBlasDenseMatrixDotProductScorer.blasNativeLoadProbe =
          () -> fakeOpenBlas.nativeLoadProbeCalls++;
      OpenBlasDenseMatrixDotProductScorer.blasThreadCountSupplier = () -> fakeOpenBlas.threadCount;
      OpenBlasDenseMatrixDotProductScorer.blasThreadCountSetter =
          numThreads -> {
            fakeOpenBlas.threadCount = numThreads;
            fakeOpenBlas.threadCountUpdates.add(numThreads);
          };
      runnable.run();
    } finally {
      OpenBlasDenseMatrixDotProductScorer.floatArrayPointerFactory = originalArrayPointerFactory;
      OpenBlasDenseMatrixDotProductScorer.floatSizePointerFactory = originalSizePointerFactory;
      OpenBlasDenseMatrixDotProductScorer.floatPointerDeallocator = originalDeallocator;
      OpenBlasDenseMatrixDotProductScorer.floatPointerArrayReader = originalArrayReader;
      OpenBlasDenseMatrixDotProductScorer.sgemvOperation = originalSgemvOperation;
      OpenBlasDenseMatrixDotProductScorer.blasNativeLoadProbe = originalNativeLoadProbe;
      OpenBlasDenseMatrixDotProductScorer.blasThreadCountSupplier = originalThreadCountSupplier;
      OpenBlasDenseMatrixDotProductScorer.blasThreadCountSetter = originalThreadCountSetter;
    }
  }

  private static boolean isOpenBlasSupportedPlatform() {
    return (Utils.isRunningOnLinux() && Utils.isRunningOnArm())
        || (Utils.isRunningOnLinux() && Utils.isRunningOnX86())
        || (Utils.isRunningOnMacOs() && Utils.isRunningOnX86());
  }

  private static final class FakeOpenBlas {
    private int threadCount = 1;
    private int nativeLoadProbeCalls;
    private int deallocateCalls;
    private int gemvCalls;
    private final List<Integer> threadCountUpdates = new ArrayList<>();
  }
}
