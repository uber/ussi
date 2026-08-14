/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.comparator;

import com.uber.ussi.comparatornormalizer.ComparatorNormalizer;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;
import com.uber.ussi.error.ArraysSizeMismatchError;
import com.uber.ussi.utils.MathUtils;

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
        || partialUni1 > uni1 + MathUtils.EPSILON
        || partialUni2 > uni2 + MathUtils.EPSILON) {
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
    /*
     * Dense records have empty terms and align values by position. Sparse records merge their
     * sorted terms, treating a term missing from either record as having value 0.0.
     */
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
      /*
       * Position filtering bounds the contribution of all unscanned dimensions. Stop once they
       * cannot keep the final L2 distance within the threshold required by minSimilarity.
       */
      if (!mayPassPositionFilteringInternal(
          partialConj.getSum(),
          partialUni1.getSum(),
          termsAndValues1.getUniValue(),
          partialUni2.getSum(),
          termsAndValues2.getUniValue(),
          maxSquaredL2Distance)) {
        return Math.sqrt(maxSquaredL2Distance + MathUtils.EPSILON);
      }
    }
    return Math.sqrt(Math.max(0.0, sumSquaredL2Distance.getSum()));
  }

  @Override
  public double getUniTransformedValue(float value) {
    return (double) value * value;
  }

  @Override
  protected double getMinPrefixSumForTermsAndValuesInternal(
      double uniValue, double comparatorValue) {
    return comparatorValue * comparatorValue;
  }

  @Override
  public boolean mayPassLengthFiltering(double uniValue1, double uniValue2, double minSimilarity) {
    double maxL2Distance =
        comparatorNormalizer.normalizedSimilarityValueToComparatorValue(minSimilarity);
    return mayPassLengthFilteringInternal(uniValue1, uniValue2, maxL2Distance * maxL2Distance);
  }
}
