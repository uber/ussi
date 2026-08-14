/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.comparatornormalizer;

import java.io.Serializable;

public interface ComparatorNormalizer extends Serializable {

  double comparatorValueToNormalizedSimilarityValue(double comparatorValue);

  double normalizedSimilarityValueToComparatorValue(double normalizedSimilarityValue);
}
