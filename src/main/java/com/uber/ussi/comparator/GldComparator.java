/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.comparator;

import com.uber.ussi.comparatornormalizer.ComparatorNormalizer;
import com.uber.ussi.sequencedistance.SequenceDistance;
import com.uber.ussi.utils.MathUtils;

/**
 * The raw generalized Levenshtein distance between two sequences, as a count of edits.
 *
 * <p>The comparator value is the edit count itself, so it is unbounded above and grows with the
 * sequences rather than with how different they are: one edit is a near match between two long
 * sequences and a wholesale rewrite between two short ones. Pair this with the {@code reciprocal}
 * normalizer, which maps an unbounded distance onto a similarity. Callers wanting a similarity that
 * is comparable across sequence lengths should use {@link NgldComparator} instead.
 */
public class GldComparator extends BaseSequenceComparator {

  GldComparator(ComparatorNormalizer comparatorNormalizer, SequenceDistance sequenceDistance) {
    super(comparatorNormalizer, sequenceDistance);
  }

  /**
   * The budget is already an edit count, so it needs no conversion, only flooring to an integer.
   * The epsilon keeps a budget that should land exactly on an integer from being floored down by
   * representation error, which would reject a genuine match.
   */
  @Override
  protected long getMaxDistance(double comparatorValue, int length1, int length2) {
    return (long) Math.floor(comparatorValue + MathUtils.EPSILON_12);
  }

  @Override
  protected double getComparatorValue(long distance, int length1, int length2) {
    return (double) distance;
  }

  /**
   * Distances are whole numbers, so a distance that overran a budget is at least the next integer
   * above it. That is both a sound and the tightest stand-in.
   */
  @Override
  protected double getExceededComparatorValue(double comparatorValue) {
    return Math.floor(comparatorValue) + 1.0;
  }

  /**
   * A query within {@code d} edits of a candidate has at most {@link
   * SequenceDistance#getL1BoundFactor()} {@code * d} of its own elements unmatched by that
   * candidate, disregarding order. So if a prefix of the query that long shares no element with a
   * candidate, the candidate is too far away, and only that prefix has to generate candidates.
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
