package com.uber.ussi.searchablestructure.index.matrix;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.uber.ussi.comparator.DotProductScored;
import com.uber.ussi.searchablestructure.result.RowNumAndSimilarity;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The GPU scorer against a scorer that needs no GPU, on the same rows and the same query.
 *
 * <p>Skipped wherever no GPU is present, which is every machine this project has, so the GPU
 * scorer is unverified until this runs somewhere it is not skipped.
 */
class CudaMatrixDotProductScorerTest {
  private static final float DELTA = 1e-3f;
  private static final int NUM_ROWS = 5_000;
  private static final int DIMENSION = 64;
  private static final int MAX_RESULTS = 32;

  @Test
  void keepsTheSameRowsAsAScorerThatNeedsNoGpu() {
    assumeTrue(CudaMatrixDotProductScorer.isAvailable(), "no GPU on this machine");
    Random random = new Random(19);
    float[] values = new float[NUM_ROWS * DIMENSION];
    for (int value = 0; value < values.length; ++value) {
      values[value] = random.nextFloat();
    }
    DenseMatrix matrix = TestDenseMatrices.of(values, NUM_ROWS, DIMENSION);
    // Real unilateral values, since a squared distance taken from zeroes is negative, which
    // clamps to zero and scores every row alike.
    double[] rowUniValues = new double[NUM_ROWS];
    for (int row = 0; row < NUM_ROWS; ++row) {
      rowUniValues[row] = sumOfSquares(values, row * DIMENSION, DIMENSION);
    }
    MatrixRows rows = rowsScoredByDistance(rowUniValues);
    float[] query = new float[DIMENSION];
    for (int column = 0; column < DIMENSION; ++column) {
      query[column] = random.nextFloat();
    }
    RowSelection selection =
        new RowSelection(sumOfSquares(query, 0, DIMENSION), Float.NEGATIVE_INFINITY, MAX_RESULTS);

    List<RowNumAndSimilarity> expected;
    try (MatrixDotProductScorer withoutGpu = new JavaMatrixDotProductScorer(matrix, rows)) {
      expected = withoutGpu.selectRows(query, selection);
    }
    List<RowNumAndSimilarity> actual;
    try (MatrixDotProductScorer onGpu =
        new CudaMatrixDotProductScorer(matrix, rows, /* maxNumQueriesInABatch */ 4, MAX_RESULTS)) {
      actual = onGpu.selectRows(query, selection);
    }

    // A scorer returns what it kept in no particular order, so the rows are compared as a set
    // and each row's similarity against the similarity that same row was given.
    Map<Long, Float> expectedByRowNum = byRowNum(expected);
    Map<Long, Float> actualByRowNum = byRowNum(actual);
    assertEquals(expectedByRowNum.keySet(), actualByRowNum.keySet());
    for (Map.Entry<Long, Float> entry : expectedByRowNum.entrySet()) {
      assertEquals(
          entry.getValue(), actualByRowNum.get(entry.getKey()), DELTA,
          "row " + entry.getKey() + " scored differently");
    }
  }

  /**
   * Rows whose similarity falls with distance without being what the select ranks by, so that
   * the scorer is shown to score with the comparator rather than with its own ordering.
   */
  private static double sumOfSquares(float[] values, int from, int length) {
    double sum = 0.0;
    for (int value = from; value < from + length; ++value) {
      sum += (double) values[value] * values[value];
    }
    return sum;
  }

  private static MatrixRows rowsScoredByDistance(double[] rowUniValues) {
    long[] rowNums = new long[rowUniValues.length];
    for (int row = 0; row < rowUniValues.length; ++row) {
      rowNums[row] = row;
    }
    return new MatrixRows(
        rowNums,
        rowUniValues,
        (dotProduct, uniValue1, uniValue2) ->
            1.0 / (1.0 + Math.sqrt(Math.max(0.0, uniValue1 + uniValue2 - 2.0 * dotProduct))));
  }

  private static Map<Long, Float> byRowNum(List<RowNumAndSimilarity> rows) {
    Map<Long, Float> byRowNum = new HashMap<>();
    for (RowNumAndSimilarity row : rows) {
      byRowNum.put(row.getRowNum(), row.getSimilarity());
    }
    return byRowNum;
  }

  /** Refusing what it cannot serve needs no GPU, since both are settled before anything runs. */
  @Test
  void rejectsBeingBuiltToKeepNoRows() {
    DenseMatrix matrix = TestDenseMatrices.of(new float[] {1f, 2f}, 1, 2);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CudaMatrixDotProductScorer(
                matrix, rowsScoredByDistance(new double[1]), /* maxNumQueriesInABatch */ 1,
                /* maxResults */ 0));
  }

  @Test
  void doesComparatorOrderBySquaredDistanceAnswersForEachComparator() {
    record Case(String name, boolean expected, DotProductScored comparator) {}
    List<Case> cases =
        List.of(
            new Case(
                "falls with the distance",
                true,
                (dotProduct, uniValue1, uniValue2) ->
                    -(uniValue1 + uniValue2 - 2.0 * dotProduct)),
            new Case(
                "falls with the distance through a root",
                true,
                (dotProduct, uniValue1, uniValue2) ->
                    1.0
                        / (1.0
                            + Math.sqrt(
                                Math.max(0.0, uniValue1 + uniValue2 - 2.0 * dotProduct)))),
            new Case("the same for every row", true, (dotProduct, uniValue1, uniValue2) -> 1.0),
            new Case(
                "rises with the distance",
                false,
                (dotProduct, uniValue1, uniValue2) -> uniValue1 + uniValue2 - 2.0 * dotProduct),
            new Case(
                "reads the dot product on its own",
                false,
                (dotProduct, uniValue1, uniValue2) -> dotProduct),
            new Case(
                "not a number",
                false,
                (dotProduct, uniValue1, uniValue2) -> Double.NaN));

    for (Case each : cases) {
      assertEquals(
          each.expected(),
          CudaMatrixDotProductScorer.doesComparatorOrderBySquaredDistance(each.comparator()),
          each.name());
    }
  }
}
