/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.comparator;

import com.uber.ussi.comparator.signaturegenerator.SignatureGenerator;
import com.uber.ussi.comparatornormalizer.ComparatorNormalizer;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;
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

  /**
   * Returns the prefix of a record's signatures that has to generate candidates: the Uni value
   * over signatures that a qualifying candidate may leave unshared, out of {@code numSignatures}.
   *
   * <p>A generator only estimates the similarity its signatures collide at, so the smallest share
   * a qualifying candidate can collide on is relaxed by the generator's safety margin first. That
   * lengthens the prefix, buying back the recall the estimate would otherwise cost.
   */
  public final double getMinPrefixSumForSignatures(
      int numSignatures, double recordUniValue, double minSimilarity) {
    SignatureGenerator generator = requireSignatureGenerator();
    if (numSignatures < 0) {
      throw new IllegalArgumentException("numSignatures must be at least 0.");
    }
    if (minSimilarity < 0.0 || minSimilarity > 1.0) {
      throw new IllegalArgumentException(
          String.format("minSimilarity must be in [0.0, 1.0], got %s.", minSimilarity));
    }
    double minSharedSignatureFraction =
        getMinSharedSignatureFraction(
                recordUniValue,
                comparatorNormalizer.normalizedSimilarityValueToComparatorValue(minSimilarity))
            - generator.getComparisonValueApproximationSafetyMargin();
    double unsharedFraction = 1.0 - Math.min(1.0, Math.max(0.0, minSharedSignatureFraction));
    return Math.min(numSignatures, Math.ceil(numSignatures * unsharedFraction))
        + MathUtils.EPSILON_12;
  }

  /**
   * Returns the smallest share of a record's signatures that a candidate clearing {@code
   * comparatorValue} can collide with it on, in [0.0, 1.0].
   *
   * <p>Signatures collide at a rate tracking the multiset similarity of the records they were
   * drawn from, so this is that similarity at the threshold, whatever the comparator itself
   * measures. A comparator measuring something else has to bound the multiset similarity from its
   * own threshold, and one whose threshold is not already a share of the record needs {@code
   * recordUniValue}, the Uni value over the record's own terms, to express one.
   *
   * <p>The margin the caller subtracts is what makes this a share rather than a count: a
   * generator's concentration bound is stated on the similarity it estimates, so a margin is only
   * meaningful in the same units.
   */
  protected abstract double getMinSharedSignatureFraction(
      double recordUniValue, double comparatorValue);

  /** A signature stands for one draw, so every signature contributes the same Uni value. */
  public final double getSignatureUniTransformedValue() {
    return 1.0;
  }

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
