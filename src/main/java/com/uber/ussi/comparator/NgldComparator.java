/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.comparator;

import com.uber.ussi.comparator.sequencedistance.SequenceDistance;
import com.uber.ussi.comparatornormalizer.ComparatorNormalizer;
import com.uber.ussi.utils.MathUtils;

/**
 * The normalized generalized Levenshtein distance (NGLD) between two sequences: the edit count
 * {@link GldComparator} reports, over the same composed {@link SequenceDistance}, divided by the
 * sequences' lengths.
 *
 * <p>The comparator value is {@code 2 * distance / (length1 + length2 + distance)}, 0.0 for
 * identical sequences and 1.0 for maximally different ones, and unlike an edit count it is
 * comparable across lengths. Pair it with the {@code complement} normalizer.
 *
 * <p>Normalizing needs both sequences' lengths, which is why this cannot be expressed as a {@link
 * ComparatorNormalizer}: that interface converts a lone comparator value.
 */
public class NgldComparator extends BaseSequenceComparator {

  /** The largest normalized distance there is, reached when every term has to be edited. */
  private static final double MAX_NORMALIZED_DISTANCE = 1.0;

  NgldComparator(
      ComparatorNormalizer comparatorNormalizer, SequenceDistance sequenceDistance) {
    super(comparatorNormalizer, sequenceDistance);
  }

  /**
   * Returns the normalized distance in [0.0, 1.0]. Two empty sequences are identical, so they
   * normalize to 0.0 rather than dividing by zero.
   */
  static double getNormalizedDistance(long distance, int length1, int length2) {
    double denominator = (double) length1 + (double) length2 + (double) distance;
    return denominator == 0.0 ? 0.0 : 2.0 * (double) distance / denominator;
  }

  /**
   * Returns the largest raw distance whose normalized distance still clears {@code
   * maxNormalizedDistance}, the inverse of {@link #getNormalizedDistance getNormalizedDistance()}.
   * The budget has to be in [0.0, 1.0], since only those have an inverse.
   *
   * <p>Distances are integer-valued, so the budget is floored. The epsilon keeps one that should
   * land exactly on an integer from being floored down by representation error. That tolerance is
   * absolute on a budget that grows with the sequences, so it stops covering the error once the
   * combined length reaches the low tens of thousands of terms.
   */
  static long getDenormalizedMaxDistance(double maxNormalizedDistance, int length1, int length2) {
    double totalLength = (double) length1 + (double) length2;
    double budget = maxNormalizedDistance * totalLength / (2.0 - maxNormalizedDistance);
    return (long) Math.floor(budget + MathUtils.EPSILON_12);
  }

  @Override
  protected long getMaxDistance(double comparatorValue, int length1, int length2) {
    return getDenormalizedMaxDistance(getBoundedComparatorValue(comparatorValue), length1, length2);
  }

  @Override
  protected double getComparatorValue(long distance, int length1, int length2) {
    return getNormalizedDistance(distance, length1, length2);
  }

  /**
   * A normalized distance cannot exceed {@link #MAX_NORMALIZED_DISTANCE}, so that is the only
   * sound stand-in for one that overran a budget.
   */
  @Override
  protected double getExceededComparatorValue(double comparatorValue) {
    return MAX_NORMALIZED_DISTANCE;
  }

  /**
   * A normalized distance is a share of the sequences' combined length rather than a count of
   * anything, so the prefix takes the share shape, over the same shared-key fraction that bounds
   * this measure's signatures.
   */
  @Override
  protected double getMaxPrefixSumForTermsAndValuesInternal(
      double uniValue, double comparatorValue) {
    return maxPrefixSumFromSharedFraction(
        uniValue, getMinSharedKeyFraction(uniValue, comparatorValue));
  }

  /**
   * A normalized distance is already a share of the sequences' combined length, so the minimum
   * similarity gives one directly and the record's own length says nothing extra. Inverting the
   * normalization turns the minimum similarity into {@code d / totalLength}, which the L1 bound
   * scales.
   */
  @Override
  protected double getMaxUnmatchedFraction(double recordUniValue, double comparatorValue) {
    double boundedValue = getBoundedComparatorValue(comparatorValue);
    return getSequenceDistance().getL1BoundFactor() * boundedValue / (2.0 - boundedValue);
  }

  /**
   * Caps a distance budget at {@link #MAX_NORMALIZED_DISTANCE}. A normalizer may map zero
   * similarity to an unbounded distance, which lies outside the range a normalized distance can
   * take. Any budget at or above the maximum admits every pair, so capping changes no result and
   * keeps the conversions above from being given a value they have no inverse for.
   */
  private static double getBoundedComparatorValue(double comparatorValue) {
    if (comparatorValue < 0.0) {
      throw new IllegalArgumentException("comparatorValue must be at least 0.0.");
    }
    return Math.min(comparatorValue, MAX_NORMALIZED_DISTANCE);
  }
}
