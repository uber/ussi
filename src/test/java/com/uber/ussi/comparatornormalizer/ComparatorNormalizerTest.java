package com.uber.ussi.comparatornormalizer;

import static com.uber.ussi.utils.MathUtils.EPSILON_9;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.uber.ussi.error.ComparatorNormalizerCreationError;
import com.uber.ussi.error.SearchResponseError;
import com.uber.ussi.utils.MathUtils;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ComparatorNormalizerTest {


  @Test
  void identityNormalizerIsIdentityWithinRange() {
    IdentityComparatorNormalizer normalizer = new IdentityComparatorNormalizer();

    assertEquals(0.4, normalizer.comparatorValueToNormalizedSimilarityValue(0.4), EPSILON_9);
    assertEquals(0.4, normalizer.normalizedSimilarityValueToComparatorValue(0.4), EPSILON_9);
  }

  @Test
  void identityNormalizerRejectsOutOfRangeComparatorValue() {
    IdentityComparatorNormalizer normalizer = new IdentityComparatorNormalizer();

    assertThrows(
        SearchResponseError.class,
        () -> normalizer.comparatorValueToNormalizedSimilarityValue(1.5));
  }

  @Test
  void identityNormalizerRejectsOutOfRangeSimilarityValue() {
    IdentityComparatorNormalizer normalizer = new IdentityComparatorNormalizer();

    assertThrows(
        SearchResponseError.class,
        () -> normalizer.normalizedSimilarityValueToComparatorValue(-0.1));
  }

  /** A complement only inverts a value that is already normalized, so it has no other domain. */
  @Test
  void complementNormalizerInvertsWithinRangeAndRejectsOutOfRange() {
    ComplementComparatorNormalizer normalizer = new ComplementComparatorNormalizer();

    assertEquals(0.6, normalizer.comparatorValueToNormalizedSimilarityValue(0.4), EPSILON_9);
    assertEquals(0.6, normalizer.normalizedSimilarityValueToComparatorValue(0.4), EPSILON_9);
    assertThrows(
        SearchResponseError.class,
        () -> normalizer.comparatorValueToNormalizedSimilarityValue(1.5));
    assertThrows(
        SearchResponseError.class,
        () -> normalizer.comparatorValueToNormalizedSimilarityValue(-0.1));
    assertThrows(
        SearchResponseError.class,
        () -> normalizer.normalizedSimilarityValueToComparatorValue(1.5));
    assertThrows(
        SearchResponseError.class,
        () -> normalizer.normalizedSimilarityValueToComparatorValue(-0.1));
  }

  @Test
  void lpNormalizerMapsComparatorValueToSimilarity() {
    LpComparatorNormalizer normalizer = new LpComparatorNormalizer();

    assertEquals(0.5, normalizer.comparatorValueToNormalizedSimilarityValue(1.0), EPSILON_9);
  }

  @Test
  void lpNormalizerInvertsSimilarityToComparatorValue() {
    LpComparatorNormalizer normalizer = new LpComparatorNormalizer();

    assertEquals(1.0, normalizer.normalizedSimilarityValueToComparatorValue(0.5), EPSILON_9);
  }

  @Test
  void lpNormalizerRejectsOutOfRangeComparatorValue() {
    LpComparatorNormalizer normalizer = new LpComparatorNormalizer();

    assertThrows(
        SearchResponseError.class,
        () -> normalizer.comparatorValueToNormalizedSimilarityValue(3.0));
  }

  @Test
  void lpNormalizerRejectsOutOfRangeSimilarityValue() {
    LpComparatorNormalizer normalizer = new LpComparatorNormalizer();

    assertThrows(
        SearchResponseError.class,
        () -> normalizer.normalizedSimilarityValueToComparatorValue(1.5));
  }

  @Test
  void reciprocalNormalizerMapsComparatorValueToSimilarity() {
    ReciprocalComparatorNormalizer normalizer = new ReciprocalComparatorNormalizer();

    assertEquals(0.5, normalizer.comparatorValueToNormalizedSimilarityValue(1.0), EPSILON_9);
  }

  @Test
  void reciprocalNormalizerInvertsSimilarityToComparatorValue() {
    ReciprocalComparatorNormalizer normalizer = new ReciprocalComparatorNormalizer();

    assertEquals(1.0, normalizer.normalizedSimilarityValueToComparatorValue(0.5), EPSILON_9);
  }

  @Test
  void reciprocalNormalizerRejectsOutOfRangeSimilarityValue() {
    ReciprocalComparatorNormalizer normalizer = new ReciprocalComparatorNormalizer();

    assertThrows(
        SearchResponseError.class,
        () -> normalizer.normalizedSimilarityValueToComparatorValue(2.0));
  }

  @Test
  void reciprocalNormalizerRejectsOutOfRangeComparatorValue() {
    ReciprocalComparatorNormalizer normalizer = new ReciprocalComparatorNormalizer();

    assertThrows(
        SearchResponseError.class,
        () -> normalizer.comparatorValueToNormalizedSimilarityValue(-2.0));
  }

  @Test
  void factoryCreatesIdentityNormalizer() {
    assertInstanceOf(
        IdentityComparatorNormalizer.class,
        ComparatorNormalizerFactory.createComparatorNormalizer("identity", Map.of()));
  }

  @Test
  void factoryCreatesLpNormalizer() {
    assertInstanceOf(
        LpComparatorNormalizer.class,
        ComparatorNormalizerFactory.createComparatorNormalizer("LP", Map.of()));
  }

  @Test
  void factoryCreatesReciprocalNormalizer() {
    assertInstanceOf(
        ReciprocalComparatorNormalizer.class,
        ComparatorNormalizerFactory.createComparatorNormalizer("Reciprocal", Map.of()));
  }

  @Test
  void factoryRejectsUnsupportedType() {
    assertThrows(
        ComparatorNormalizerCreationError.class,
        () -> ComparatorNormalizerFactory.createComparatorNormalizer("nope", Map.of()));
  }
}
