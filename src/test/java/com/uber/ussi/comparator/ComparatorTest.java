package com.uber.ussi.comparator;

import static com.uber.ussi.utils.MathUtils.EPSILON_9;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.uber.ussi.comparatornormalizer.IdentityComparatorNormalizer;
import com.uber.ussi.comparatornormalizer.ReciprocalComparatorNormalizer;
import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValuesTestFactory;
import com.uber.ussi.error.ArraysSizeMismatchError;
import com.uber.ussi.error.ComparatorCreationError;
import com.uber.ussi.utils.ConfigKeys;
import com.uber.ussi.utils.MathUtils;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ComparatorTest {


  private static Comparator l2Comparator() {
    return ComparatorFactory.createComparator("l2", Map.of(), new ReciprocalComparatorNormalizer());
  }

  private static LongTermsAndValues denseVector(float[] values, double uniValue) {
    return LongTermsAndValuesTestFactory.create(new long[0], values, uniValue);
  }

  @Test
  void getUniTransformedValueSquaresValue() {
    assertEquals(9.0, l2Comparator().getUniTransformedValue(3f), EPSILON_9);
  }

  @Test
  void constructorRejectsNullNormalizer() {
    assertThrows(NullPointerException.class, () -> new L2Comparator(null));
  }

  @Test
  void computeUniValueSumsSquaredValues() {
    assertEquals(25.0, l2Comparator().computeUniValue(new float[] {3f, 4f}), EPSILON_9);
  }

  @Test
  void computeUniValueReadsValuesFromTermsAndValues() {
    LongTermsAndValues vector = denseVector(new float[] {3f, 4f}, 25.0);

    assertEquals(25.0, l2Comparator().computeUniValue(vector), EPSILON_9);
  }

  @Test
  void getSimilarityIsOneForIdenticalVectors() {
    LongTermsAndValues vector = denseVector(new float[] {1f, 0f}, 1.0);

    assertEquals(1.0, l2Comparator().getSimilarity(vector, vector, 0.0), EPSILON_9);
  }

  @Test
  void getSimilarityRejectsUnsetUniValue() {
    LongTermsAndValues unset = denseVector(new float[] {1f, 0f}, Comparator.UNSET_UNI_VALUE);
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

    assertEquals(0.0, l2Comparator().getSimilarity(large, small, 0.9), EPSILON_9);
  }

  @Test
  void factoryCreatesComparatorFromNamespaceConfig() {
    NamespaceConfig config =
        NamespaceConfig.builder()
            .maxCacheSize(10)
            .cacheType("scan")
            .indexType("matrix")
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
  void getMaxPrefixSumValidatesInputs() {
    Comparator comparator = l2Comparator();

    assertThrows(
        IllegalArgumentException.class,
        () -> comparator.getMaxPrefixSumForTermsAndValues(Comparator.UNSET_UNI_VALUE, 0.5));
    assertThrows(
        IllegalArgumentException.class,
        () -> comparator.getMaxPrefixSumForTermsAndValues(1.0, -0.1));
    assertThrows(
        IllegalArgumentException.class,
        () -> comparator.getMaxPrefixSumForTermsAndValues(1.0, 1.1));
  }

  @Test
  void l2BoundsNoShareOfItsKeys() {
    assertTrue(!(l2Comparator() instanceof KeyShareBounded));
  }

  /**
   * A measure either caps the prefix by a share of its keys or by counting them, and which one it
   * does shows in whether the cap moves with the keys' Uni value: a share of twice as many keys is
   * twice as much, while an edit count or a squared distance is the same quantity however long the
   * record.
   *
   * <p>For the share-shaped three the cap over terms is that shape applied to the very fraction
   * their signatures are bounded by, since keys are shared in proportion to the multiset
   * similarity of the records they come from whether they are terms or signatures. Each of the
   * three used to restate the shape itself, NGLD by re-deriving that fraction's algebra in a
   * second form, so this is the identity that lets one shape serve both key spaces.
   */
  @Test
  void aMeasureCapsThePrefixByAShareOfItsKeysOrByCountingThem() {
    for (String comparatorType : List.of("jaccard", "ruzicka", "ngld")) {
      Comparator comparator = comparator(comparatorType);
      KeyShareBounded bounded = (KeyShareBounded) comparator;
      for (double comparatorValue : List.of(0.0, 0.05, 0.25, 0.5, 0.9, 1.0)) {
        for (double uniValue : List.of(1.0, 5.0, 17.0, 1000.0)) {
          String where = comparatorType + " at " + comparatorValue + " over " + uniValue;
          double cap =
              comparator.getMaxPrefixSumForTermsAndValuesInternal(uniValue, comparatorValue);

          assertEquals(
              Comparator.maxPrefixSumFromSharedFraction(
                  uniValue, bounded.getMinSharedKeyFraction(uniValue, comparatorValue)),
              cap,
              EPSILON_9,
              where);
          assertEquals(
              2.0 * cap,
              comparator.getMaxPrefixSumForTermsAndValuesInternal(
                  2.0 * uniValue, comparatorValue),
              EPSILON_9,
              where + ", a share, should double with the keys");
        }
      }
    }

    // A budget of 3 well inside both records, so neither cap is the whole of the record instead.
    for (String comparatorType : List.of("gld", "l2")) {
      Comparator comparator = comparator(comparatorType);

      assertEquals(
          comparator.getMaxPrefixSumForTermsAndValuesInternal(100.0, 3.0),
          comparator.getMaxPrefixSumForTermsAndValuesInternal(200.0, 3.0),
          EPSILON_9,
          comparatorType + ", a count, should not move with the keys");
    }
  }

  /** Sequence measures need a distance to bound with; the rest reject the parameter. */
  private static Comparator comparator(String comparatorType) {
    Map<String, String> comparatorParams =
        List.of("gld", "ngld").contains(comparatorType)
            ? Map.of(ConfigKeys.SEQUENCE_DISTANCE_TYPE, "levenshtein")
            : Map.of();
    return ComparatorFactory.createComparator(
        comparatorType, comparatorParams, new IdentityComparatorNormalizer());
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
