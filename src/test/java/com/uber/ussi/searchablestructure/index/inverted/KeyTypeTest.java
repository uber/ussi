package com.uber.ussi.searchablestructure.index.inverted;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class KeyTypeTest {

  private static final TypeExpectation[] TYPE_EXPECTATIONS = {
    new TypeExpectation(KeyType.EXACT_TERM, false, true),
    new TypeExpectation(KeyType.SIGNATURE, true, false),
  };

  @Test
  void eachTypeExposesTheExpectedCapabilities() {
    for (TypeExpectation expectation : TYPE_EXPECTATIONS) {
      assertEquals(
          expectation.requiresSignatureSupport,
          expectation.type.requiresSignatureSupport(),
          expectation.type + " requiresSignatureSupport");
      assertEquals(
          expectation.supportsConjunctionScoring,
          expectation.type.supportsConjunctionScoring(),
          expectation.type + " supportsConjunctionScoring");
    }
  }

  private record TypeExpectation(
      KeyType type, boolean requiresSignatureSupport, boolean supportsConjunctionScoring) {}
}
