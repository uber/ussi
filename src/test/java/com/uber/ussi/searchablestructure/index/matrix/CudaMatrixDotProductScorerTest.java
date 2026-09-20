package com.uber.ussi.searchablestructure.index.matrix;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.uber.ussi.searchablestructure.result.RowNumAndSimilarity;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The device scorer against a scorer that needs no device, on the same rows and the same query.
 *
 * <p>Skipped wherever no device is present, which is every machine this project has, so the
 * device scorer is unverified until this runs somewhere it is not skipped.
 */
class CudaMatrixDotProductScorerTest {
  private static final float DELTA = 1e-3f;
  private static final int NUM_ROWS = 5_000;
  private static final int DIMENSION = 64;
  private static final int MAX_RESULTS = 32;

  @Test
  void keepsTheSameRowsAsAScorerThatNeedsNoDevice() {
    assumeTrue(CudaMatrixDotProductScorer.isAvailable(), "no device on this machine");
    Random random = new Random(19);
    float[] values = new float[NUM_ROWS * DIMENSION];
    for (int value = 0; value < values.length; ++value) {
      values[value] = random.nextFloat();
    }
    DenseMatrix matrix = TestDenseMatrices.of(values, NUM_ROWS, DIMENSION);
    MatrixRows rows = TestMatrixRows.of(NUM_ROWS);
    float[] query = new float[DIMENSION];
    for (int column = 0; column < DIMENSION; ++column) {
      query[column] = random.nextFloat();
    }
    RowSelection selection = new RowSelection(0, Float.NEGATIVE_INFINITY, MAX_RESULTS);

    List<RowNumAndSimilarity> expected;
    try (MatrixDotProductScorer withoutDevice = new JavaMatrixDotProductScorer(matrix, rows)) {
      expected = withoutDevice.selectRows(query, selection);
    }
    List<RowNumAndSimilarity> actual;
    try (MatrixDotProductScorer onDevice =
        new CudaMatrixDotProductScorer(matrix, rows, /* maxNumQueriesInABatch */ 4, MAX_RESULTS)) {
      actual = onDevice.selectRows(query, selection);
    }

    assertEquals(sortedRowNums(expected), sortedRowNums(actual));
    for (int row = 0; row < expected.size(); ++row) {
      assertEquals(
          expected.get(row).getSimilarity(), actual.get(row).getSimilarity(), DELTA,
          "row " + row + " scored differently");
    }
  }

  private static List<Long> sortedRowNums(List<RowNumAndSimilarity> rows) {
    return rows.stream().map(RowNumAndSimilarity::getRowNum).sorted().toList();
  }
}
