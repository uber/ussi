/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.comparator;

import com.uber.ussi.comparator.sequencedistance.SequenceDistance;
import com.uber.ussi.comparatornormalizer.ComparatorNormalizer;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;
import com.uber.ussi.entity.termsandvalues.RecordType;
import com.uber.ussi.error.ArraysSizeMismatchError;
import com.uber.ussi.utils.MathUtils;
import java.util.Objects;
import java.util.Set;

/**
 * Shared implementation for the comparators reporting an edit distance, with length and prefix
 * filtering. Subclasses differ only in how they report that distance, and compose the {@link
 * SequenceDistance} deciding which edits it counts.
 *
 * <p>Sequences are held differently from the other comparators' records. A sequence carries its
 * elements, in order and with repeats, in the record's terms and has no values, so {@code terms} is
 * the sequence and the record's Uni value is its length. Candidate generation instead indexes the
 * multiset of those elements as an ordinary sparse record, whose terms are the distinct elements
 * and whose values are their counts; that form sums to the same length, so both agree on the Uni
 * value.
 *
 * <p>These comparators cannot generate candidates by merging, and leave {@link
 * Comparator#conjunctionContribution} and its companions unimplemented. Merging scores a row from
 * the elements it shares with the query, which for an order-sensitive distance bounds the
 * similarity but does not determine it, since the dynamic program still has to run over the
 * ordered sequences. Sequence searches generate candidates and then verify them.
 */
abstract class BaseSequenceComparator extends Comparator {

  private final SequenceDistance sequenceDistance;

  BaseSequenceComparator(
      ComparatorNormalizer comparatorNormalizer, SequenceDistance sequenceDistance) {
    super(comparatorNormalizer);
    this.sequenceDistance = Objects.requireNonNull(sequenceDistance, "sequenceDistance is null.");
  }

  public final SequenceDistance getSequenceDistance() {
    return sequenceDistance;
  }

  /**
   * Returns the largest raw distance whose comparator value still clears {@code comparatorValue},
   * for a pair of sequences of the given lengths. A negative budget means no distance can.
   */
  protected abstract long getMaxDistance(double comparatorValue, int length1, int length2);

  /** Returns the comparator value of a raw distance between sequences of the given lengths. */
  protected abstract double getComparatorValue(long distance, int length1, int length2);

  /**
   * Returns a comparator value standing in for a distance that overran {@code comparatorValue}. It
   * only has to be worse than the budget it overran, since the caller is about to discard it.
   */
  protected abstract double getExceededComparatorValue(double comparatorValue);

  @Override
  protected final double compareInternal(
      LongTermsAndValues termsAndValues1, LongTermsAndValues termsAndValues2, double minSimilarity)
      throws ArraysSizeMismatchError {
    double maxComparatorValue =
        comparatorNormalizer.normalizedSimilarityValueToComparatorValue(minSimilarity);
    int length1 = termsAndValues1.termsLength();
    int length2 = termsAndValues2.termsLength();
    long maxDistance = getMaxDistance(maxComparatorValue, length1, length2);
    long distance = sequenceDistance.getDistance(termsAndValues1, termsAndValues2, maxDistance);
    return distance == SequenceDistance.DISTANCE_EXCEEDED
        ? getExceededComparatorValue(maxComparatorValue)
        : getComparatorValue(distance, length1, length2);
  }

  /**
   * An indexed multiset's value is how many times that element occurs, and those counts sum to the
   * sequence length, so each contributes itself to the record's Uni value.
   */
  @Override
  public final double getUniTransformedValue(float value) {
    if (value < 0.0f) {
      throw new IllegalArgumentException(
          String.format("A sequence element cannot occur a negative number of times (%s).", value));
    }
    return value;
  }

  /** An edit distance reads the elements in the order they arrived, so only a sequence will do. */
  @Override
  public final Set<RecordType> getSupportedRecordTypes() {
    return Set.of(RecordType.SEQUENCE);
  }

  /*
   * A sequence's Uni value is its length. Both forms report it: the ordered sequence keeps its
   * elements in terms and has no counts to sum, and the element multiset's counts sum to that same
   * length, so the parent's sum over the values already gives it.
   */

  @Override
  public final double computeUniValue(long[] terms, float[] values) {
    return values.length == 0 ? terms.length : super.computeUniValue(terms, values);
  }

  @Override
  public final double computeUniValue(LongTermsAndValues termsAndValues) {
    return termsAndValues.valuesLength() == 0
        ? termsAndValues.termsLength()
        : super.computeUniValue(termsAndValues);
  }

  /**
   * An edit either inserts, deletes, or rewrites one element, so it changes a sequence's length by
   * at most one. Two sequences whose lengths differ by more than the distance budget are therefore
   * too far apart whatever their contents.
   */
  @Override
  public final boolean mayPassLengthFiltering(
      double uniValue1, double uniValue2, double minSimilarity) {
    double maxComparatorValue =
        comparatorNormalizer.normalizedSimilarityValueToComparatorValue(minSimilarity);
    long maxDistance = getMaxDistance(maxComparatorValue, toLength(uniValue1), toLength(uniValue2));
    return Math.abs(uniValue1 - uniValue2) <= (double) maxDistance + MathUtils.EPSILON_12;
  }

  /**
   * Returns the sequence length a Uni value represents. Lengths are whole numbers that round-trip
   * exactly through a double at any length a sequence can reach, so the rounding only undoes the
   * widening.
   */
  private static int toLength(double uniValue) {
    return (int) Math.round(uniValue);
  }
}
