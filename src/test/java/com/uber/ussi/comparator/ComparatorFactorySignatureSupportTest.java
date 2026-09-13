package com.uber.ussi.comparator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.uber.ussi.comparator.signaturegenerator.SignatureGeneratorFactory.SignatureGeneratorType;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ComparatorFactorySignatureSupportTest {

  private static final SignatureSupportCase[] SIGNATURE_SUPPORT_CASES = {
    new SignatureSupportCase("jaccard", Set.of(SignatureGeneratorType.MINHASH)),
    new SignatureSupportCase(
        "ruzicka",
        Set.of(
            SignatureGeneratorType.I2CWS,
            SignatureGeneratorType.ICWS,
            SignatureGeneratorType.PCWS,
            SignatureGeneratorType.SCWS)),
    new SignatureSupportCase("l2", Set.of()),
    new SignatureSupportCase("cosine", Set.of()),
  };

  @Test
  void supportedSignatureGeneratorTypesCases() {
    for (SignatureSupportCase testCase : SIGNATURE_SUPPORT_CASES) {
      assertEquals(
          testCase.expectedTypes,
          ComparatorFactory.getSupportedSignatureGeneratorTypes(testCase.comparatorType),
          testCase.comparatorType);
    }
  }

  @Test
  void comparatorTypeLookupIsCaseInsensitive() {
    assertEquals(
        Set.of(SignatureGeneratorType.MINHASH),
        ComparatorFactory.getSupportedSignatureGeneratorTypes("JACCARD"));
  }

  @Test
  void unsupportedComparatorTypesReturnAnEmptySet() {
    assertTrue(ComparatorFactory.getSupportedSignatureGeneratorTypes("unknown").isEmpty());
  }

  private record SignatureSupportCase(
      String comparatorType, Set<SignatureGeneratorType> expectedTypes) {}
}
