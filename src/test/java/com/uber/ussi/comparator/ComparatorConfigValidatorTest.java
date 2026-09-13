package com.uber.ussi.comparator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.uber.ussi.config.NamespaceConfig;
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
    // The unknown name is the only thing reported, rather than piling on what it cannot support.
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
    // A blank value is the same as leaving it unset, which is what a sequence comparator defaults.
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
        .cacheType("generic")
        .indexType("inverted")
        .comparatorType("jaccard")
        .comparatorNormalizerType("identity")
        .maxNumSearchableStructures(3)
        .maxNumSimilarities(5);
  }

  private static List<String> violations(NamespaceConfig config) {
    return config.collectViolations(ComparatorConfigValidator.getInstance());
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
