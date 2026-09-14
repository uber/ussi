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
 * filtering. Subclasses report it as a raw edit count ({@link GldComparator}) or a
 * length-normalized fraction ({@link NgldComparator}), over the {@link SequenceDistance} they
 * compose.
 *
 * <p>A sequence record carries its elements, in order and with repeats, in its terms and has no
 * values, so its Uni value is its length. Candidate generation instead indexes the element
 * multiset as a sparse record whose counts sum to that same length, so both forms agree on the Uni
 * value.
 *
 * <p>Merging cannot generate candidates here: the shared elements bound an order-sensitive
 * distance without determining it, so these searches generate candidates and then verify them.
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
   * for sequences of the given lengths. A negative result means no distance can.
   */
  protected abstract long getMaxDistance(double comparatorValue, int length1, int length2);

  protected abstract double getComparatorValue(long distance, int length1, int length2);

  /**
   * Returns a comparator value standing in for a distance that overran {@code comparatorValue}. It
   * only has to be worse than that budget, since the caller is about to discard it.
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

  /** An element's value is its occurrence count, so it contributes itself to the Uni value. */
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

  // A sequence's Uni value is its length, whether taken from its terms or its multiset counts.

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
   * An edit changes a sequence's length by at most one, so two sequences whose lengths differ by
   * more than the distance budget are too far apart whatever their contents.
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
   * Lengths are whole numbers that round-trip exactly through a double at any length a sequence
   * can reach, so the rounding only undoes the widening.
   */
  private static int toLength(double uniValue) {
    return (int) Math.round(uniValue);
  }
}
