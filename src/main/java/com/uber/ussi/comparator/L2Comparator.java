/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.comparator;

import com.uber.ussi.comparatornormalizer.ComparatorNormalizer;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;
import com.uber.ussi.entity.termsandvalues.RecordType;
import com.uber.ussi.error.ArraysSizeMismatchError;
import com.uber.ussi.utils.MathUtils;
import java.util.Set;

public class L2Comparator extends Comparator {

  L2Comparator(ComparatorNormalizer comparatorNormalizer) {
    super(comparatorNormalizer);
  }

  private static void validatePartialUniValues(
      double partialUni1, double uni1, double partialUni2, double uni2) {
    if (partialUni1 < 0.0
        || uni1 < 0.0
        || partialUni2 < 0.0
        || uni2 < 0.0
        || partialUni1 > uni1 + MathUtils.EPSILON_12
        || partialUni2 > uni2 + MathUtils.EPSILON_12) {
      throw new IllegalArgumentException(
          String.format(
              "Invalid partialUni1 (%s), uni1 (%s), partialUni2 (%s), uni2 (%s).",
              partialUni1, uni1, partialUni2, uni2));
    }
  }

  protected static boolean mayPassLengthFilteringInternal(
      double uni1, double uni2, double maxSquaredL2Distance) {
    double k = uni1 + uni2 - maxSquaredL2Distance;
    if (k <= 0.0) {
      return true;
    }
    return k * k <= 4.0 * uni1 * uni2;
  }

  protected static boolean mayPassPositionFilteringInternal(
      double partialConj,
      double partialUni1,
      double uni1,
      double partialUni2,
      double uni2,
      double maxSquaredL2Distance) {
    validatePartialUniValues(partialUni1, uni1, partialUni2, uni2);
    double innerProductThreshold = (uni1 + uni2 - maxSquaredL2Distance) / 2.0;
    if (partialConj >= innerProductThreshold) {
      return true;
    }
    double remainingInnerProductGap = innerProductThreshold - partialConj;
    double remainingSquaredNorm1 = Math.max(0.0, uni1 - partialUni1);
    double remainingSquaredNorm2 = Math.max(0.0, uni2 - partialUni2);
    return remainingSquaredNorm1 * remainingSquaredNorm2
        >= remainingInnerProductGap * remainingInnerProductGap;
  }

  @Override
  protected double compareInternal(
      LongTermsAndValues termsAndValues1, LongTermsAndValues termsAndValues2, double minSimilarity)
      throws ArraysSizeMismatchError, IllegalArgumentException {
    double maxL2Distance =
        comparatorNormalizer.normalizedSimilarityValueToComparatorValue(minSimilarity);
    double maxSquaredL2Distance = maxL2Distance * maxL2Distance;
    MathUtils.StableSumAccumulator partialUni1 = new MathUtils.StableSumAccumulator();
    MathUtils.StableSumAccumulator partialUni2 = new MathUtils.StableSumAccumulator();
    MathUtils.StableSumAccumulator partialConj = new MathUtils.StableSumAccumulator();
    MathUtils.StableSumAccumulator sumSquaredL2Distance = new MathUtils.StableSumAccumulator();
    int pointer1 = 0;
    int pointer2 = 0;
    // Dense records align values by position; sparse merge sorted terms, a missing term is 0.0.
    while (pointer1 < termsAndValues1.valuesLength() || pointer2 < termsAndValues2.valuesLength()) {
      float value1;
      float value2;
      if (termsAndValues1.termsLength() == 0) {
        value1 = termsAndValues1.getValue(pointer1);
        value2 = termsAndValues2.getValue(pointer2);
        ++pointer1;
        ++pointer2;
      } else if (pointer1 < termsAndValues1.termsLength()
          && pointer2 < termsAndValues2.termsLength()
          && termsAndValues1.getTerm(pointer1) == termsAndValues2.getTerm(pointer2)) {
        value1 = termsAndValues1.getValue(pointer1++);
        value2 = termsAndValues2.getValue(pointer2++);
      } else if (pointer1 < termsAndValues1.termsLength()
          && (pointer2 >= termsAndValues2.termsLength()
              || termsAndValues1.getTerm(pointer1) < termsAndValues2.getTerm(pointer2))) {
        value1 = termsAndValues1.getValue(pointer1++);
        value2 = 0.0f;
      } else {
        value1 = 0.0f;
        value2 = termsAndValues2.getValue(pointer2++);
      }

      double squaredValue1 = (double) value1 * value1;
      double squaredValue2 = (double) value2 * value2;
      double distanceOfDimension = value1 - value2;
      sumSquaredL2Distance.add(distanceOfDimension * distanceOfDimension);
      partialUni1.add(squaredValue1);
      partialUni2.add(squaredValue2);
      partialConj.add((double) value1 * value2);
      // Position filtering: stop once the unscanned dimensions cannot keep the distance in budget.
      if (!mayPassPositionFilteringInternal(
          partialConj.getSum(),
          partialUni1.getSum(),
          termsAndValues1.getUniValue(),
          partialUni2.getSum(),
          termsAndValues2.getUniValue(),
          maxSquaredL2Distance)) {
        return Math.sqrt(maxSquaredL2Distance + MathUtils.EPSILON_12);
      }
    }
    return Math.sqrt(Math.max(0.0, sumSquaredL2Distance.getSum()));
  }

  @Override
  public double getUniTransformedValue(float value) {
    return (double) value * value;
  }

  /**
   * A squared distance states the maximum prefix sum directly rather than through a share: it is
   * already in the units of the squared values this comparator's Uni value sums, and a distance
   * budget bounds no share of a record, two vectors being as far apart as their magnitudes
   * allow.
   */
  @Override
  protected double getMaxPrefixSumForTermsAndValuesInternal(
      double uniValue, double comparatorValue) {
    return comparatorValue * comparatorValue;
  }

  @Override
  public boolean mayPassLengthFiltering(double uniValue1, double uniValue2, double minSimilarity) {
    double maxL2Distance =
        comparatorNormalizer.normalizedSimilarityValueToComparatorValue(minSimilarity);
    return mayPassLengthFilteringInternal(uniValue1, uniValue2, maxL2Distance * maxL2Distance);
  }

  /**
   * An L2 distance is defined over either record type: a dense record's values are the vector, and
   * a sparse record's terms name its non-zero coordinates.
   */
  @Override
  public Set<RecordType> getSupportedRecordTypes() {
    return Set.of(RecordType.DENSE, RecordType.SPARSE);
  }

  @Override
  public boolean supportsMergeCandidateGeneration() {
    return true;
  }

  @Override
  public double conjunctionContribution(float value1, float value2) {
    double gap = (double) value1 - value2;
    return gap * gap;
  }

  @Override
  public double similarityFromConjunction(
      double conjunction,
      double partialUniValue1,
      double uniValue1,
      double partialUniValue2,
      double uniValue2) {
    double unscannedUniValue1 = Math.max(0.0, uniValue1 - partialUniValue1);
    double unscannedUniValue2 = Math.max(0.0, uniValue2 - partialUniValue2);
    return comparatorNormalizer.comparatorValueToNormalizedSimilarityValue(
        Math.sqrt(Math.max(0.0, conjunction + unscannedUniValue1 + unscannedUniValue2)));
  }

  @Override
  public double maxSimilarityFromPartialConjunction(
      double conjunction,
      double unscannedKeysUniValue,
      double partialUniValue1,
      double uniValue1,
      double partialUniValue2,
      double uniValue2) {
    double unscannedUniValue1 = Math.max(0.0, uniValue1 - partialUniValue1);
    double unscannedUniValue2 = Math.max(0.0, uniValue2 - partialUniValue2);
    double unscannedSquaredDistance =
        unscannedUniValue1
            + unscannedUniValue2
            - 2.0 * Math.sqrt(Math.max(0.0, unscannedUniValue1 * unscannedUniValue2));
    return comparatorNormalizer.comparatorValueToNormalizedSimilarityValue(
        Math.sqrt(Math.max(0.0, conjunction + unscannedSquaredDistance)));
  }

  @Override
  public boolean supportsDotProductScoring() {
    return true;
  }

  /**
   * Expands the squared distance as the two squared norms less twice the dot product. A record's
   * unilateral value sums the squares of its values, so it is that record's squared norm.
   *
   * <p>The expansion cancels to nothing for a close pair, which leaves the clamp absorbing the
   * small negative squared distance that round-off can produce.
   */
  @Override
  public double similarityFromDotProduct(double dotProduct, double uniValue1, double uniValue2) {
    return comparatorNormalizer.comparatorValueToNormalizedSimilarityValue(
        Math.sqrt(Math.max(0.0, uniValue1 + uniValue2 - 2.0 * dotProduct)));
  }
}
