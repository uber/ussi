package com.uber.ussi.comparator;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.uber.ussi.comparatornormalizer.ComparatorNormalizer;
import com.uber.ussi.comparatornormalizer.ReciprocalComparatorNormalizer;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValuesTestFactory;
import com.uber.ussi.error.ArraysSizeMismatchError;
import com.uber.ussi.utils.MathUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class L2ComparatorTest {

  private ComparatorNormalizer comparatorNormalizer;
  private L2Comparator comparator;

  @BeforeEach
  void setUp() {
    comparatorNormalizer = new ReciprocalComparatorNormalizer();
    comparator = new L2Comparator(comparatorNormalizer);
  }

  private static LongTermsAndValues denseVector(float[] values, double uniValue) {
    return LongTermsAndValuesTestFactory.create(new long[0], values, uniValue);
  }

  private LongTermsAndValues sparseVector(long[] terms, float[] values) {
    return LongTermsAndValuesTestFactory.create(terms, values, comparator.computeUniValue(values));
  }

  @Test
  void mayPassPositionFilteringInternalRejectsInvalidPartialUni1() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            L2Comparator.mayPassPositionFilteringInternal(
                /* partialConj */ 0.0,
                /* partialUni1 */ 1.0,
                /* uni1 */ 0.0,
                /* partialUni2 */ 0.0,
                /* uni2 */ 0.0,
                /* maxSquaredL2Distance */ 1.0));
  }

  @Test
  void mayPassPositionFilteringInternalRejectsInvalidPartialUni2() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            L2Comparator.mayPassPositionFilteringInternal(
                /* partialConj */ 0.0,
                /* partialUni1 */ 0.0,
                /* uni1 */ 0.0,
                /* partialUni2 */ 1.0,
                /* uni2 */ 0.0,
                /* maxSquaredL2Distance */ 1.0));
  }

  @Test
  void mayPassLengthFilteringInternal() {
    Assertions.assertTrue(
        L2Comparator.mayPassLengthFilteringInternal(
            /* uni1 */ 25.0, /* uni2 */ 9.0, /* maxSquaredL2Distance */ 4.0));
    Assertions.assertFalse(
        L2Comparator.mayPassLengthFilteringInternal(
            /* uni1 */ 25.0, /* uni2 */ 9.0, /* maxSquaredL2Distance */ 3.9));
    Assertions.assertTrue(
        L2Comparator.mayPassLengthFilteringInternal(
            /* uni1 */ 1.0, /* uni2 */ 1.0, /* maxSquaredL2Distance */ 4.0));
  }

  @Test
  void mayPassPositionFilteringInternal() {
    Assertions.assertTrue(
        L2Comparator.mayPassPositionFilteringInternal(
            /* partialConj */ 8.0,
            /* partialUni1 */ 5.0,
            /* uni1 */ 10.0,
            /* partialUni2 */ 5.0,
            /* uni2 */ 10.0,
            /* maxSquaredL2Distance */ 4.0));
    Assertions.assertTrue(
        L2Comparator.mayPassPositionFilteringInternal(
            /* partialConj */ 7.0,
            /* partialUni1 */ 5.0,
            /* uni1 */ 10.0,
            /* partialUni2 */ 5.0,
            /* uni2 */ 10.0,
            /* maxSquaredL2Distance */ 4.0));
    Assertions.assertFalse(
        L2Comparator.mayPassPositionFilteringInternal(
            /* partialConj */ 0.0,
            /* partialUni1 */ 5.0,
            /* uni1 */ 10.0,
            /* partialUni2 */ 5.0,
            /* uni2 */ 10.0,
            /* maxSquaredL2Distance */ 4.0));
  }

  @Test
  void compare() throws ArraysSizeMismatchError {
    LongTermsAndValues termsAndValues1 = denseVector(new float[] {0f, 0f}, 0.0);
    LongTermsAndValues termsAndValues2 = denseVector(new float[] {0f, 0f}, 0.0);
    double distance1 =
        comparator.compare(termsAndValues1, termsAndValues2, /* minSimilarity */ 0.0);
    double distance2 =
        comparator.compare(termsAndValues2, termsAndValues1, /* minSimilarity */ 0.0);
    Assertions.assertEquals(/* expected */ 0.0, distance1, /* delta */ 0.0);
    Assertions.assertEquals(distance1, distance2, /* delta */ 0.0);

    termsAndValues1 = denseVector(new float[] {3f, 4f}, 25.0);
    termsAndValues2 = denseVector(new float[] {0f, 0f}, 0.0);
    distance1 = comparator.compare(termsAndValues1, termsAndValues2, /* minSimilarity */ 0.0);
    distance2 = comparator.compare(termsAndValues2, termsAndValues1, /* minSimilarity */ 0.0);
    Assertions.assertEquals(/* expected */ 5.0, distance1, /* delta */ 0.0);
    Assertions.assertEquals(distance1, distance2, /* delta */ 0.0);

    termsAndValues1 = denseVector(new float[] {3f, 0f}, 9.0);
    termsAndValues2 = denseVector(new float[] {0f, 4f}, 16.0);
    distance1 = comparator.compare(termsAndValues1, termsAndValues2, /* minSimilarity */ 0.0);
    distance2 = comparator.compare(termsAndValues2, termsAndValues1, /* minSimilarity */ 0.0);
    Assertions.assertEquals(/* expected */ 5.0, distance1, /* delta */ 0.0);
    Assertions.assertEquals(distance1, distance2, /* delta */ 0.0);

    termsAndValues1 = denseVector(new float[] {3f, 0f}, 9.0);
    termsAndValues2 = denseVector(new float[] {0f, -4f}, 16.0);
    distance1 = comparator.compare(termsAndValues1, termsAndValues2, /* minSimilarity */ 0.0);
    distance2 = comparator.compare(termsAndValues2, termsAndValues1, /* minSimilarity */ 0.0);
    Assertions.assertEquals(/* expected */ 5.0, distance1, /* delta */ 0.0);
    Assertions.assertEquals(distance1, distance2, /* delta */ 0.0);

    termsAndValues1 = denseVector(new float[] {-3f, 0f}, 9.0);
    termsAndValues2 = denseVector(new float[] {0f, -4f}, 16.0);
    distance1 = comparator.compare(termsAndValues1, termsAndValues2, /* minSimilarity */ 0.0);
    distance2 = comparator.compare(termsAndValues2, termsAndValues1, /* minSimilarity */ 0.0);
    Assertions.assertEquals(/* expected */ 5.0, distance1, /* delta */ 0.0);
    Assertions.assertEquals(distance1, distance2, /* delta */ 0.0);
  }

  @Test
  void compareAlignsSparseValuesByTerm() {
    LongTermsAndValues first = sparseVector(new long[] {1L, 3L}, new float[] {3f, 4f});
    LongTermsAndValues second = sparseVector(new long[] {2L, 3L}, new float[] {4f, 4f});

    Assertions.assertEquals(5.0, comparator.compare(first, second, 0.0), 0.0);
    Assertions.assertEquals(5.0, comparator.compare(second, first, 0.0), 0.0);
  }

  @Test
  void disjointSparseVectorsCanHaveNonZeroNormalizedSimilarity() {
    LongTermsAndValues first = sparseVector(new long[] {1L}, new float[] {1f});
    LongTermsAndValues second = sparseVector(new long[] {2L}, new float[] {1f});

    Assertions.assertEquals(
        1.0 / (1.0 + Math.sqrt(2.0)), comparator.getSimilarity(first, second, 0.0), 1e-12);
  }

  @Test
  void exactPrefixSumUsesSquaredMaximumDistance() {
    Assertions.assertEquals(
        4.0 + MathUtils.EPSILON,
        comparator.getMinPrefixSumForTermsAndValues(
            25.0, comparatorNormalizer.comparatorValueToNormalizedSimilarityValue(2.0)),
        0.0);
  }

  private void testGetDistanceWithEarlyStopInternal(
      LongTermsAndValues termsAndValues1, LongTermsAndValues termsAndValues2, double distance)
      throws ArraysSizeMismatchError {
    double distance1 =
        comparator.compare(
            termsAndValues1,
            termsAndValues2,
            comparatorNormalizer.comparatorValueToNormalizedSimilarityValue(
                distance - MathUtils.EPSILON));
    double distance2 =
        comparator.compare(
            termsAndValues2,
            termsAndValues1,
            comparatorNormalizer.comparatorValueToNormalizedSimilarityValue(
                distance - MathUtils.EPSILON));
    Assertions.assertEquals(
        comparatorNormalizer.normalizedSimilarityValueToComparatorValue(0.0),
        distance1,
        /* delta */ 0.0);
    Assertions.assertEquals(distance1, distance2, /* delta */ 0.0);

    distance1 =
        comparator.compare(
            termsAndValues1,
            termsAndValues2,
            comparatorNormalizer.comparatorValueToNormalizedSimilarityValue(distance));
    distance2 =
        comparator.compare(
            termsAndValues2,
            termsAndValues1,
            comparatorNormalizer.comparatorValueToNormalizedSimilarityValue(distance));
    Assertions.assertEquals(/* expected */ distance, distance1, /* delta */ 0.0);
    Assertions.assertEquals(distance1, distance2, /* delta */ 0.0);

    double belowThresholdSimilarity =
        comparatorNormalizer.comparatorValueToNormalizedSimilarityValue(
            distance - MathUtils.EPSILON);
    distance1 =
        comparator.compareInternal(termsAndValues1, termsAndValues2, belowThresholdSimilarity);
    distance2 =
        comparator.compareInternal(termsAndValues2, termsAndValues1, belowThresholdSimilarity);
    double maxAllowedDistance =
        comparatorNormalizer.normalizedSimilarityValueToComparatorValue(belowThresholdSimilarity);
    Assertions.assertTrue(maxAllowedDistance < distance1 && distance1 <= distance);
    Assertions.assertEquals(distance1, distance2, /* delta */ 0.0);

    double forceExitSimilarity = 1.0 - MathUtils.EPSILON;
    double forceExitDistance =
        comparatorNormalizer.comparatorValueToNormalizedSimilarityValue(forceExitSimilarity);
    if (distance > forceExitDistance) {
      distance1 = comparator.compareInternal(termsAndValues1, termsAndValues2, forceExitSimilarity);
      distance2 = comparator.compareInternal(termsAndValues2, termsAndValues1, forceExitSimilarity);
      Assertions.assertTrue(MathUtils.EPSILON < distance1 && distance1 <= distance);
      Assertions.assertEquals(distance1, distance2, /* delta */ 0.0);
    }
  }

  @Test
  void getDistanceWithEarlyStop() throws ArraysSizeMismatchError {
    LongTermsAndValues termsAndValues1 = denseVector(new float[] {3f, 0f}, 9.0);
    LongTermsAndValues termsAndValues2 = denseVector(new float[] {0f, -4f}, 16.0);
    testGetDistanceWithEarlyStopInternal(termsAndValues1, termsAndValues2, /* distance */ 5.0);

    termsAndValues1 = denseVector(new float[] {1f, 0f, 4f, 0f, 8f}, 81.0);
    termsAndValues2 = denseVector(new float[] {0f, 2f, 0f, 6f, 0f}, 40.0);
    testGetDistanceWithEarlyStopInternal(termsAndValues1, termsAndValues2, /* distance */ 11.0);
  }

  @Test
  void getDistanceArraysSizeMismatch() {
    assertThrows(
        ArraysSizeMismatchError.class,
        () ->
            comparator.compare(
                denseVector(new float[] {4f}, 16.0),
                denseVector(new float[] {1f, 2f}, 5.0),
                /* minSimilarity */ 0.0));
  }

  @Test
  void computeUniValue() {
    Assertions.assertEquals(/* expected */ 0.0, comparator.computeUniValue(new float[] {}), 0.0);
    Assertions.assertEquals(/* expected */ 1.0, comparator.computeUniValue(new float[] {1f}), 0.0);
    Assertions.assertEquals(
        /* expected */ 14.0, comparator.computeUniValue(new float[] {1f, 2f, 3f}), 0.0);
  }

  @Test
  void mayPassLengthFiltering() {
    Assertions.assertTrue(
        comparator.mayPassLengthFiltering(
            /* uniValue1 */ 0.0, /* uniValue2 */ 0.0, /* minSimilarity */ 1.0));
    Assertions.assertFalse(
        comparator.mayPassLengthFiltering(
            /* uniValue1 */ 1.0, /* uniValue2 */ 0.0, /* minSimilarity */ 1.0));
    Assertions.assertFalse(
        comparator.mayPassLengthFiltering(
            /* uniValue1 */ 0.0, /* uniValue2 */ 1.0, /* minSimilarity */ 1.0));
    Assertions.assertTrue(
        comparator.mayPassLengthFiltering(
            /* uniValue1 */ 25.0,
            /* uniValue2 */ 9.0,
            comparatorNormalizer.comparatorValueToNormalizedSimilarityValue(2.0)));
    Assertions.assertTrue(
        comparator.mayPassLengthFiltering(
            /* uniValue1 */ 9.0,
            /* uniValue2 */ 25.0,
            comparatorNormalizer.comparatorValueToNormalizedSimilarityValue(2.0)));
    Assertions.assertFalse(
        comparator.mayPassLengthFiltering(
            /* uniValue1 */ 25.0,
            /* uniValue2 */ 9.0,
            comparatorNormalizer.comparatorValueToNormalizedSimilarityValue(
                2.0 - MathUtils.EPSILON)));
    Assertions.assertFalse(
        comparator.mayPassLengthFiltering(
            /* uniValue1 */ 9.0,
            /* uniValue2 */ 25.0,
            comparatorNormalizer.comparatorValueToNormalizedSimilarityValue(
                2.0 - MathUtils.EPSILON)));
  }
}
