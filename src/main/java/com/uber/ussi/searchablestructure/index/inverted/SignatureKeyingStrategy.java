/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.inverted;

import com.uber.ussi.comparator.Comparator;
import com.uber.ussi.comparator.ComparatorFactory;
import com.uber.ussi.comparator.KeyShareBounded;
import com.uber.ussi.comparator.signaturegenerator.SignatureGenerator;
import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;
import com.uber.ussi.error.IndexCreationError;
import com.uber.ussi.utils.MathUtils;
import java.util.Objects;

/**
 * How records become the signatures a signature-keyed structure indexes them under, and what
 * prefix of those signatures has to generate candidates.
 *
 * <p>Parallel to {@link RecordIndexingStrategy}, which says how a record type presents itself: a
 * structure composes one of each rather than asking its comparator. A structure that keys by
 * signatures cannot be built without this, which is why {@link #create create()} throws rather than
 * reporting an absent generator to every caller that has to check.
 *
 * <p>It pairs the two halves the bound needs and neither one owns. The generator knows how closely
 * its collision rate estimates a similarity. Only the measure knows which similarity that is.
 */
final class SignatureKeyingStrategy {

  private final Comparator comparator;
  private final KeyShareBounded keyShareBound;
  private final SignatureGenerator signatureGenerator;

  SignatureKeyingStrategy(Comparator comparator, SignatureGenerator signatureGenerator) {
    this.comparator = comparator;
    this.keyShareBound = (KeyShareBounded) comparator;
    this.signatureGenerator = signatureGenerator;
  }

  /**
   * Returns the strategy a signature-keyed structure indexes with, throwing {@link
   * IndexCreationError} when the configured comparator cannot supply one.
   */
  static SignatureKeyingStrategy create(NamespaceConfig namespaceConfig, Comparator comparator) {
    Objects.requireNonNull(namespaceConfig, "namespaceConfig is null.");
    Objects.requireNonNull(comparator, "comparator is null.");
    SignatureGenerator signatureGenerator =
        ComparatorFactory.createSignatureGenerator(namespaceConfig);
    if (!(comparator instanceof KeyShareBounded) || signatureGenerator == null) {
      throw new IndexCreationError(
          "A signature-keyed index requires a comparator with a configured signature generator.");
    }
    return new SignatureKeyingStrategy(comparator, signatureGenerator);
  }

  /** A signature stands for one draw, so every signature contributes the same Uni value. */
  double getSignatureUniTransformedValue() {
    return 1.0;
  }

  long[] getSignatures(LongTermsAndValues termsAndValues, int numSignatures) {
    return signatureGenerator.getSignatures(termsAndValues, numSignatures);
  }

  /**
   * Returns the prefix of a record's signatures that has to generate candidates: the Uni value
   * over signatures that a qualifying candidate may leave unshared, out of {@code numSignatures}.
   *
   * <p>Signature keys are always bounded by a share, every signature standing for one draw, so
   * this is the share shape over a Uni value of {@code numSignatures}. Two things distinguish it
   * from the same shape over terms. A generator only estimates the similarity its signatures
   * collide at, so the share is relaxed by its safety margin first, which lengthens the prefix and
   * buys back the recall the estimate would otherwise cost. And a prefix is rounded up to whole
   * signatures, since half a draw cannot be visited. Rounding cannot overrun {@code numSignatures}
   * because the shape caps the prefix at the Uni value it is given.
   */
  double getMaxPrefixSumForSignatures(
      int numSignatures, double recordUniValue, double minSimilarity) {
    if (numSignatures < 0) {
      throw new IllegalArgumentException("numSignatures must be at least 0.");
    }
    if (minSimilarity < 0.0 || minSimilarity > 1.0) {
      throw new IllegalArgumentException(
          String.format("minSimilarity must be in [0.0, 1.0], got %s.", minSimilarity));
    }
    double minSharedKeyFraction =
        keyShareBound.getMinSharedKeyFraction(
                recordUniValue, comparator.fromSimilarity(minSimilarity))
            - signatureGenerator.getComparisonValueApproximationSafetyMargin();
    return Math.ceil(
            Comparator.maxPrefixSumFromSharedFraction(numSignatures, minSharedKeyFraction))
        + MathUtils.EPSILON_12;
  }
}
