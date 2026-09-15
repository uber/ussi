package com.uber.ussi.comparator;

import static com.uber.ussi.utils.MathUtils.EPSILON_9;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.uber.ussi.comparatornormalizer.ComparatorNormalizer;
import com.uber.ussi.comparatornormalizer.IdentityComparatorNormalizer;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValuesTestFactory;
import com.uber.ussi.entity.termsandvalues.RecordType;
import com.uber.ussi.error.ComparatorCreationError;
import com.uber.ussi.utils.ConfigKeys;
import com.uber.ussi.utils.MathUtils;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class SparseComparatorTest {

  @Test
  void jaccardComputesIntersectionOverUnion() {
    Comparator comparator = comparator("jaccard");
    LongTermsAndValues first = sparse(comparator, new long[] {1L, 2L, 3L}, 1f, 5f, 2f);
    LongTermsAndValues second = sparse(comparator, new long[] {2L, 3L, 4L}, 1f, -4f, 8f);

    assertEquals(1.0 / 5.0, comparator.getSimilarity(first, second, 0.0), EPSILON_9);
  }

  @Test
  void ruzickaComputesWeightedIntersectionOverUnion() {
    Comparator comparator = comparator("ruzicka");
    LongTermsAndValues first = sparse(comparator, new long[] {1L, 2L}, 2f, 3f);
    LongTermsAndValues second = sparse(comparator, new long[] {1L, 2L}, 1f, 5f);

    assertEquals(4.0 / 7.0, comparator.getSimilarity(first, second, 0.0), EPSILON_9);
  }

  @Test
  void ruzickaTreatsOppositeSignsAsDistinctElements() {
    Comparator comparator = comparator("ruzicka");
    LongTermsAndValues first = sparse(comparator, new long[] {1L}, 2f);
    LongTermsAndValues second = sparse(comparator, new long[] {1L}, -2f);

    assertEquals(0.0, comparator.getSimilarity(first, second, 0.0), EPSILON_9);
  }

  @Test
  void ruzickaComparesDenseVectorsPositionally() {
    Comparator comparator = comparator("ruzicka");
    LongTermsAndValues first = dense(comparator, 2f, 3f);
    LongTermsAndValues second = dense(comparator, 1f, 5f);

    assertEquals(4.0 / 7.0, comparator.getSimilarity(first, second, 0.0), EPSILON_9);
  }

  /** A zero coordinate is absent, so a dense record's populated positions are its elements. */
  @Test
  void jaccardComparesDenseVectorsByPopulatedPosition() {
    Comparator comparator = comparator("jaccard");
    LongTermsAndValues first = dense(comparator, 1f, 0f, 2f);
    LongTermsAndValues second = dense(comparator, 3f, 4f, 0f);

    assertEquals(1.0 / 3.0, comparator.getSimilarity(first, second, 0.0), EPSILON_9);
  }

  /**
   * Both measures align a dense record as readily as a sparse one, so both declare it and a
   * namespace can pair them with any structure that stores either.
   */
  @Test
  void bothMeasuresReadSparseAndDenseRecords() {
    Set<RecordType> orderAgnosticTypes =
        Set.of(RecordType.SPARSE, RecordType.DENSE);

    assertEquals(orderAgnosticTypes, comparator("jaccard").getSupportedRecordTypes());
    assertEquals(orderAgnosticTypes, comparator("ruzicka").getSupportedRecordTypes());
  }

  @Test
  void lengthAndPositionFilteringPreserveThresholdSemantics() {
    Comparator comparator = comparator("ruzicka");
    LongTermsAndValues first = sparse(comparator, new long[] {1L, 2L}, 1f, 1f);
    LongTermsAndValues second = sparse(comparator, new long[] {2L, 3L}, 1f, 1f);

    assertFalse(comparator.mayPassLengthFiltering(2.0, 10.0, 0.5));
    assertTrue(comparator.mayPassLengthFiltering(2.0, 3.0, 0.5));
    assertEquals(1.0 / 3.0, comparator.getSimilarity(first, second, 1.0 / 3.0), EPSILON_9);
    assertEquals(0.0, comparator.getSimilarity(first, second, 0.34), EPSILON_9);
  }

  @Test
  void numTermsFilteringUsesJaccardBoundsAndConservativeDefaults() {
    Comparator jaccard = comparator("jaccard");
    LongTermsAndValues query = sparse(jaccard, sequentialTerms(100), repeatedValue(1.0f, 100));

    assertTrue(jaccard.mayPassNumTermsFiltering(query, 0, 270, 0.5));
    assertFalse(jaccard.mayPassNumTermsFiltering(query, 271, Integer.MAX_VALUE, 0.5));
    assertFalse(
        jaccard.mayPassNumTermsFiltering(
            sparse(jaccard, sequentialTerms(600), repeatedValue(1.0f, 600)), 0, 270, 0.5));
    assertTrue(jaccard.mayPassNumTermsFiltering(query, 271, Integer.MAX_VALUE, 0.0));
    assertTrue(
        jaccard.mayPassNumTermsFiltering(
            LongTermsAndValuesTestFactory.create(new long[] {1}, new float[] {0.0f}, 0.0),
            271,
            Integer.MAX_VALUE,
            1.0));

    Comparator ruzicka = comparator("ruzicka");
    assertTrue(
        ruzicka.mayPassNumTermsFiltering(
            sparse(ruzicka, new long[] {1}, 1.0f), 271, Integer.MAX_VALUE, 1.0));
  }

  @Test
  void numTermsFilteringValidatesArgumentsAndComparatorThresholds() {
    Comparator jaccard = comparator("jaccard");
    LongTermsAndValues query = sparse(jaccard, new long[] {1}, 1.0f);

    assertThrows(
        NullPointerException.class, () -> jaccard.mayPassNumTermsFiltering(null, 0, 1, 0.5));
    assertThrows(
        IllegalArgumentException.class, () -> jaccard.mayPassNumTermsFiltering(query, -1, 1, 0.5));
    assertThrows(
        IllegalArgumentException.class, () -> jaccard.mayPassNumTermsFiltering(query, 2, 1, 0.5));
    assertThrows(
        IllegalArgumentException.class, () -> jaccard.mayPassNumTermsFiltering(query, 0, 1, 1.1));

    ComparatorNormalizer outOfRangeNormalizer =
        new ComparatorNormalizer() {
          @Override
          public double comparatorValueToNormalizedSimilarityValue(double comparatorValue) {
            return comparatorValue;
          }

          @Override
          public double normalizedSimilarityValueToComparatorValue(double normalizedSimilarity) {
            return 2.0;
          }
        };
    JaccardComparator outOfRangeJaccard =
        new JaccardComparator(outOfRangeNormalizer);
    assertFalse(outOfRangeJaccard.mayPassNumTermsFiltering(query, 0, 1, 0.5));
  }

  @Test
  void prefixSumMatchesOssFormula() {
    Comparator comparator = comparator("jaccard");

    assertEquals(
        2.0 + MathUtils.EPSILON_12, comparator.getMaxPrefixSumForTermsAndValues(4.0, 0.5), 0.0);
  }

  /**
   * These measures are themselves the multiset similarity the signatures collide at, so the
   * threshold needs no conversion and the record's own Uni value says nothing extra.
   */
  @Test
  void theSharedSignatureBoundIsTheThresholdItself() {
    BaseRuzickaComparator comparator = (BaseRuzickaComparator) comparator("jaccard");

    assertEquals(0.5, comparator.getMinSharedSignatureFraction(8.0, 0.5), 0.0);
    assertEquals(0.5, comparator.getMinSharedSignatureFraction(4096.0, 0.5), 0.0);
  }

  @Test
  void comparatorFactoryValidatesSignatureCompatibility() {
    assertThrows(
        ComparatorCreationError.class,
        () ->
            ComparatorFactory.createComparator(
                "jaccard",
                Map.of(ConfigKeys.SIGNATURE_GENERATOR, "icws"),
                new IdentityComparatorNormalizer()));
    assertThrows(
        ComparatorCreationError.class,
        () ->
            ComparatorFactory.createComparator(
                "ruzicka",
                Map.of(ConfigKeys.SIGNATURE_GENERATOR, "minhash"),
                new IdentityComparatorNormalizer()));
    assertThrows(
        ComparatorCreationError.class,
        () ->
            ComparatorFactory.createComparator(
                "jaccard",
                Map.of(ConfigKeys.SIGNATURE_GENERATOR, "unknown"),
                new IdentityComparatorNormalizer()));
    assertThrows(
        ComparatorCreationError.class,
        () ->
            ComparatorFactory.createComparator(
                "l2",
                Map.of(ConfigKeys.SIGNATURE_GENERATOR, "icws"),
                new IdentityComparatorNormalizer()));
  }

  @Test
  void theSharedSignatureBoundRejectsANegativeThreshold() {
    BaseRuzickaComparator comparator = (BaseRuzickaComparator) comparator("ruzicka");

    assertThrows(
        IllegalArgumentException.class,
        () -> comparator.getMinSharedSignatureFraction(4.0, -0.1));
  }

  @Test
  void maxPossibleComparatorValueRejectsInvalidPartialState() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            BaseRuzickaComparator.computeMaxPossibleComparatorValue(2.0, 1.0, 0.0, 1.0, 0.0, 0.0));
  }

  @Test
  void ruzickaPrefixSumRejectsNegativeComparatorValue() {
    BaseRuzickaComparator comparator = (BaseRuzickaComparator) comparator("ruzicka");

    assertThrows(
        IllegalArgumentException.class,
        () -> comparator.getMaxPrefixSumForTermsAndValuesInternal(1.0, -0.1));
  }

  /** No magnitude means an empty union, which no ratio is defined over, so they are identical. */
  @Test
  void twoRecordsWithNothingToUnionAreIdentical() {
    BaseRuzickaComparator comparator = (BaseRuzickaComparator) comparator("ruzicka");

    assertEquals(
        1.0,
        comparator.similarityFromConjunction(
            /* conjunction */ 0.0,
            /* partialUniValue1 */ 0.0,
            /* uniValue1 */ 0.0,
            /* partialUniValue2 */ 0.0,
            /* uniValue2 */ 0.0),
        EPSILON_9);
  }

  @Test
  void randomizedResultsMatchMapBasedOracle() {
    Random random = new Random(8128L);
    for (String comparatorType : new String[] {"jaccard", "ruzicka"}) {
      Comparator comparator = comparator(comparatorType);
      for (int iteration = 0; iteration < 200; ++iteration) {
        LongTermsAndValues first = randomSparseVector(comparator, random);
        LongTermsAndValues second = randomSparseVector(comparator, random);
        double expected = mapBasedSimilarity(comparator, first, second);

        assertEquals(expected, comparator.getSimilarity(first, second, 0.0), EPSILON_9);
        double threshold = Math.min(1.0, expected + 0.01);
        double thresholded = comparator.getSimilarity(first, second, threshold);
        assertEquals(expected >= threshold ? expected : 0.0, thresholded, EPSILON_9);
      }
    }
  }

  private static Comparator comparator(String type) {
    return ComparatorFactory.createComparator(type, Map.of(), new IdentityComparatorNormalizer());
  }

  private static LongTermsAndValues sparse(Comparator comparator, long[] terms, float... values) {
    return LongTermsAndValuesTestFactory.create(terms, values, comparator.computeUniValue(values));
  }

  private static LongTermsAndValues dense(Comparator comparator, float... values) {
    return LongTermsAndValuesTestFactory.create(
        new long[0], values, comparator.computeUniValue(values));
  }

  private static long[] sequentialTerms(int numTerms) {
    long[] terms = new long[numTerms];
    for (int i = 0; i < numTerms; ++i) {
      terms[i] = i + 1L;
    }
    return terms;
  }

  private static float[] repeatedValue(float value, int count) {
    float[] values = new float[count];
    java.util.Arrays.fill(values, value);
    return values;
  }

  private static LongTermsAndValues randomSparseVector(Comparator comparator, Random random) {
    long[] candidateTerms = new long[8];
    float[] candidateValues = new float[8];
    int size = 0;
    for (int term = 0; term < 8; ++term) {
      if (random.nextBoolean()) {
        candidateTerms[size] = term;
        int value;
        do {
          value = random.nextInt(5) - 2;
        } while (value == 0);
        candidateValues[size] = value;
        ++size;
      }
    }
    if (size == 0) {
      candidateTerms[0] = random.nextInt(8);
      candidateValues[0] = 1f;
      size = 1;
    }
    long[] terms = java.util.Arrays.copyOf(candidateTerms, size);
    float[] values = java.util.Arrays.copyOf(candidateValues, size);
    return sparse(comparator, terms, values);
  }

  private static double mapBasedSimilarity(
      Comparator comparator, LongTermsAndValues first, LongTermsAndValues second) {
    TreeMap<Long, Float> firstValues = asMap(first);
    TreeMap<Long, Float> secondValues = asMap(second);
    TreeSet<Long> terms = new TreeSet<>(firstValues.keySet());
    terms.addAll(secondValues.keySet());
    double intersection = 0.0;
    double union = 0.0;
    for (long term : terms) {
      float firstValue = firstValues.getOrDefault(term, 0f);
      float secondValue = secondValues.getOrDefault(term, 0f);
      double transformedFirst = comparator.getUniTransformedValue(firstValue);
      double transformedSecond = comparator.getUniTransformedValue(secondValue);
      if (Math.signum(firstValue) == Math.signum(secondValue)) {
        intersection += Math.min(transformedFirst, transformedSecond);
        union += Math.max(transformedFirst, transformedSecond);
      } else {
        union += transformedFirst + transformedSecond;
      }
    }
    return union == 0.0 ? 1.0 : intersection / union;
  }

  private static TreeMap<Long, Float> asMap(LongTermsAndValues termsAndValues) {
    TreeMap<Long, Float> values = new TreeMap<>();
    for (int i = 0; i < termsAndValues.termsLength(); ++i) {
      values.put(termsAndValues.getTerm(i), termsAndValues.getValue(i));
    }
    return values;
  }
}
