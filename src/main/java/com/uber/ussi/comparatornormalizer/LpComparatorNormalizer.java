/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.comparatornormalizer;

import com.uber.ussi.error.SearchResponseError;

public class LpComparatorNormalizer implements ComparatorNormalizer {

  public LpComparatorNormalizer() {}

  @Override
  public double comparatorValueToNormalizedSimilarityValue(double comparatorValue) {
    double normalizedSimilarityValue = 1 - comparatorValue / 2;
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
    return (1 - normalizedSimilarityValue) * 2;
  }
}
