/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.comparator;

import com.uber.ussi.comparatornormalizer.ComparatorNormalizer;
import com.uber.ussi.signaturegenerator.SignatureGenerator;
import javax.annotation.Nullable;

/** Weighted Jaccard (Ruzicka) similarity over TermsAndValues entries. */
public final class RuzickaComparator extends BaseRuzickaComparator {

  RuzickaComparator(
      ComparatorNormalizer comparatorNormalizer, @Nullable SignatureGenerator signatureGenerator) {
    super(comparatorNormalizer, signatureGenerator);
  }

  @Override
  public double getUniTransformedValue(float value) {
    return Math.abs(value);
  }
}
