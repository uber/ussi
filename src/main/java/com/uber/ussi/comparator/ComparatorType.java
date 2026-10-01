/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.comparator;

import com.uber.ussi.comparator.signaturegenerator.SignatureGeneratorFactory.SignatureGeneratorType;
import com.uber.ussi.config.ConfigVocabulary;
import java.util.Set;

/**
 * The comparators a namespace can be configured with, each stating the signature generators it
 * accepts and whether it compares sequences. An empty generator set is a comparator that cannot
 * generate signatures at all.
 */
public enum ComparatorType implements ConfigVocabulary {
  /** Generalized Levenshtein distance between two sequences. */
  GLD(
      Generators.CONSISTENT_WEIGHTED_SAMPLING,
      /* comparesSequences */ true,
      /* boundsKeyShare */ true,
      /* similarityFromConfiguredConjunction */ false,
      /* similarityFromDotProduct */ false),

  JACCARD(
      Set.of(SignatureGeneratorType.MINHASH),
      /* comparesSequences */ false,
      /* boundsKeyShare */ true,
      /* similarityFromConfiguredConjunction */ true,
      /* similarityFromDotProduct */ false),

  L2(
      Set.of(),
      /* comparesSequences */ false,
      /* boundsKeyShare */ false,
      /* similarityFromConfiguredConjunction */ true,
      /* similarityFromDotProduct */ true),

  /** Normalized generalized Levenshtein distance between two sequences. */
  NGLD(
      Generators.CONSISTENT_WEIGHTED_SAMPLING,
      /* comparesSequences */ true,
      /* boundsKeyShare */ true,
      /* similarityFromConfiguredConjunction */ false,
      /* similarityFromDotProduct */ false),

  RUZICKA(
      Generators.CONSISTENT_WEIGHTED_SAMPLING,
      /* comparesSequences */ false,
      /* boundsKeyShare */ true,
      /* similarityFromConfiguredConjunction */ true,
      /* similarityFromDotProduct */ false);

  private final Set<SignatureGeneratorType> supportedSignatureGeneratorTypes;
  private final boolean comparesSequences;
  private final boolean boundsKeyShare;
  private final boolean similarityFromConfiguredConjunction;
  private final boolean similarityFromDotProduct;

  ComparatorType(
      Set<SignatureGeneratorType> supportedSignatureGeneratorTypes,
      boolean comparesSequences,
      boolean boundsKeyShare,
      boolean similarityFromConfiguredConjunction,
      boolean similarityFromDotProduct) {
    this.supportedSignatureGeneratorTypes = supportedSignatureGeneratorTypes;
    this.comparesSequences = comparesSequences;
    this.boundsKeyShare = boundsKeyShare;
    this.similarityFromConfiguredConjunction = similarityFromConfiguredConjunction;
    this.similarityFromDotProduct = similarityFromDotProduct;
  }

  public Set<SignatureGeneratorType> getSupportedSignatureGeneratorTypes() {
    return supportedSignatureGeneratorTypes;
  }

  /**
   * Returns whether this comparator compares sequences of terms rather than values, which decides
   * whether a namespace may configure a sequence distance. Each constant states it, so a
   * comparator added here has to answer rather than be answered for.
   */
  public boolean comparesSequences() {
    return comparesSequences;
  }

  /** Returns whether this measure can bound how much of a record's keys a candidate must share. */
  public boolean boundsKeyShare() {
    return boundsKeyShare;
  }

  /**
   * Returns whether merge may treat the configured comparator's conjunction as the row's
   * similarity on structures where conjunction determines similarity.
   */
  public boolean similarityFromConfiguredConjunction() {
    return similarityFromConfiguredConjunction;
  }

  /** Returns whether this measure's similarity is determined by a dot product and two Uni values. */
  public boolean similarityFromDotProduct() {
    return similarityFromDotProduct;
  }

  /**
   * The generator sets more than one comparator draws on. They live in a nested class because an
   * enum constructor cannot read a static field of its own enum.
   */
  private static final class Generators {

    /**
     * Weighted generators, so they collide at the multiset similarity of records whose values are
     * counts. The sequence comparators need that over a term multiset, and Ruzicka over a
     * sparse record's values.
     */
    private static final Set<SignatureGeneratorType> CONSISTENT_WEIGHTED_SAMPLING =
        Set.of(
            SignatureGeneratorType.I2CWS,
            SignatureGeneratorType.ICWS,
            SignatureGeneratorType.PCWS,
            SignatureGeneratorType.SCWS);

    private Generators() {}
  }
}
