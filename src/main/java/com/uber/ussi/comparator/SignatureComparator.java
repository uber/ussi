/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.comparator;

import com.uber.ussi.comparatornormalizer.ComparatorNormalizer;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;
import com.uber.ussi.signaturegenerator.SignatureGenerator;
import com.uber.ussi.utils.MathUtils;
import javax.annotation.Nullable;

/** Base comparator for similarities that support signature-based candidate generation. */
public abstract class SignatureComparator extends Comparator {
  @Nullable private final SignatureGenerator signatureGenerator;

  protected SignatureComparator(ComparatorNormalizer comparatorNormalizer) {
    this(comparatorNormalizer, null);
  }

  protected SignatureComparator(
      ComparatorNormalizer comparatorNormalizer, @Nullable SignatureGenerator signatureGenerator) {
    super(comparatorNormalizer);
    this.signatureGenerator = signatureGenerator;
  }

  public final double getMinPrefixSumForSignatures(int numSignatures, double minSimilarity) {
    SignatureGenerator generator = requireSignatureGenerator();
    if (numSignatures < 0) {
      throw new IllegalArgumentException("numSignatures must be at least 0.");
    }
    if (minSimilarity < 0.0 || minSimilarity > 1.0) {
      throw new IllegalArgumentException(
          String.format("minSimilarity must be in [0.0, 1.0], got %s.", minSimilarity));
    }
    double comparatorValue =
        Math.max(
            0.0,
            comparatorNormalizer.normalizedSimilarityValueToComparatorValue(minSimilarity)
                - generator.getComparisonValueApproximationSafetyMargin());
    return getMinPrefixSumForSignaturesInternal(numSignatures, comparatorValue) + MathUtils.EPSILON;
  }

  protected abstract double getMinPrefixSumForSignaturesInternal(
      int numSignatures, double comparatorValue);

  public abstract double getSignatureUniTransformedValue();

  public final long[] getSignatures(LongTermsAndValues termsAndValues, int numSignatures) {
    return requireSignatureGenerator().getSignatures(termsAndValues, numSignatures);
  }

  public final boolean supportsSignatures() {
    return signatureGenerator != null;
  }

  private SignatureGenerator requireSignatureGenerator() {
    if (signatureGenerator == null) {
      throw new UnsupportedOperationException(
          "No signature generator is configured for this comparator.");
    }
    return signatureGenerator;
  }
}
