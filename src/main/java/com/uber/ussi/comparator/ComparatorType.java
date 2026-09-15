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
  GLD(Generators.CONSISTENT_WEIGHTED_SAMPLING, /* comparesSequences */ true),

  JACCARD(Set.of(SignatureGeneratorType.MINHASH), /* comparesSequences */ false),

  L2(Set.of(), /* comparesSequences */ false),

  /** Normalized generalized Levenshtein distance between two sequences. */
  NGLD(Generators.CONSISTENT_WEIGHTED_SAMPLING, /* comparesSequences */ true),

  RUZICKA(Generators.CONSISTENT_WEIGHTED_SAMPLING, /* comparesSequences */ false);

  private final Set<SignatureGeneratorType> supportedSignatureGeneratorTypes;
  private final boolean comparesSequences;

  ComparatorType(
      Set<SignatureGeneratorType> supportedSignatureGeneratorTypes, boolean comparesSequences) {
    this.supportedSignatureGeneratorTypes = supportedSignatureGeneratorTypes;
    this.comparesSequences = comparesSequences;
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
