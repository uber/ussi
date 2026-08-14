package com.uber.ussi.signaturegenerator;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValuesTestFactory;
import com.uber.ussi.signaturegenerator.SignatureGeneratorFactory.SignatureGeneratorType;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class SignatureGeneratorTest {

  @Test
  void factoryCreatesEverySupportedGenerator() {
    assertGenerator(SignatureGeneratorType.MINHASH, false, 0.1);
    assertGenerator(SignatureGeneratorType.I2CWS, true, 0.1);
    assertGenerator(SignatureGeneratorType.ICWS, true, 0.1);
    assertGenerator(SignatureGeneratorType.PCWS, true, 0.15);
    assertGenerator(SignatureGeneratorType.SCWS, true, 0.1);
    assertTrue(
        SignatureGeneratorFactory.createSignatureGenerator("minhash")
            instanceof MinHashSignatureGenerator);
  }

  @Test
  void factoryRejectsInvalidTypes() {
    assertThrows(
        NullPointerException.class,
        () -> SignatureGeneratorFactory.createSignatureGenerator((String) null));
    assertThrows(
        NullPointerException.class,
        () -> SignatureGeneratorFactory.createSignatureGenerator((SignatureGeneratorType) null));
    assertThrows(
        IllegalArgumentException.class,
        () -> SignatureGeneratorFactory.createSignatureGenerator("unknown"));
  }

  @Test
  void everyGeneratorIsDeterministicForSignedSparseValues() {
    LongTermsAndValues values = sparse(new long[] {11L, 22L, 33L}, 1f, -2f, 3f);
    for (SignatureGeneratorType type : SignatureGeneratorType.values()) {
      SignatureGenerator generator = SignatureGeneratorFactory.createSignatureGenerator(type);
      long[] first = generator.getSignatures(values, 32);
      long[] second = generator.getSignatures(values, 32);

      assertEquals(32, first.length);
      assertArrayEquals(first, second);
      assertTrue(Arrays.stream(first).anyMatch(signature -> signature != 0L));
    }
  }

  @Test
  void minHashSeparatesDisjointRecordsAndMatchesIdenticalRecords() {
    SignatureGenerator generator =
        SignatureGeneratorFactory.createSignatureGenerator(SignatureGeneratorType.MINHASH);
    LongTermsAndValues first = sparse(new long[] {1L, 2L, 3L}, 1f, 1f, 1f);
    LongTermsAndValues same = sparse(new long[] {1L, 2L, 3L}, 5f, 2f, 9f);
    LongTermsAndValues disjoint = sparse(new long[] {4L, 5L, 6L}, 1f, 1f, 1f);

    assertArrayEquals(generator.getSignatures(first, 64), generator.getSignatures(same, 64));
    assertFalse(
        Arrays.equals(generator.getSignatures(first, 64), generator.getSignatures(disjoint, 64)));
  }

  @Test
  void signatureCollisionRatesEstimateSignedJaccardAndRuzickaSimilarity() {
    LongTermsAndValues first = sparse(new long[] {1L, 2L, 3L}, -10f, 20f, 30f);
    LongTermsAndValues second = sparse(new long[] {1L, 2L, 4L}, 10f, 20f, 30f);
    int numSignatures = 1000;

    for (SignatureGeneratorType type : SignatureGeneratorType.values()) {
      SignatureGenerator generator = SignatureGeneratorFactory.createSignatureGenerator(type);
      double estimatedSimilarity =
          collisionRate(
              generator.getSignatures(first, numSignatures),
              generator.getSignatures(second, numSignatures));

      assertEquals(0.2, estimatedSimilarity, 0.1, type.name());
    }
  }

  @Test
  void signatureInputValidationRejectsInvalidRecords() {
    SignatureGenerator generator =
        SignatureGeneratorFactory.createSignatureGenerator(SignatureGeneratorType.MINHASH);
    LongTermsAndValues dense = internal(new long[0], new float[] {1f}, 1.0);
    LongTermsAndValues sequence = internal(new long[] {1L}, new float[0], 0.0);

    assertThrows(NullPointerException.class, () -> generator.getSignatures(null, 1));
    assertThrows(IllegalArgumentException.class, () -> generator.getSignatures(sparse(1L), 0));
    assertThrows(IllegalArgumentException.class, () -> generator.getSignatures(dense, 1));
    assertThrows(IllegalArgumentException.class, () -> generator.getSignatures(sequence, 1));
  }

  @Test
  void baseGeneratorValidatesSafetyMargin() {
    assertThrows(IllegalArgumentException.class, () -> new TestSignatureGenerator(-0.1));
    assertThrows(IllegalArgumentException.class, () -> new TestSignatureGenerator(1.1));
    SignatureGenerator generator = new TestSignatureGenerator(0.0);

    assertArrayEquals(new long[] {7L, 7L}, generator.getSignatures(sparse(1L), 2));
  }

  @Test
  void cwsSignatureHasValueSemantics() {
    CwsSignature first = new CwsSignature(1L, 0.1);
    CwsSignature same = new CwsSignature(1L, 0.1);
    CwsSignature differentTerm = new CwsSignature(2L, 0.1);
    CwsSignature differentWeight = new CwsSignature(1L, 0.2);

    assertEquals(first, first);
    assertEquals(first, same);
    assertEquals(first.hashCode(), same.hashCode());
    assertNotEquals(first, differentTerm);
    assertNotEquals(first, differentWeight);
    assertNotEquals(first, "signature");
    assertNotNull(first.toString());
    assertNotEquals(0L, first.longHashCode());
  }

  private static void assertGenerator(
      SignatureGeneratorType type, boolean weighted, double safetyMargin) {
    SignatureGenerator generator = SignatureGeneratorFactory.createSignatureGenerator(type);
    assertEquals(weighted, generator.isWeighted());
    assertEquals(safetyMargin, generator.getComparisonValueApproximationSafetyMargin(), 0.0);
  }

  private static LongTermsAndValues sparse(long term) {
    return sparse(new long[] {term}, 1f);
  }

  private static LongTermsAndValues sparse(long[] terms, float... values) {
    double uniValue = 0.0;
    for (float value : values) {
      uniValue += Math.abs(value);
    }
    return internal(terms, values, uniValue);
  }

  private static LongTermsAndValues internal(long[] terms, float[] values, double uniValue) {
    return LongTermsAndValuesTestFactory.create(terms, values, uniValue);
  }

  private static double collisionRate(long[] first, long[] second) {
    assertEquals(first.length, second.length);
    int numCollisions = 0;
    for (int i = 0; i < first.length; ++i) {
      if (first[i] == second[i]) {
        ++numCollisions;
      }
    }
    return (double) numCollisions / first.length;
  }

  private static final class TestSignatureGenerator extends SignatureGenerator {
    private TestSignatureGenerator(double safetyMargin) {
      super(/* weighted */ false, safetyMargin);
    }

    @Override
    protected long[] generateSignatures(LongTermsAndValues termsAndValues, int numSignatures) {
      long[] signatures = new long[numSignatures];
      Arrays.fill(signatures, 7L);
      return signatures;
    }
  }
}
