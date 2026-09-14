/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.comparator;

import com.uber.ussi.comparator.signaturegenerator.SignatureGeneratorFactory.SignatureGeneratorType;
import com.uber.ussi.config.ConfigVocabulary;
import java.util.Set;

/**
 * The comparators a namespace can be configured with, each paired with the signature generators it
 * accepts. An empty set is a comparator that cannot generate signatures at all.
 */
public enum ComparatorType implements ConfigVocabulary {
  /** Generalized Levenshtein distance between two sequences. */
  GLD(Generators.CONSISTENT_WEIGHTED_SAMPLING),

  JACCARD(Set.of(SignatureGeneratorType.MINHASH)),

  L2(Set.of()),

  /** Normalized generalized Levenshtein distance between two sequences. */
  NGLD(Generators.CONSISTENT_WEIGHTED_SAMPLING),

  RUZICKA(Generators.CONSISTENT_WEIGHTED_SAMPLING);

  private final Set<SignatureGeneratorType> supportedSignatureGeneratorTypes;

  ComparatorType(Set<SignatureGeneratorType> supportedSignatureGeneratorTypes) {
    this.supportedSignatureGeneratorTypes = supportedSignatureGeneratorTypes;
  }

  public Set<SignatureGeneratorType> getSupportedSignatureGeneratorTypes() {
    return supportedSignatureGeneratorTypes;
  }

  /** Returns whether this comparator compares sequences of elements rather than values. */
  public boolean comparesSequences() {
    return this == GLD || this == NGLD;
  }

  /**
   * The generator sets more than one comparator draws on. They live in a nested class because an
   * enum constructor cannot read a static field of its own enum.
   */
  private static final class Generators {

    /**
     * Weighted generators, so they collide at the multiset similarity of records whose values are
     * counts. The sequence comparators need that over an element multiset, and Ruzicka over a
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
