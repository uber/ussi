package com.uber.ussi.comparator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.uber.ussi.comparatornormalizer.ReciprocalComparatorNormalizer;
import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValuesTestFactory;
import com.uber.ussi.error.ArraysSizeMismatchError;
import com.uber.ussi.error.ComparatorCreationError;
import com.uber.ussi.utils.Constants;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ComparatorTest {

  private static final double DELTA = 1e-9;

  private static Comparator l2Comparator() {
    return ComparatorFactory.createComparator("l2", Map.of(), new ReciprocalComparatorNormalizer());
  }

  private static LongTermsAndValues denseVector(float[] values, double uniValue) {
    return LongTermsAndValuesTestFactory.create(new long[0], values, uniValue);
  }

  @Test
  void getUniTransformedValueSquaresValue() {
    assertEquals(9.0, l2Comparator().getUniTransformedValue(3f), DELTA);
  }

  @Test
  void constructorRejectsNullNormalizer() {
    assertThrows(NullPointerException.class, () -> new L2Comparator(null));
  }

  @Test
  void computeUniValueSumsSquaredValues() {
    assertEquals(25.0, l2Comparator().computeUniValue(new float[] {3f, 4f}), DELTA);
  }

  @Test
  void computeUniValueReadsValuesFromTermsAndValues() {
    LongTermsAndValues vector = denseVector(new float[] {3f, 4f}, 25.0);

    assertEquals(25.0, l2Comparator().computeUniValue(vector), DELTA);
  }

  @Test
  void getSimilarityIsOneForIdenticalVectors() {
    LongTermsAndValues vector = denseVector(new float[] {1f, 0f}, 1.0);

    assertEquals(1.0, l2Comparator().getSimilarity(vector, vector, 0.0), DELTA);
  }

  @Test
  void getSimilarityRejectsUnsetUniValue() {
    LongTermsAndValues unset = denseVector(new float[] {1f, 0f}, Constants.UNSET_UNI_VALUE);
    LongTermsAndValues valid = denseVector(new float[] {1f, 0f}, 1.0);
    Comparator comparator = l2Comparator();

    assertThrows(IllegalArgumentException.class, () -> comparator.getSimilarity(unset, valid, 0.0));
  }

  @Test
  void getSimilarityRejectsRecordsWithoutValues() {
    LongTermsAndValues empty = denseVector(new float[0], 0.0);
    Comparator comparator = l2Comparator();

    assertThrows(IllegalArgumentException.class, () -> comparator.getSimilarity(empty, empty, 0.0));
  }

  @Test
  void getSimilarityRejectsMixedDenseAndSparseRecords() {
    LongTermsAndValues sparse =
        LongTermsAndValuesTestFactory.create(new long[] {1L}, new float[] {1f}, 1.0);
    LongTermsAndValues dense = denseVector(new float[] {1f}, 1.0);
    Comparator comparator = l2Comparator();

    assertThrows(ArraysSizeMismatchError.class, () -> comparator.getSimilarity(sparse, dense, 0.0));
  }

  @Test
  void getSimilarityRejectsMismatchedValueLengths() {
    LongTermsAndValues shorter = denseVector(new float[] {1f}, 1.0);
    LongTermsAndValues longer = denseVector(new float[] {1f, 2f}, 5.0);
    Comparator comparator = l2Comparator();

    assertThrows(
        ArraysSizeMismatchError.class, () -> comparator.getSimilarity(shorter, longer, 0.0));
  }

  @Test
  void getSimilarityReturnsZeroWhenLengthFilteringPrunesPair() {
    LongTermsAndValues large = denseVector(new float[] {10f}, 100.0);
    LongTermsAndValues small = denseVector(new float[] {1f}, 1.0);

    assertEquals(0.0, l2Comparator().getSimilarity(large, small, 0.9), DELTA);
  }

  @Test
  void factoryCreatesComparatorFromNamespaceConfig() {
    NamespaceConfig config =
        NamespaceConfig.builder()
            .maxCacheSize(10)
            .cacheType("generic")
            .indexType("dense")
            .comparatorType("l2")
            .comparatorNormalizerType("reciprocal")
            .maxNumSearchableStructures(3)
            .maxNumSimilarities(5)
            .build();

    assertTrue(ComparatorFactory.createComparator(config) instanceof L2Comparator);
  }

  @Test
  void factoryCreatesJaccardAndRuzickaComparators() {
    assertTrue(
        ComparatorFactory.createComparator(
                "jaccard",
                Map.of(),
                new com.uber.ussi.comparatornormalizer
                    .IdentityComparatorNormalizer())
            instanceof JaccardComparator);
    assertTrue(
        ComparatorFactory.createComparator(
                "ruzicka",
                Map.of(),
                new com.uber.ussi.comparatornormalizer
                    .IdentityComparatorNormalizer())
            instanceof RuzickaComparator);
  }

  @Test
  void getMinPrefixSumValidatesInputs() {
    Comparator comparator = l2Comparator();

    assertThrows(
        IllegalArgumentException.class,
        () -> comparator.getMinPrefixSumForTermsAndValues(Constants.UNSET_UNI_VALUE, 0.5));
    assertThrows(
        IllegalArgumentException.class,
        () -> comparator.getMinPrefixSumForTermsAndValues(1.0, -0.1));
    assertThrows(
        IllegalArgumentException.class,
        () -> comparator.getMinPrefixSumForTermsAndValues(1.0, 1.1));
  }

  @Test
  void l2DoesNotExposeSignatureCapability() {
    assertTrue(!(l2Comparator() instanceof SignatureComparator));
  }

  @Test
  void factoryRejectsUnsupportedComparatorType() {
    assertThrows(
        ComparatorCreationError.class,
        () ->
            ComparatorFactory.createComparator(
                "cosine", Map.of(), new ReciprocalComparatorNormalizer()));
  }
}
