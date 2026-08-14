/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.comparatornormalizer;

import com.uber.ussi.error.SearchResponseError;

public class ReciprocalComparatorNormalizer implements ComparatorNormalizer {

  public ReciprocalComparatorNormalizer() {}

  @Override
  public double comparatorValueToNormalizedSimilarityValue(double comparatorValue) {
    double normalizedSimilarityValue = 1.0 / (1.0 + comparatorValue);
    if (0 > normalizedSimilarityValue || normalizedSimilarityValue > 1) {
      throw new SearchResponseError(
          String.format("Invalid comparatorValue (%s).", comparatorValue));
    }
    return normalizedSimilarityValue;
  }

  @Override
  public double normalizedSimilarityValueToComparatorValue(double normalizedSimilarityValue) {
    if (0 > normalizedSimilarityValue || normalizedSimilarityValue > 1) {
      throw new SearchResponseError(
          String.format("Invalid normalizedSimilarityValue (%s).", normalizedSimilarityValue));
    }
    return (1.0 / normalizedSimilarityValue) - 1.0;
  }
}
