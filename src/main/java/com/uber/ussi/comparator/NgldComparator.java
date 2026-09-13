/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.comparator;

import com.uber.ussi.comparator.sequencedistance.SequenceDistance;
import com.uber.ussi.comparatornormalizer.ComparatorNormalizer;
import com.uber.ussi.utils.MathUtils;

/**
 * The normalized generalized Levenshtein distance (NGLD) between two sequences.
 *
 * <p>NGLD is the generalized Levenshtein distance {@link GldComparator} reports, divided by the
 * two sequences' lengths. It measures the same edits, over whichever {@link SequenceDistance} the
 * namespace configures, and differs only in expressing them as a fraction rather than a count.
 *
 * <p>The comparator value is {@code 2 * distance / (length1 + length2 + distance)}, which is 0.0
 * for identical sequences and 1.0 for maximally different ones. Dividing by the lengths is what
 * makes the value comparable across sequences of different lengths, unlike the edit count {@link
 * GldComparator} reports. Pair this with the {@code complement} normalizer, which maps the
 * distance onto the similarity {@code 1 - distance}.
 *
 * <p>Normalizing needs both sequences' lengths, which is why this cannot be expressed as a {@link
 * ComparatorNormalizer}: that interface converts a lone comparator value.
 */
public class NgldComparator extends BaseSequenceComparator {

  /** The largest normalized distance there is, reached when every element has to be edited. */
  private static final double MAX_NORMALIZED_DISTANCE = 1.0;

  NgldComparator(ComparatorNormalizer comparatorNormalizer, SequenceDistance sequenceDistance) {
    super(comparatorNormalizer, sequenceDistance);
  }

  /**
   * Returns the normalized distance in [0.0, 1.0] for a raw distance over the two given lengths.
   * Two empty sequences are identical, so they normalize to 0.0 rather than dividing by zero.
   */
  static double getNormalizedDistance(long distance, int length1, int length2) {
    double denominator = (double) length1 + (double) length2 + (double) distance;
    return denominator == 0.0 ? 0.0 : 2.0 * (double) distance / denominator;
  }

  /**
   * Returns the largest raw distance whose normalized distance still clears {@code
   * maxNormalizedDistance}, the inverse of {@link #getNormalizedDistance}. The budget has to be a
   * normalized distance, in [0.0, 1.0], since only those have an inverse.
   *
   * <p>Every supported distance is integer-valued, so the real-valued budget is floored to an
   * integer band radius. The epsilon keeps a budget that should land exactly on an integer from
   * being floored down by representation error, which would reject a genuine match. It is an
   * absolute tolerance on a budget that grows with the sequences, so it stops covering that error
   * once the combined length reaches the low tens of thousands of elements.
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
   * A normalized distance can never exceed {@link #MAX_NORMALIZED_DISTANCE}, so that is the only
   * sound stand-in for one that overran a budget, which is itself normalized.
   */
  @Override
  protected double getExceededComparatorValue(double comparatorValue) {
    return MAX_NORMALIZED_DISTANCE;
  }

  /**
   * A query within {@code d} edits of a candidate has at most {@code l1BoundFactor * d} of its own
   * elements unmatched by that candidate, disregarding order. Substituting the largest {@code d}
   * this threshold allows turns that into a fraction of the query's length that is the same for
   * every candidate, because both the budget and the bound scale with the lengths involved. Only a
   * prefix that long has to generate candidates.
   */
  @Override
  protected double getMinPrefixSumForTermsAndValuesInternal(
      double uniValue, double comparatorValue) {
    double boundedValue = getBoundedComparatorValue(comparatorValue);
    double unmatchedFraction =
        getSequenceDistance().getL1BoundFactor() * boundedValue / (2.0 - boundedValue);
    return Math.min(uniValue, uniValue * 2.0 * unmatchedFraction / (1.0 + unmatchedFraction));
  }

  /**
   * Caps a distance budget at the largest normalized distance there is.
   *
   * <p>Normalizers are free to map a similarity of zero to an unbounded distance, which suits the
   * comparators whose value is unbounded but is outside the range a normalized distance can take.
   * Any budget at or above the maximum admits every pair, so capping changes no result, and it
   * keeps the conversions above from being handed a value they have no inverse for.
   */
  private static double getBoundedComparatorValue(double comparatorValue) {
    if (comparatorValue < 0.0) {
      throw new IllegalArgumentException("comparatorValue must be at least 0.0.");
    }
    return Math.min(comparatorValue, MAX_NORMALIZED_DISTANCE);
  }
}
