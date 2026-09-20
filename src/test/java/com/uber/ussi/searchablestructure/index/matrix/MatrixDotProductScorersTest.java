package com.uber.ussi.searchablestructure.index.matrix;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.uber.ussi.searchablestructure.result.RowNumAndSimilarity;
import java.util.List;
import org.junit.jupiter.api.Test;

class MatrixDotProductScorersTest {
  private static final float DELTA = 1e-6f;
  private static final RowSelection SELECTION =
      new RowSelection(0, Float.NEGATIVE_INFINITY, 10);

  private static final int MAX_NUM_SIMILARITIES = 10;

  @Test
  void createReturnsAUsableScorer() {
    DenseMatrix matrix = TestDenseMatrices.of(new float[] {1f, 2f, 3f, 4f}, 2, 2);
    try (MatrixDotProductScorer scorer =
        MatrixDotProductScorers.create(matrix, TestMatrixRows.of(2), MAX_NUM_SIMILARITIES)) {

      List<RowNumAndSimilarity> kept = scorer.selectRows(new float[] {0.5f, 2f}, SELECTION);

      assertEquals(2, kept.size());
      List<Float> similarities =
          kept.stream().map(RowNumAndSimilarity::getSimilarity).sorted().toList();
      assertEquals(4.5f, similarities.get(0), DELTA);
      assertEquals(9.5f, similarities.get(1), DELTA);
    }
  }

  @Test
  void createTakesTheFirstAvailableScorerInPreferenceOrder() {
    MatrixDotProductScorer preferred = new NoScorer();

    try (MatrixDotProductScorer scorer = createWith(provider(true, preferred), provider(true, new NoScorer()))) {
      assertSame(preferred, scorer);
    }
  }

  @Test
  void createSkipsAScorerThatIsUnavailable() {
    MatrixDotProductScorer next = new NoScorer();

    try (MatrixDotProductScorer scorer =
        createWith(
            provider(
                false,
                () -> {
                  throw new AssertionError("an unavailable scorer must not be built.");
                }),
            provider(true, next))) {
      assertSame(next, scorer);
    }
  }

  @Test
  void createSkipsAScorerWhoseNativeCodeFailsToLoad() {
    MatrixDotProductScorer next = new NoScorer();

    try (MatrixDotProductScorer first =
        createWith(
            provider(
                true,
                () -> {
                  throw new UnsatisfiedLinkError("native missing");
                }),
            provider(true, next))) {
      assertSame(next, first);
    }
    try (MatrixDotProductScorer wrapped =
        createWith(
            provider(
                true,
                () -> {
                  throw new RuntimeException(new UnsatisfiedLinkError("native missing"));
                }),
            provider(true, next))) {
      assertSame(next, wrapped);
    }
  }

  @Test
  void createRethrowsAFailureThatIsNotAMissingLibrary() {
    assertThrows(
        IllegalStateException.class,
        () ->
            createWith(
                provider(
                    true,
                    () -> {
                      throw new IllegalStateException("boom");
                    })));
  }

  @Test
  void createRejectsAnEmptyPreferenceOrder() {
    assertThrows(IllegalStateException.class, this::createWith);
  }

  /** The default order ends with a scorer needing no native code, so it always builds one. */
  @Test
  void theDefaultOrderAlwaysBuildsAScorer() {
    DenseMatrix matrix = TestDenseMatrices.of(new float[] {1f, 2f}, 1, 2);
    try (MatrixDotProductScorer scorer =
        MatrixDotProductScorers.create(matrix, TestMatrixRows.of(1), MAX_NUM_SIMILARITIES)) {
      assertInstanceOf(MatrixDotProductScorer.class, scorer);
    }
  }

  @Test
  void validateQueryLengthRejectsAQueryOfTheWrongLength() {
    DenseMatrix matrix = TestDenseMatrices.of(new float[] {1f, 2f}, 1, 2);

    assertThrows(
        IllegalArgumentException.class,
        () -> MatrixDotProductScorers.validateQueryLength(matrix, new float[] {1f}));
    MatrixDotProductScorers.validateQueryLength(matrix, new float[] {1f, 2f});
  }

  private MatrixDotProductScorer createWith(MatrixDotProductScorers.Provider... providers) {
    return MatrixDotProductScorers.create(
        TestDenseMatrices.of(new float[] {1f, 2f}, 1, 2), TestMatrixRows.of(1),
        MAX_NUM_SIMILARITIES, List.of(providers));
  }

  private static MatrixDotProductScorers.Provider provider(
      boolean available, MatrixDotProductScorer scorer) {
    return provider(available, () -> scorer);
  }

  private static MatrixDotProductScorers.Provider provider(
      boolean available, java.util.function.Supplier<MatrixDotProductScorer> scorer) {
    return new MatrixDotProductScorers.Provider() {
      @Override
      public boolean isAvailable() {
        return available;
      }

      @Override
      public MatrixDotProductScorer create(
          DenseMatrix matrix, MatrixRows rows, int maxNumSimilarities) {
        return scorer.get();
      }
    };
  }

  /** A scorer that is only ever identified, never used. */
  private static final class NoScorer implements MatrixDotProductScorer {
    @Override
    public List<RowNumAndSimilarity> selectRows(float[] queryValues, RowSelection selection) {
      return List.of();
    }
  }
}
