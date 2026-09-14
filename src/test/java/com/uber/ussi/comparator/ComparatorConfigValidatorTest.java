package com.uber.ussi.comparator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.uber.ussi.comparator.sequencedistance.SequenceDistance;
import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValuesTestFactory;
import com.uber.ussi.error.ComparatorCreationError;
import com.uber.ussi.utils.Constants;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ComparatorConfigValidatorTest {

  private static final ValidationCase[] VALIDATION_CASES = {
    new ValidationCase("no signature param", builder -> builder, true, null),
    new ValidationCase(
        "minhash for jaccard",
        builder -> builder.comparatorParams(Map.of(Constants.SIGNATURE_GENERATOR_TYPE, "minhash")),
        true,
        null),
    new ValidationCase(
        "signature param on l2",
        builder ->
            builder
                .comparatorType("l2")
                .comparatorParams(Map.of(Constants.SIGNATURE_GENERATOR_TYPE, "minhash")),
        false,
        "L2 does not support signature generation."),
    new ValidationCase(
        "unknown signature type",
        builder ->
            builder.comparatorParams(Map.of(Constants.SIGNATURE_GENERATOR_TYPE, "superhash")),
        false,
        null),
    new ValidationCase(
        "unknown comparator type",
        builder -> builder.comparatorType("cosine"),
        false,
        "Unsupported comparator type (cosine)."),
    // Only the unknown name is reported, not what it cannot support.
    new ValidationCase(
        "unknown comparator type carrying a signature param",
        builder ->
            builder
                .comparatorType("cosine")
                .comparatorParams(Map.of(Constants.SIGNATURE_GENERATOR_TYPE, "minhash")),
        false,
        "Unsupported comparator type (cosine)."),
    new ValidationCase(
        "sequence distance type for ngld",
        builder ->
            builder
                .comparatorType("ngld")
                .comparatorNormalizerType("complement")
                .comparatorParams(Map.of(Constants.SEQUENCE_DISTANCE_TYPE, "levenshtein")),
        true,
        null),
    new ValidationCase(
        "sequence distance param on jaccard",
        builder ->
            builder.comparatorParams(Map.of(Constants.SEQUENCE_DISTANCE_TYPE, "levenshtein")),
        false,
        "JACCARD does not compare sequences, so it has no sequence distance type."),
    new ValidationCase(
        "unknown sequence distance type",
        builder ->
            builder
                .comparatorType("ngld")
                .comparatorNormalizerType("complement")
                .comparatorParams(Map.of(Constants.SEQUENCE_DISTANCE_TYPE, "hamming")),
        false,
        null),
    // A blank value is treated as unset, which a sequence comparator defaults.
    new ValidationCase(
        "blank sequence distance type",
        builder ->
            builder
                .comparatorType("ngld")
                .comparatorNormalizerType("complement")
                .comparatorParams(Map.of(Constants.SEQUENCE_DISTANCE_TYPE, "  ")),
        true,
        null),
    new ValidationCase(
        "minhash for ruzicka",
        builder ->
            builder
                .comparatorType("ruzicka")
                .comparatorParams(Map.of(Constants.SIGNATURE_GENERATOR_TYPE, "minhash")),
        false,
        null),
    new ValidationCase(
        "unsupported comparator normalizer type",
        builder -> builder.comparatorNormalizerType("softmax"),
        false,
        "Unsupported comparatorNormalizerType (softmax). Supported values: "
            + "complement, identity, lp, reciprocal."),
    // The normalizer is reported even when the comparator name cannot be answered for either.
    new ValidationCase(
        "unsupported comparator normalizer type on an unknown comparator",
        builder -> builder.comparatorType("cosine").comparatorNormalizerType("softmax"),
        false,
        "Unsupported comparator type (cosine)."),
  };

  @Test
  void validationCases() {
    for (ValidationCase testCase : VALIDATION_CASES) {
      List<String> violations = violations(testCase.build(validBuilder()));
      assertEquals(testCase.valid, violations.isEmpty(), testCase.name);
      if (testCase.expectedMessage != null) {
        assertTrue(violations.contains(testCase.expectedMessage), testCase.name);
      }
    }
  }

  /** A key validation resolves is one construction resolves, so the two cannot disagree. */
  @Test
  void aParamUnderADifferentlyCasedKeyIsReadByValidationAndConstructionAlike() {
    NamespaceConfig config =
        validBuilder()
            .comparatorType("l2")
            .comparatorParams(Map.of("Signature_Generator_Type", "minhash"))
            .build();

    assertEquals(List.of("L2 does not support signature generation."), violations(config));
    assertThrows(ComparatorCreationError.class, () -> ComparatorFactory.createComparator(config));
  }

  /** The same rule where a missed key is silent: an absent sequence distance means Levenshtein. */
  @Test
  void aSequenceDistanceUnderADifferentlyCasedKeyIsNotTheDefault() {
    SequenceDistance distance =
        ComparatorFactory.createSequenceDistance(Map.of("Sequence_Distance_Type", "lcs"));

    // Rewriting an element costs a deletion and an insertion under LCS, one substitution under LD.
    assertEquals(2L, distance.getDistance(sequence("ab"), sequence("ac"), 4L));
  }

  /** A config carries whatever string the caller set, including none at all. */
  @Test
  void aBlankOrMissingComparatorTypeIsNotASupportedType() {
    assertFalse(ComparatorFactory.isSupportedComparatorType(null));
    assertFalse(ComparatorFactory.isSupportedComparatorType(""));
    assertTrue(ComparatorFactory.isSupportedComparatorType(" Jaccard "));
  }

  private static NamespaceConfig.Builder validBuilder() {
    return NamespaceConfig.builder()
        .minTermsAndValuesLength(0)
        .maxTermsAndValuesLength(4)
        .maxCacheSize(10)
        .cacheType("scan")
        .indexType("inverted_term")
        .comparatorType("jaccard")
        .comparatorNormalizerType("identity")
        .maxNumSearchableStructures(3)
        .maxNumSimilarities(5);
  }

  private static List<String> violations(NamespaceConfig config) {
    return config.collectViolations(ComparatorConfigValidator.getInstance());
  }

  private static LongTermsAndValues sequence(String elements) {
    long[] terms = new long[elements.length()];
    for (int index = 0; index < terms.length; ++index) {
      terms[index] = elements.charAt(index);
    }
    // A sequence's Uni value is its length, and it carries no values.
    return LongTermsAndValuesTestFactory.create(terms, new float[0], terms.length);
  }

  @FunctionalInterface
  private interface BuilderMutation {
    NamespaceConfig.Builder apply(NamespaceConfig.Builder builder);
  }

  private record ValidationCase(
      String name, BuilderMutation build, boolean valid, String expectedMessage) {
    NamespaceConfig build(NamespaceConfig.Builder builder) {
      return build.apply(builder).build();
    }
  }
}
