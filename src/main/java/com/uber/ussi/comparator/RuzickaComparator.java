/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.comparator;

import com.uber.ussi.comparatornormalizer.ComparatorNormalizer;

/** Weighted Jaccard (Ruzicka) similarity over TermsAndValues entries. */
public final class RuzickaComparator extends BaseRuzickaComparator {

  RuzickaComparator(ComparatorNormalizer comparatorNormalizer) {
    super(comparatorNormalizer);
  }

  @Override
  public double getUniTransformedValue(float value) {
    return Math.abs(value);
  }
}
