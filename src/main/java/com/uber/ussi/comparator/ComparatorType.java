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
  GLD(Set.of()),

  JACCARD(Set.of(SignatureGeneratorType.MINHASH)),

  L2(Set.of()),

  /** Normalized generalized Levenshtein distance between two sequences. */
  NGLD(Set.of()),

  RUZICKA(
      Set.of(
          SignatureGeneratorType.I2CWS,
          SignatureGeneratorType.ICWS,
          SignatureGeneratorType.PCWS,
          SignatureGeneratorType.SCWS));

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
}
