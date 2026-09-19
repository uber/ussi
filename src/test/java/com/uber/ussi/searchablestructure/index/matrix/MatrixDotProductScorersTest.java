package com.uber.ussi.searchablestructure.index.matrix;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class MatrixDotProductScorersTest {
  private static final float DELTA = 1e-6f;

  @Test
  void createReturnsUsableScorer() {
    try (MatrixDotProductScorer scorer =
        MatrixDotProductScorers.create(
            TestDenseMatrices.of(new float[] {1f, 2f, 3f, 4f}, 2, 2))) {
      float[] dotProducts = new float[2];

      scorer.score(new float[] {0.5f, 2f}, dotProducts);

      assertEquals(4.5f, dotProducts[0], DELTA);
      assertEquals(9.5f, dotProducts[1], DELTA);
    }
  }

  @Test
  void createUsesTheNativeSupplierWhenAvailable() {
    MatrixDotProductScorer expectedScorer =
        new MatrixDotProductScorer() {
          @Override
          public void score(float[] queryValues, float[] dotProducts) {}
        };

    try (MatrixDotProductScorer scorer =
        MatrixDotProductScorers.create(
            TestDenseMatrices.of(new float[] {1f, 2f}, 1, 2),
            /* nativeBlasAvailable */ true, () -> expectedScorer)) {
      assertSame(expectedScorer, scorer);
    }
  }

  @Test
  void createBuildsJavaScorerWhenNoNativeLibraryIsAvailable() {
    try (MatrixDotProductScorer scorer =
        MatrixDotProductScorers.create(
            TestDenseMatrices.of(new float[] {1f, 2f}, 1, 2),
            /* nativeBlasAvailable */ false,
            () -> {
              throw new AssertionError(
                  "the supplier must not be invoked when no native library is available.");
            })) {
      assertInstanceOf(JavaMatrixDotProductScorer.class, scorer);
    }
  }

  @Test
  void createFallsBackToJavaScorerOnLinkageError() {
    try (MatrixDotProductScorer scorer =
        MatrixDotProductScorers.create(
            TestDenseMatrices.of(new float[] {1f, 2f}, 1, 2),
            /* nativeBlasAvailable */ true,
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
            TestDenseMatrices.of(new float[] {1f, 2f}, 1, 2),
            /* nativeBlasAvailable */ true,
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
                TestDenseMatrices.of(new float[] {1f, 2f}, 1, 2),
                /* nativeBlasAvailable */ true,
                () -> {
                  throw new IllegalStateException("boom");
                }));
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
  void validateScoreInputsRejectsQueryLengthMismatch() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            MatrixDotProductScorers.validateScoreInputs(
                TestDenseMatrices.of(new float[] {1f, 2f}, 1, 2), new float[] {1f}, new float[] {0f}));
  }

  @Test
  void validateScoreInputsRejectsDotProductsLengthMismatch() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            MatrixDotProductScorers.validateScoreInputs(
                TestDenseMatrices.of(new float[] {1f, 2f}, 1, 2), new float[] {1f, 2f}, new float[] {0f, 0f}));
  }

  @Test
  void validateScoreInputsAcceptsConsistentDimensions() {
    MatrixDotProductScorers.validateScoreInputs(
                TestDenseMatrices.of(new float[] {1f, 2f}, 1, 2), new float[] {1f, 2f}, new float[] {0f});
  }
}
