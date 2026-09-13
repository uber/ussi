/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.comparator;

import com.uber.ussi.comparator.signaturegenerator.SignatureGenerator;
import com.uber.ussi.comparatornormalizer.ComparatorNormalizer;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;
import com.uber.ussi.entity.termsandvalues.RecordType;
import com.uber.ussi.error.ArraysSizeMismatchError;
import com.uber.ussi.utils.MathUtils;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Shared Jaccard/Ruzicka implementation with length and position filtering. A negatively weighted
 * element is treated as the corresponding negative element with a positive weight.
 */
abstract class BaseRuzickaComparator extends SignatureComparator {

  BaseRuzickaComparator(ComparatorNormalizer comparatorNormalizer) {
    super(comparatorNormalizer);
  }

  BaseRuzickaComparator(
      ComparatorNormalizer comparatorNormalizer, @Nullable SignatureGenerator signatureGenerator) {
    super(comparatorNormalizer, signatureGenerator);
  }

  protected static double computeMaxPossibleComparatorValue(
      double partialUni1,
      double uni1,
      double partialUni2,
      double uni2,
      double scannedIntersection,
      double scannedUnion) {
    if (partialUni1 < 0.0
        || uni1 < 0.0
        || partialUni2 < 0.0
        || uni2 < 0.0
        || scannedIntersection < 0.0
        || scannedUnion < 0.0
        || partialUni1 > uni1 + MathUtils.EPSILON_12
        || partialUni2 > uni2 + MathUtils.EPSILON_12
        || scannedIntersection > Math.min(partialUni1, partialUni2) + MathUtils.EPSILON_12
        || scannedUnion < scannedIntersection - MathUtils.EPSILON_12
        || scannedUnion > partialUni1 + partialUni2 + MathUtils.EPSILON_12) {
      throw new IllegalArgumentException(
          String.format(
              "Invalid partialUni1 (%s), uni1 (%s), partialUni2 (%s), uni2 (%s), "
                  + "scannedIntersection (%s), scannedUnion (%s).",
              partialUni1, uni1, partialUni2, uni2, scannedIntersection, scannedUnion));
    }
    double remainingUni1 = Math.max(0.0, uni1 - partialUni1);
    double remainingUni2 = Math.max(0.0, uni2 - partialUni2);
    double minPossibleUnion = scannedUnion + Math.max(remainingUni1, remainingUni2);
    if (minPossibleUnion == 0.0) {
      return 1.0;
    }
    return (scannedIntersection + Math.min(remainingUni1, remainingUni2)) / minPossibleUnion;
  }

  @Override
  protected double compareInternal(
      LongTermsAndValues termsAndValues1, LongTermsAndValues termsAndValues2, double minSimilarity)
      throws ArraysSizeMismatchError {
    double minComparatorValue =
        comparatorNormalizer.normalizedSimilarityValueToComparatorValue(minSimilarity);
    double intersection = 0.0;
    double union = 0.0;
    double partialUni1 = 0.0;
    double partialUni2 = 0.0;
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

      double transformedValue1 = getUniTransformedValue(value1);
      double transformedValue2 = getUniTransformedValue(value2);
      partialUni1 += transformedValue1;
      partialUni2 += transformedValue2;
      if (Math.signum(value1) == Math.signum(value2)) {
        intersection += Math.min(transformedValue1, transformedValue2);
        union += Math.max(transformedValue1, transformedValue2);
      } else {
        union += transformedValue1 + transformedValue2;
      }
      /*
       * Position filtering computes the highest similarity attainable from the unscanned values.
       * Stop once even that upper bound is below minSimilarity.
       */
      double maxPossibleComparatorValue =
          computeMaxPossibleComparatorValue(
              partialUni1,
              termsAndValues1.getUniValue(),
              partialUni2,
              termsAndValues2.getUniValue(),
              intersection,
              union);
      if (maxPossibleComparatorValue < minComparatorValue) {
        return maxPossibleComparatorValue;
      }
    }
    return union == 0.0 ? 1.0 : intersection / union;
  }

  @Override
  protected double getMinPrefixSumForTermsAndValuesInternal(
      double uniValue, double comparatorValue) {
    if (comparatorValue < 0.0) {
      throw new IllegalArgumentException("comparatorValue must be at least 0.0.");
    }
    return uniValue * (1.0 - comparatorValue);
  }

  @Override
  protected double getMinPrefixSumForSignaturesInternal(int numSignatures, double comparatorValue) {
    if (numSignatures < 0) {
      throw new IllegalArgumentException("numSignatures must be at least 0.");
    }
    if (comparatorValue < 0.0) {
      throw new IllegalArgumentException("comparatorValue must be at least 0.0.");
    }
    return Math.min(numSignatures, Math.ceil((1.0 - comparatorValue) * numSignatures));
  }

  @Override
  public double getSignatureUniTransformedValue() {
    return 1.0;
  }

  @Override
  public boolean mayPassLengthFiltering(double uniValue1, double uniValue2, double minSimilarity) {
    double minComparatorValue =
        comparatorNormalizer.normalizedSimilarityValueToComparatorValue(minSimilarity);
    return computeMaxPossibleComparatorValue(0.0, uniValue1, 0.0, uniValue2, 0.0, 0.0)
        >= minComparatorValue;
  }

  /** Both measures walk two records term by term, which needs the terms aligned and distinct. */
  @Override
  public Set<RecordType> getSupportedRecordTypes() {
    return Set.of(RecordType.SPARSE);
  }

  @Override
  public boolean supportsMergeCandidateGeneration() {
    return true;
  }

  @Override
  public double conjunctionContribution(float value1, float value2) {
    if (Math.signum(value1) != Math.signum(value2)) {
      return 0.0;
    }
    return Math.min(getUniTransformedValue(value1), getUniTransformedValue(value2));
  }

  @Override
  public double similarityFromConjunction(
      double conjunction,
      double partialUniValue1,
      double uniValue1,
      double partialUniValue2,
      double uniValue2) {
    double unionValue = uniValue1 + uniValue2 - conjunction;
    if (unionValue <= 0.0) {
      return comparatorNormalizer.comparatorValueToNormalizedSimilarityValue(1.0);
    }
    return comparatorNormalizer.comparatorValueToNormalizedSimilarityValue(
        conjunction / unionValue);
  }

  @Override
  public double maxSimilarityFromPartialConjunction(
      double conjunction,
      double unscannedKeysUniValue,
      double partialUniValue1,
      double uniValue1,
      double partialUniValue2,
      double uniValue2) {
    // The intersection can never exceed either row's Uni value, whatever the unscanned keys hold.
    double maxConjunction =
        Math.min(conjunction + unscannedKeysUniValue, Math.min(uniValue1, uniValue2));
    return similarityFromConjunction(
        maxConjunction, partialUniValue1, uniValue1, partialUniValue2, uniValue2);
  }

  @Override
  public boolean doesSuffixBoundConjunction() {
    return true;
  }
}
