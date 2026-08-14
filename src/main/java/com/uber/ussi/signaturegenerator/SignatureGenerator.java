/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.signaturegenerator;

import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;
import java.io.Serializable;
import java.util.Objects;

/** Generates deterministic sparse keys whose collisions estimate record similarity. */
public abstract class SignatureGenerator implements Serializable {
  private final boolean weighted;
  private final double comparisonValueApproximationSafetyMargin;

  protected SignatureGenerator(boolean weighted, double comparisonValueApproximationSafetyMargin) {
    if (comparisonValueApproximationSafetyMargin < 0.0
        || comparisonValueApproximationSafetyMargin > 1.0) {
      throw new IllegalArgumentException(
          "comparisonValueApproximationSafetyMargin must be in [0.0, 1.0].");
    }
    this.weighted = weighted;
    this.comparisonValueApproximationSafetyMargin = comparisonValueApproximationSafetyMargin;
  }

  public final boolean isWeighted() {
    return weighted;
  }

  public final double getComparisonValueApproximationSafetyMargin() {
    return comparisonValueApproximationSafetyMargin;
  }

  public final long[] getSignatures(LongTermsAndValues termsAndValues, int numSignatures) {
    Objects.requireNonNull(termsAndValues, "termsAndValues");
    if (numSignatures <= 0) {
      throw new IllegalArgumentException("numSignatures must be greater than 0.");
    }
    if (termsAndValues.termsLength() == 0
        || termsAndValues.termsLength() != termsAndValues.valuesLength()) {
      throw new IllegalArgumentException(
          "Signature generation requires equal non-empty terms and values arrays.");
    }
    return generateSignatures(termsAndValues, numSignatures);
  }

  protected abstract long[] generateSignatures(
      LongTermsAndValues termsAndValues, int numSignatures);
}
