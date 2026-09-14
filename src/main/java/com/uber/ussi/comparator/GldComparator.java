/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.comparator;

import com.uber.ussi.comparator.sequencedistance.SequenceDistance;
import com.uber.ussi.comparatornormalizer.ComparatorNormalizer;
import com.uber.ussi.utils.MathUtils;

/**
 * The number of single-element edits that turn one sequence into the other, the raw generalized
 * Levenshtein distance (GLD). It is generalized in that the composed {@link SequenceDistance}
 * decides which edits count, so the same comparator measures a Levenshtein, Damerau-Levenshtein,
 * or longest-common-subsequence distance.
 *
 * <p>The comparator value is the edit count itself, so it is unbounded above and not comparable
 * across sequence lengths. Pair it with the {@code reciprocal} normalizer, or use {@link
 * NgldComparator} for a value that is comparable across lengths.
 */
public class GldComparator extends BaseSequenceComparator {

  GldComparator(ComparatorNormalizer comparatorNormalizer, SequenceDistance sequenceDistance) {
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
   * SequenceDistance#getL1BoundFactor()} {@code * d} of its own elements unmatched by that
   * candidate, disregarding order, so only a prefix that long has to generate candidates.
   */
  @Override
  protected double getMinPrefixSumForTermsAndValuesInternal(
      double uniValue, double comparatorValue) {
    if (comparatorValue < 0.0) {
      throw new IllegalArgumentException("comparatorValue must be at least 0.0.");
    }
    return Math.min(uniValue, getSequenceDistance().getL1BoundFactor() * comparatorValue);
  }
}
