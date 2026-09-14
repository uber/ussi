package com.uber.ussi.comparator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.uber.ussi.comparator.signaturegenerator.SignatureGeneratorFactory.SignatureGeneratorType;
import com.uber.ussi.config.ConfigVocabulary;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ComparatorTypeTest {

  @Test
  void paramValuesAreTheLowercasedNames() {
    assertEquals("jaccard", ComparatorType.JACCARD.getParamValue());
    assertEquals("l2", ComparatorType.L2.getParamValue());
    assertEquals(
        ComparatorType.RUZICKA,
        ConfigVocabulary.fromParamValue(ComparatorType.class, " Ruzicka "));
  }

  @Test
  void anUnknownParamValueHasNoComparatorType() {
    assertNull(ConfigVocabulary.fromParamValue(ComparatorType.class, "cosine"));
  }

  /**
   * A generator has to collide at the similarity the comparator's own threshold bounds. Jaccard
   * reads a record's distinct terms, so an unweighted generator serves it; the rest read counts,
   * whether a sparse record's values or how often a sequence repeats an element.
   */
  @Test
  void supportedSignatureGeneratorTypesAreTheOnesTheComparatorAccepts() {
    assertEquals(
        Set.of(SignatureGeneratorType.MINHASH),
        ComparatorType.JACCARD.getSupportedSignatureGeneratorTypes());
    Set<SignatureGeneratorType> consistentWeightedSampling =
        Set.of(
            SignatureGeneratorType.I2CWS,
            SignatureGeneratorType.ICWS,
            SignatureGeneratorType.PCWS,
            SignatureGeneratorType.SCWS);
    assertEquals(
        consistentWeightedSampling, ComparatorType.RUZICKA.getSupportedSignatureGeneratorTypes());
    assertEquals(
        consistentWeightedSampling, ComparatorType.GLD.getSupportedSignatureGeneratorTypes());
    assertEquals(
        consistentWeightedSampling, ComparatorType.NGLD.getSupportedSignatureGeneratorTypes());
  }

  /** L2 is a distance over positions, which no signature collision rate tracks. */
  @Test
  void aComparatorThatGeneratesNoSignaturesAcceptsNoGenerator() {
    assertTrue(ComparatorType.L2.getSupportedSignatureGeneratorTypes().isEmpty());
  }

  @Test
  void onlyTheEditDistanceComparatorsCompareSequences() {
    assertTrue(ComparatorType.GLD.comparesSequences());
    assertTrue(ComparatorType.NGLD.comparesSequences());
    assertFalse(ComparatorType.JACCARD.comparesSequences());
    assertFalse(ComparatorType.L2.comparesSequences());
    assertFalse(ComparatorType.RUZICKA.comparesSequences());
  }
}
