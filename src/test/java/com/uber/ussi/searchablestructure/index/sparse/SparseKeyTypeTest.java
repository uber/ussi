package com.uber.ussi.searchablestructure.index.sparse;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SparseKeyTypeTest {

  private static final TypeExpectation[] TYPE_EXPECTATIONS = {
    new TypeExpectation(SparseKeyType.EXACT_TERM, false, true),
    new TypeExpectation(SparseKeyType.SIGNATURE, true, false),
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
      SparseKeyType type, boolean requiresSignatureSupport, boolean supportsConjunctionScoring) {}
}
