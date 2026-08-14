/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.comparatornormalizer;

import com.uber.ussi.error.SearchResponseError;

public class IdentityComparatorNormalizer implements ComparatorNormalizer {

  public IdentityComparatorNormalizer() {}

  @Override
  public double comparatorValueToNormalizedSimilarityValue(double comparatorValue) {
    if (0 > comparatorValue || comparatorValue > 1) {
      throw new SearchResponseError(
          String.format("Invalid comparatorValue (%s).", comparatorValue));
    }
    return comparatorValue;
  }

  @Override
  public double normalizedSimilarityValueToComparatorValue(double normalizedSimilarityValue) {
    if (0 > normalizedSimilarityValue || normalizedSimilarityValue > 1) {
      throw new SearchResponseError(
          String.format("Invalid normalizedSimilarityValue (%s).", normalizedSimilarityValue));
    }
    return normalizedSimilarityValue;
  }
}
