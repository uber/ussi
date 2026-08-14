/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.utils;

import org.apache.commons.math3.distribution.NormalDistribution;

public final class MathUtils {
  public static final double EPSILON = 1e-12;
  private static final long SPLITTABLE_GAMMA = 0x9e3779b97f4a7c15L;
  private static final double DOUBLE_UNIT = 0x1.0p-53;

  private MathUtils() {}

  /** Mixes a primitive seed without allocating a pseudo-random number generator. */
  public static long mix64(long value) {
    value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
    value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
    return value ^ (value >>> 31);
  }

  /** Deterministically maps a seed and draw index to a pseudo-random long. */
  public static long seedToLong(long seed, int drawIndex) {
    return mix64(seed + SPLITTABLE_GAMMA * (drawIndex + 1L));
  }

  /** Deterministically maps a seed and draw index to a uniform value in (0, 1). */
  public static double seedToUniform01(long seed, int drawIndex) {
    long bits = seedToLong(seed, drawIndex);
    return ((bits >>> 11) + 0.5) * DOUBLE_UNIT;
  }

  /** Deterministically maps a seed and two consecutive draws to Gamma(2, 1). */
  public static double seedToGamma21(long seed, int firstDrawIndex) {
    double first = seedToUniform01(seed, firstDrawIndex);
    double second = seedToUniform01(seed, firstDrawIndex + 1);
    return -Math.log(first * second);
  }

  /** Returns the Neumaier compensated sum of the supplied values. */
  public static double stableSum(double... values) {
    StableSumAccumulator accumulator = new StableSumAccumulator();
    for (double value : values) {
      accumulator.add(value);
    }
    return accumulator.getSum();
  }

  /** Incrementally computes a Neumaier compensated sum. */
  public static final class StableSumAccumulator {
    private double sum;
    private double compensation;

    public void add(double value) {
      double nextSum = sum + value;
      if (Math.abs(sum) >= Math.abs(value)) {
        compensation += (sum - nextSum) + value;
      } else {
        compensation += (value - nextSum) + sum;
      }
      sum = nextSum;
    }

    public double getSum() {
      return sum + compensation;
    }
  }

  /**
   * Computes one-sided confidence intervals for proportions.
   *
   * <p>The equivalent two-sided confidence is {@code 2 * confidence - 1}.
   */
  public static class ProportionConfidenceInterval1Sided {

    private final double kAlphaAtConfidence;

    public ProportionConfidenceInterval1Sided(double confidence) {
      if (confidence < 0.5 || confidence > 1.0) {
        throw new IllegalArgumentException(
            String.format(
                "Illegal confidence (%s). "
                    + "The confidence of a single-sided interval should be in the range [0.5, 1].",
                confidence));
      }
      if (confidence == 1.0) {
        this.kAlphaAtConfidence = 1.0 / EPSILON;
      } else {
        this.kAlphaAtConfidence = new NormalDistribution().inverseCumulativeProbability(confidence);
      }
    }

    private void validateNumTrialsAndNumSuccesses(int numTrials, int numSuccesses) {
      if (numTrials <= 0 || numSuccesses < 0 || numSuccesses > numTrials) {
        throw new IllegalArgumentException(
            String.format(
                "Illegal numTrials (%s) and/or numSuccesses (%s). "
                    + "The numTrials should be greater than 0. "
                    + "The numSuccesses should be at least 0 and should not exceed numTrials.",
                numTrials, numSuccesses));
      }
    }

    /**
     * Returns the lower-bound on the one-sided confidence interval of the probability of success.
     * The upper bound is always 1.0.
     */
    public double getConfidenceIntervalLowerBound(int numTrials, int numSuccesses) {
      validateNumTrialsAndNumSuccesses(numTrials, numSuccesses);
      double pHat = numSuccesses * 1.0 / numTrials;
      double errorMargin = kAlphaAtConfidence * Math.sqrt(pHat * (1 - pHat) / numTrials);
      return Math.max(0.0, pHat - errorMargin);
    }

    /**
     * Returns the upper-bound on the one-sided confidence interval of the probability of success.
     * The lower bound is always 0.0.
     */
    public double getConfidenceIntervalUpperBound(int numTrials, int numSuccesses) {
      validateNumTrialsAndNumSuccesses(numTrials, numSuccesses);
      double pHat = numSuccesses * 1.0 / numTrials;
      double errorMargin = kAlphaAtConfidence * Math.sqrt(pHat * (1 - pHat) / numTrials);
      return Math.min(1.0, pHat + errorMargin);
    }
  }
}
