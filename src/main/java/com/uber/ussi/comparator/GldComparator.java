/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.comparator;

import com.uber.ussi.comparator.sequencedistance.SequenceDistance;
import com.uber.ussi.comparatornormalizer.ComparatorNormalizer;
import com.uber.ussi.utils.MathUtils;

/**
 * The number of single-term edits that turn one sequence into the other, the raw generalized
 * Levenshtein distance (GLD). It is generalized in that the composed {@link SequenceDistance}
 * decides which edits count, so the same comparator measures a Levenshtein, Damerau-Levenshtein,
 * or longest-common-subsequence distance.
 *
 * <p>The comparator value is the edit count itself, so it is unbounded above and not comparable
 * across sequence lengths. Pair it with the {@code reciprocal} normalizer, or use {@link
 * NgldComparator} for a value that is comparable across lengths.
 */
public class GldComparator extends BaseSequenceComparator {

  GldComparator(
      ComparatorNormalizer comparatorNormalizer, SequenceDistance sequenceDistance) {
    super(comparatorNormalizer, sequenceDistance);
  }

  /**
   * The budget is already an edit count, so it only needs flooring. The epsilon keeps a budget
   * that should land exactly on an integer from being floored down by representation error.
   */
  @Override
  protected long getMaxDistance(double comparatorValue, int length1, int length2) {
    return (long) Math.floor(comparatorValue + MathUtils.EPSILON_12);
  }

  @Override
  protected double getComparatorValue(long distance, int length1, int length2) {
    return (double) distance;
  }

  /** Distances are whole numbers, so one that overran the budget is at least the next integer. */
  @Override
  protected double getExceededComparatorValue(double comparatorValue) {
    return Math.floor(comparatorValue) + 1.0;
  }

  /**
   * A query within {@code d} edits of a candidate has at most {@link
   * SequenceDistance#getL1BoundFactor()} {@code * d} of its own terms unmatched by that
   * candidate, disregarding order, so only a prefix that long has to generate candidates.
   *
   * <p>An edit count states the prefix sum directly rather than through a share, because it
   * already counts terms. Taking the share shape here would be sound but looser, since expressing
   * the budget as a share needs the shortest length a candidate may have and then scales back up
   * by a longer one.
   */
  @Override
  protected double getMaxPrefixSumForTermsAndValuesInternal(
      double uniValue, double comparatorValue) {
    if (comparatorValue < 0.0) {
      throw new IllegalArgumentException("comparatorValue must be at least 0.0.");
    }
    return Math.min(uniValue, getSequenceDistance().getL1BoundFactor() * comparatorValue);
  }

  /**
   * An edit count is a number of terms rather than a share of anything, so expressing it as one
   * takes the shortest combined length a candidate can have. Length filtering admits only
   * candidates within the budget's worth of terms of the query, so the shortest runs {@code
   * recordUniValue - comparatorValue} terms and cannot run shorter than empty.
   *
   * <p>Signature keys are why this detour is needed: over terms the budget counts the keys
   * directly and no length comes into it.
   */
  @Override
  protected double getMaxUnmatchedFraction(double recordUniValue, double comparatorValue) {
    if (comparatorValue < 0.0) {
      throw new IllegalArgumentException("comparatorValue must be at least 0.0.");
    }
    double minTotalLength = Math.max(recordUniValue, 2.0 * recordUniValue - comparatorValue);
    if (minTotalLength <= 0.0) {
      return 1.0;
    }
    return Math.min(
        1.0, getSequenceDistance().getL1BoundFactor() * comparatorValue / minTotalLength);
  }
}
