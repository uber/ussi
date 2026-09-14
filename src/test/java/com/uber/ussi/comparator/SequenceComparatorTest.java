package com.uber.ussi.comparator;

import static com.uber.ussi.utils.MathUtils.EPSILON_9;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.uber.ussi.comparator.sequencedistance.SequenceDistanceFactory.SequenceDistanceType;
import com.uber.ussi.comparatornormalizer.ComparatorNormalizer;
import com.uber.ussi.comparatornormalizer.ComparatorNormalizerFactory;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;
import com.uber.ussi.entity.termsandvalues.TermsAndValues;
import com.uber.ussi.error.ComparatorCreationError;
import com.uber.ussi.utils.Constants;
import com.uber.ussi.utils.MathUtils;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class SequenceComparatorTest {

  /** Sequences are spelled as strings, one term per character, in order and with repeats. */
  private static final SimilarityCase[] SIMILARITY_CASES = {
    // NGLD divides by the lengths, so the same edit counts for less between longer sequences.
    new SimilarityCase("ngld", "complement", "levenshtein", "kitten", "sitting", 0.625),
    new SimilarityCase("ngld", "complement", "levenshtein", "abc", "abd", 5.0 / 7.0),
    new SimilarityCase("ngld", "complement", "levenshtein", "abc", "abc", 1.0),
    // Every element is an insertion, the worst a pair can do.
    new SimilarityCase("ngld", "complement", "levenshtein", "abc", "", 0.0),
    // GLD reports the edit count itself, so the same count scores the same at any length.
    new SimilarityCase("gld", "reciprocal", "levenshtein", "kitten", "sitting", 0.25),
    new SimilarityCase("gld", "reciprocal", "levenshtein", "abc", "abd", 0.5),
    new SimilarityCase("gld", "reciprocal", "levenshtein", "abc", "abc", 1.0),
    new SimilarityCase("gld", "reciprocal", "levenshtein", "abc", "", 0.25),
    // A swap is one transposition, or two substitutions without one, or two indels without those.
    new SimilarityCase("gld", "reciprocal", "damerau_levenshtein", "ab", "ba", 0.5),
    new SimilarityCase("gld", "reciprocal", "levenshtein", "ab", "ba", 1.0 / 3.0),
    new SimilarityCase("gld", "reciprocal", "lcs", "ab", "ba", 1.0 / 3.0),
    // Without substitution a rewritten element costs a delete and an insert instead of one edit.
    new SimilarityCase("gld", "reciprocal", "lcs", "abc", "abd", 1.0 / 3.0),
    new SimilarityCase("ngld", "complement", "lcs", "abc", "abd", 0.5),
    // Reciprocal compresses NGLD into [0.5, 1.0].
    new SimilarityCase("ngld", "reciprocal", "levenshtein", "kitten", "sitting", 1.0 / 1.375),
  };

  private static final LengthFilteringCase[] LENGTH_FILTERING_CASES = {
    // At 0.5 similarity a 4-element query tolerates a distance of 0.5 * (4 + length2) / 1.5, which
    // reaches 2 only at 2 elements; shorter candidates differ by more and are rejected.
    new LengthFilteringCase("ngld", "complement", 4, 4, 0.5, true),
    new LengthFilteringCase("ngld", "complement", 4, 2, 0.5, true),
    new LengthFilteringCase("ngld", "complement", 4, 1, 0.5, false),
    new LengthFilteringCase("ngld", "complement", 4, 0, 0.5, false),
    // An exact match tolerates no distance at all, so only equal lengths survive.
    new LengthFilteringCase("ngld", "complement", 4, 4, 1.0, true),
    new LengthFilteringCase("ngld", "complement", 4, 3, 1.0, false),
    // GLD's budget is an absolute edit count, so it bounds the length gap directly.
    new LengthFilteringCase("gld", "reciprocal", 10, 8, 1.0 / 3.0, true),
    new LengthFilteringCase("gld", "reciprocal", 10, 7, 1.0 / 3.0, false),
    // A similarity of zero asks for no filtering, however far apart the lengths are.
    new LengthFilteringCase("gld", "reciprocal", 1000, 0, 0.0, true),
    new LengthFilteringCase("ngld", "complement", 1000, 0, 0.0, true),
  };

  @Test
  void similarityCases() {
    for (SimilarityCase testCase : SIMILARITY_CASES) {
      Comparator comparator =
          createComparator(
              testCase.comparatorType, testCase.normalizerType, testCase.distanceType);
      LongTermsAndValues sequence1 = sequence(comparator, testCase.sequence1);
      LongTermsAndValues sequence2 = sequence(comparator, testCase.sequence2);

      assertEquals(
          testCase.expectedSimilarity,
          comparator.getSimilarity(sequence1, sequence2, 0.0),
          EPSILON_9,
          testCase.label());
      // An edit distance is symmetric, so the comparator has to be too.
      assertEquals(
          testCase.expectedSimilarity,
          comparator.getSimilarity(sequence2, sequence1, 0.0),
          EPSILON_9,
          testCase.label() + " reversed");
    }
  }

  /**
   * Early exit only has to report a similarity below the threshold, so each case is checked just
   * above and just below its own.
   */
  @Test
  void similarityCasesRespectTheThresholdTheyAreGiven() {
    for (SimilarityCase testCase : SIMILARITY_CASES) {
      Comparator comparator =
          createComparator(
              testCase.comparatorType, testCase.normalizerType, testCase.distanceType);
      LongTermsAndValues sequence1 = sequence(comparator, testCase.sequence1);
      LongTermsAndValues sequence2 = sequence(comparator, testCase.sequence2);

      if (testCase.expectedSimilarity > 0.0) {
        double reachableThreshold = testCase.expectedSimilarity - EPSILON_9;
        assertEquals(
            testCase.expectedSimilarity,
            comparator.getSimilarity(sequence1, sequence2, reachableThreshold),
            EPSILON_9,
            testCase.label() + " at a threshold it reaches");
      }
      if (testCase.expectedSimilarity < 1.0) {
        double unreachableThreshold = testCase.expectedSimilarity + 1e-3;
        assertTrue(
            comparator.getSimilarity(sequence1, sequence2, unreachableThreshold)
                < unreachableThreshold,
            testCase.label() + " at a threshold it misses");
      }
    }
  }

  @Test
  void lengthFilteringCases() {
    for (LengthFilteringCase testCase : LENGTH_FILTERING_CASES) {
      Comparator comparator =
          createComparator(testCase.comparatorType, testCase.normalizerType, "levenshtein");

      boolean mayPass =
          comparator.mayPassLengthFiltering(
              testCase.length1, testCase.length2, testCase.minSimilarity);

      assertEquals(testCase.expectedMayPass, mayPass, testCase.label());
    }
  }

  /** Length filtering may only reject pairs the comparator would score below the threshold. */
  @Test
  void lengthFilteringOnlyRejectsPairsBelowTheThreshold() {
    String alphabet = "abc";
    Random random = new Random(7_314L);
    for (String comparatorType : List.of("gld", "ngld")) {
      String normalizerType = comparatorType.equals("gld") ? "reciprocal" : "complement";
      for (String distanceType : List.of("levenshtein", "damerau_levenshtein", "lcs")) {
        Comparator comparator = createComparator(comparatorType, normalizerType, distanceType);
        for (double minSimilarity : new double[] {0.1, 0.4, 0.6, 0.9}) {
          for (int trial = 0; trial < 200; ++trial) {
            LongTermsAndValues sequence1 =
                sequence(comparator, randomSequence(random, alphabet, 8));
            LongTermsAndValues sequence2 =
                sequence(comparator, randomSequence(random, alphabet, 8));
            if (sequence1.termsLength() == 0 && sequence2.termsLength() == 0) {
              continue;
            }

            if (!comparator.mayPassLengthFiltering(
                sequence1.getUniValue(), sequence2.getUniValue(), minSimilarity)) {
              assertTrue(
                  comparator.getSimilarity(sequence1, sequence2, 0.0) < minSimilarity,
                  String.format(
                      "%s/%s rejected %s and %s at minSimilarity %s, but they score %s.",
                      comparatorType,
                      distanceType,
                      sequence1,
                      sequence2,
                      minSimilarity,
                      comparator.getSimilarity(sequence1, sequence2, 0.0)));
            }
          }
        }
      }
    }
  }

  @Test
  void theUniValueOfASequenceIsItsLength() {
    Comparator comparator = createComparator("ngld", "complement", "levenshtein");

    // "banana" repeats elements, which a multiset would collapse but a sequence length counts.
    assertEquals(6.0, sequence(comparator, "banana").getUniValue(), EPSILON_9);
    assertEquals(0.0, sequence(comparator, "").getUniValue(), EPSILON_9);
  }

  @Test
  void aSequenceKeepsItsElementsInOrderWithRepeats() {
    Comparator comparator = createComparator("ngld", "complement", "levenshtein");

    LongTermsAndValues sequence = sequence(comparator, "banana");

    assertEquals(6, sequence.termsLength());
    assertEquals(0, sequence.valuesLength());
    assertEquals('b', sequence.getTerm(0));
    assertEquals('a', sequence.getTerm(1));
    assertEquals('n', sequence.getTerm(2));
  }

  @Test
  void sequenceComparatorsRejectSignatureGeneration() {
    for (String comparatorType : List.of("gld", "ngld")) {
      ComparatorCreationError error =
          assertThrows(
              ComparatorCreationError.class,
              () ->
                  ComparatorFactory.createComparator(
                      comparatorType,
                      Map.of(Constants.SIGNATURE_GENERATOR, "minhash"),
                      normalizer("complement")),
              comparatorType);

      assertTrue(
          error.getMessage().contains("does not support signature generation"),
          error.getMessage());
    }
  }

  @Test
  void anUnknownSequenceDistanceTypeIsRejected() {
    assertThrows(
        ComparatorCreationError.class,
        () ->
            ComparatorFactory.createComparator(
                "ngld",
                Map.of(Constants.SEQUENCE_DISTANCE_TYPE, "hamming"),
                normalizer("complement")));
  }

  @Test
  void theDistanceTypeDefaultsToLevenshtein() {
    Comparator withDefault =
        ComparatorFactory.createComparator("ngld", Map.of(), normalizer("complement"));
    Comparator explicit = createComparator("ngld", "complement", "levenshtein");

    // The default is not observable directly, so compare against the distance it should be.
    assertEquals(
        ((BaseSequenceComparator) explicit).getSequenceDistance().getClass(),
        ((BaseSequenceComparator) withDefault).getSequenceDistance().getClass());
  }

  @Test
  void everyDistanceTypeIsReachableThroughTheComparatorParam() {
    for (SequenceDistanceType type : SequenceDistanceType.values()) {
      Comparator comparator =
          createComparator("ngld", "complement", type.name().toLowerCase(Locale.ROOT));

      assertEquals(
          1.0,
          comparator.getSimilarity(sequence(comparator, "abc"), sequence(comparator, "abc"), 0.0),
          EPSILON_9,
          type.name());
    }
  }

  @Test
  void normalizedDistanceCases() {
    for (NormalizationCase testCase : NORMALIZATION_CASES) {
      assertEquals(
          testCase.expected,
          NgldComparator.getNormalizedDistance(
              testCase.distance, testCase.length1, testCase.length2),
          EPSILON_9,
          testCase.toString());
    }
  }

  /** The budget must be the exact inverse of the normalization, so no genuine match is rejected. */
  @Test
  void maxDistanceAdmitsExactlyTheDistancesThatNormalizeWithinTheThreshold() {
    for (int length1 = 0; length1 <= 12; ++length1) {
      for (int length2 = 0; length2 <= 12; ++length2) {
        for (double maxNormalizedDistance : new double[] {0.0, 0.1, 0.25, 1.0 / 3.0, 0.5, 0.8}) {
          long budget =
              NgldComparator.getDenormalizedMaxDistance(maxNormalizedDistance, length1, length2);
          String label =
              "lengths=" + length1 + "," + length2 + " maxNgld=" + maxNormalizedDistance;

          assertTrue(
              NgldComparator.getNormalizedDistance(budget, length1, length2)
                  <= maxNormalizedDistance + EPSILON_9,
              label + " admits its own budget");
          assertFalse(
              NgldComparator.getNormalizedDistance(budget + 1, length1, length2)
                  <= maxNormalizedDistance + EPSILON_9,
              label + " rejects one past its budget");
        }
      }
    }
  }

  /** Shared elements only bound an order-sensitive distance, so merging cannot score rows. */
  @Test
  void sequenceComparatorsCannotGenerateCandidatesByMerging() {
    for (String comparatorType : List.of("gld", "ngld")) {
      Comparator comparator = createComparator(comparatorType, "reciprocal", "levenshtein");

      assertFalse(comparator.supportsMergeCandidateGeneration(), comparatorType);
      assertFalse(comparator.doesSuffixBoundConjunction(), comparatorType);
      assertThrows(
          UnsupportedOperationException.class,
          () -> comparator.conjunctionContribution(1f, 1f),
          comparatorType);
      assertThrows(
          UnsupportedOperationException.class,
          () -> comparator.similarityFromConjunction(1.0, 1.0, 1.0, 1.0, 1.0),
          comparatorType);
      assertThrows(
          UnsupportedOperationException.class,
          () -> comparator.maxSimilarityFromPartialConjunction(1.0, 1.0, 1.0, 1.0, 1.0, 1.0),
          comparatorType);
    }
  }

  /** The capability is what a config is validated against, so it must match what is implemented. */
  @Test
  void theValueComparatorsReportThatTheySupportMerging() {
    for (String comparatorType : List.of("l2", "jaccard", "ruzicka")) {
      Comparator comparator =
          ComparatorFactory.createComparator(comparatorType, Map.of(), normalizer("reciprocal"));

      assertTrue(comparator.supportsMergeCandidateGeneration(), comparatorType);
      // Implemented rather than throwing.
      comparator.conjunctionContribution(1f, 1f);
    }
  }

  /** A length gap wider than the distance budget rejects the pair without running the program. */
  @Test
  void aPairTooFarApartInLengthIsRejectedWithoutMeasuringIt() {
    Comparator comparator = createComparator("ngld", "complement", "levenshtein");

    assertEquals(
        0.0,
        comparator.getSimilarity(
            sequence(comparator, "abcdefghij"), sequence(comparator, "a"), 0.9),
        EPSILON_9);
  }

  /**
   * The ordered sequence's Uni value is its length, and the multiset an index keys by has counts
   * summing to the same length.
   */
  @Test
  void bothFormsOfASequenceReportTheSameUniValue() {
    Comparator comparator = createComparator("ngld", "complement", "levenshtein");
    LongTermsAndValues ordered = sequence(comparator, "abcb");
    LongTermsAndValues multiset = ordered.toElementMultiset(comparator);

    assertEquals(4.0, comparator.computeUniValue(ordered), EPSILON_9);
    assertEquals(4.0, comparator.computeUniValue(multiset), EPSILON_9);
    assertEquals(
        4.0, comparator.computeUniValue(multiset.getTerms(), multiset.getValues()), EPSILON_9);
  }

  /** The prefix grows with the distance budget, so a negative budget has no prefix to report. */
  @Test
  void aNegativeDistanceBudgetLeavesNoPrefixToGenerateCandidatesFrom() {
    for (String comparatorType : List.of("gld", "ngld")) {
      BaseSequenceComparator comparator =
          (BaseSequenceComparator) createComparator(comparatorType, "reciprocal", "levenshtein");

      assertEquals(0.0, comparator.getMinPrefixSumForTermsAndValuesInternal(4.0, 0.0), EPSILON_9);
      assertThrows(
          IllegalArgumentException.class,
          () -> comparator.getMinPrefixSumForTermsAndValuesInternal(4.0, -1.0),
          comparatorType);
    }
  }

  @Test
  void aNegativeElementCountIsRejected() {
    Comparator comparator = createComparator("ngld", "complement", "levenshtein");

    assertEquals(2.0, comparator.getUniTransformedValue(2.0f), EPSILON_9);
    assertThrows(IllegalArgumentException.class, () -> comparator.getUniTransformedValue(-1.0f));
  }

  private static Comparator createComparator(
      String comparatorType, String normalizerType, String distanceType) {
    return ComparatorFactory.createComparator(
        comparatorType,
        Map.of(Constants.SEQUENCE_DISTANCE_TYPE, distanceType),
        normalizer(normalizerType));
  }

  private static ComparatorNormalizer normalizer(String normalizerType) {
    return ComparatorNormalizerFactory.createComparatorNormalizer(normalizerType, Map.of());
  }

  private static LongTermsAndValues sequence(Comparator comparator, String elements) {
    String[] terms = new String[elements.length()];
    for (int i = 0; i < elements.length(); ++i) {
      terms[i] = String.valueOf(elements.charAt(i));
    }
    return LongTermsAndValues.from(
        new TermsAndValues(terms, new float[0]), term -> term.charAt(0), comparator);
  }

  private static String randomSequence(Random random, String alphabet, int maxLength) {
    StringBuilder builder = new StringBuilder();
    int length = random.nextInt(maxLength + 1);
    for (int i = 0; i < length; ++i) {
      builder.append(alphabet.charAt(random.nextInt(alphabet.length())));
    }
    return builder.toString();
  }

  private record SimilarityCase(
      String comparatorType,
      String normalizerType,
      String distanceType,
      String sequence1,
      String sequence2,
      double expectedSimilarity) {

    private String label() {
      return String.format(
          "%s/%s/%s on \"%s\" and \"%s\"",
          comparatorType, normalizerType, distanceType, sequence1, sequence2);
    }
  }

  private static final NormalizationCase[] NORMALIZATION_CASES = {
    new NormalizationCase(0, 3, 3, 0.0),
    new NormalizationCase(3, 0, 3, 1.0),
    new NormalizationCase(2, 4, 4, 0.4),
    new NormalizationCase(0, 0, 0, 0.0),
  };

  private record NormalizationCase(long distance, int length1, int length2, double expected) {}

  private record LengthFilteringCase(
      String comparatorType,
      String normalizerType,
      int length1,
      int length2,
      double minSimilarity,
      boolean expectedMayPass) {

    private String label() {
      return String.format(
          "%s/%s on lengths %s and %s at minSimilarity %s",
          comparatorType, normalizerType, length1, length2, minSimilarity);
    }
  }
}
