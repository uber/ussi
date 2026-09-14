/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.comparatornormalizer;

import com.uber.ussi.error.SearchResponseError;

/**
 * Maps a distance already normalized to [0.0, 1.0] onto its complementary similarity. It is the
 * normalizer for comparators whose value is a normalized distance, such as NGLD.
 */
public class ComplementComparatorNormalizer implements ComparatorNormalizer {

  public ComplementComparatorNormalizer() {}

  @Override
  public double comparatorValueToNormalizedSimilarityValue(double comparatorValue) {
    if (0 > comparatorValue || comparatorValue > 1) {
      throw new SearchResponseError(
          String.format("Invalid comparatorValue (%s).", comparatorValue));
    }
    return 1 - comparatorValue;
  }

  @Override
  public double normalizedSimilarityValueToComparatorValue(double normalizedSimilarityValue) {
    if (0 > normalizedSimilarityValue || normalizedSimilarityValue > 1) {
      throw new SearchResponseError(
          String.format("Invalid normalizedSimilarityValue (%s).", normalizedSimilarityValue));
    }
    return 1 - normalizedSimilarityValue;
  }
}
