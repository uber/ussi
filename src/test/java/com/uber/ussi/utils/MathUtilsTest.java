package com.uber.ussi.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MathUtilsTest {
  private static final double DELTA = 1e-9;

  @Test
  void stableSumPreservesValuesLostByNaiveSummation() {
    assertEquals(1.0, MathUtils.stableSum(1e16, 1.0, -1e16), 0.0);
  }

  @Test
  void stableSumAccumulatorSupportsIncrementalSummation() {
    MathUtils.StableSumAccumulator accumulator = new MathUtils.StableSumAccumulator();

    accumulator.add(1e16);
    accumulator.add(1.0);
    accumulator.add(-1e16);

    assertEquals(1.0, accumulator.getSum(), 0.0);
  }

  @Test
  void stableSumOfNoValuesIsZero() {
    assertEquals(0.0, MathUtils.stableSum(), 0.0);
  }

  @Test
  void seedOnlyRandomValuesAreDeterministicAndUseIndependentDraws() {
    long seed = MathUtils.mix64(123L);
    long firstLong = MathUtils.seedToLong(seed, 0);
    long secondLong = MathUtils.seedToLong(seed, 1);
    double first = MathUtils.seedToUniform01(seed, 0);
    double second = MathUtils.seedToUniform01(seed, 1);

    assertEquals(firstLong, MathUtils.seedToLong(seed, 0));
    assertTrue(firstLong != secondLong);
    assertEquals(first, MathUtils.seedToUniform01(seed, 0), 0.0);
    assertTrue(first > 0.0 && first < 1.0);
    assertTrue(second > 0.0 && second < 1.0);
    assertTrue(first != second);
    assertEquals(-Math.log(first * second), MathUtils.seedToGamma21(seed, 0), Double.MIN_VALUE);
  }

  @Test
  void confidenceIntervalDegeneratesToObservedProportionAtMinimumConfidence() {
    MathUtils.ProportionConfidenceInterval1Sided interval =
        new MathUtils.ProportionConfidenceInterval1Sided(0.5);

    assertEquals(0.75, interval.getConfidenceIntervalLowerBound(4, 3), DELTA);
    assertEquals(0.75, interval.getConfidenceIntervalUpperBound(4, 3), DELTA);
  }

  @Test
  void confidenceIntervalMatchesReferenceValuesAt95PercentConfidence() {
    MathUtils.ProportionConfidenceInterval1Sided interval =
        new MathUtils.ProportionConfidenceInterval1Sided(0.95);

    assertEquals(0.8506543911914559, interval.getConfidenceIntervalLowerBound(100, 90), DELTA);
    assertEquals(0.9493456088085441, interval.getConfidenceIntervalUpperBound(100, 90), DELTA);
    assertEquals(0.3699629030311107, interval.getConfidenceIntervalLowerBound(40, 20), DELTA);
    assertEquals(0.6300370969688893, interval.getConfidenceIntervalUpperBound(40, 20), DELTA);
  }

  @Test
  void confidenceIntervalBoundsAreClampedToTheUnitInterval() {
    MathUtils.ProportionConfidenceInterval1Sided interval =
        new MathUtils.ProportionConfidenceInterval1Sided(0.95);

    assertEquals(0.0, interval.getConfidenceIntervalLowerBound(4, 1), DELTA);
    assertEquals(1.0, interval.getConfidenceIntervalUpperBound(4, 3), DELTA);
  }

  @Test
  void confidenceIntervalHasZeroWidthAtExtremeProportions() {
    MathUtils.ProportionConfidenceInterval1Sided interval =
        new MathUtils.ProportionConfidenceInterval1Sided(0.95);

    assertEquals(0.0, interval.getConfidenceIntervalLowerBound(10, 0), DELTA);
    assertEquals(0.0, interval.getConfidenceIntervalUpperBound(10, 0), DELTA);
    assertEquals(1.0, interval.getConfidenceIntervalLowerBound(10, 10), DELTA);
    assertEquals(1.0, interval.getConfidenceIntervalUpperBound(10, 10), DELTA);
  }

  @Test
  void confidenceIntervalCoversTheFullUnitIntervalAtFullConfidence() {
    MathUtils.ProportionConfidenceInterval1Sided interval =
        new MathUtils.ProportionConfidenceInterval1Sided(1.0);

    assertEquals(0.0, interval.getConfidenceIntervalLowerBound(4, 3), DELTA);
    assertEquals(1.0, interval.getConfidenceIntervalUpperBound(4, 3), DELTA);
  }

  @Test
  void confidenceIntervalRejectsConfidencesOutsideTheSupportedRange() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new MathUtils.ProportionConfidenceInterval1Sided(0.4));
    assertThrows(
        IllegalArgumentException.class,
        () -> new MathUtils.ProportionConfidenceInterval1Sided(1.1));
  }

  @Test
  void confidenceIntervalRejectsInvalidTrialsAndSuccesses() {
    MathUtils.ProportionConfidenceInterval1Sided interval =
        new MathUtils.ProportionConfidenceInterval1Sided(0.95);

    assertThrows(
        IllegalArgumentException.class, () -> interval.getConfidenceIntervalUpperBound(0, 0));
    assertThrows(
        IllegalArgumentException.class, () -> interval.getConfidenceIntervalUpperBound(4, -1));
    assertThrows(
        IllegalArgumentException.class, () -> interval.getConfidenceIntervalUpperBound(4, 5));
    assertThrows(
        IllegalArgumentException.class, () -> interval.getConfidenceIntervalLowerBound(0, 0));
  }
}
