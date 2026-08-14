package com.uber.ussi.comparatornormalizer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.uber.ussi.error.ComparatorNormalizerCreationError;
import com.uber.ussi.error.SearchResponseError;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ComparatorNormalizerTest {

  private static final double DELTA = 1e-9;

  @Test
  void identityNormalizerIsIdentityWithinRange() {
    IdentityComparatorNormalizer normalizer = new IdentityComparatorNormalizer();

    assertEquals(0.4, normalizer.comparatorValueToNormalizedSimilarityValue(0.4), DELTA);
    assertEquals(0.4, normalizer.normalizedSimilarityValueToComparatorValue(0.4), DELTA);
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

  @Test
  void lpNormalizerMapsComparatorValueToSimilarity() {
    LpComparatorNormalizer normalizer = new LpComparatorNormalizer();

    assertEquals(0.5, normalizer.comparatorValueToNormalizedSimilarityValue(1.0), DELTA);
  }

  @Test
  void lpNormalizerInvertsSimilarityToComparatorValue() {
    LpComparatorNormalizer normalizer = new LpComparatorNormalizer();

    assertEquals(1.0, normalizer.normalizedSimilarityValueToComparatorValue(0.5), DELTA);
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

    assertEquals(0.5, normalizer.comparatorValueToNormalizedSimilarityValue(1.0), DELTA);
  }

  @Test
  void reciprocalNormalizerInvertsSimilarityToComparatorValue() {
    ReciprocalComparatorNormalizer normalizer = new ReciprocalComparatorNormalizer();

    assertEquals(1.0, normalizer.normalizedSimilarityValueToComparatorValue(0.5), DELTA);
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
