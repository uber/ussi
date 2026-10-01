/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.comparator;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ComparatorTypeTest {

  @Test
  void statesStructurePairingCapabilities() {
    assertTrue(ComparatorType.L2.similarityFromDotProduct());
    assertTrue(ComparatorType.L2.similarityFromConfiguredConjunction());
    assertFalse(ComparatorType.L2.boundsKeyShare());

    assertTrue(ComparatorType.JACCARD.boundsKeyShare());
    assertTrue(ComparatorType.JACCARD.similarityFromConfiguredConjunction());
    assertFalse(ComparatorType.JACCARD.similarityFromDotProduct());

    assertTrue(ComparatorType.NGLD.boundsKeyShare());
    assertFalse(ComparatorType.NGLD.similarityFromConfiguredConjunction());
  }
}
