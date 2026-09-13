package com.uber.ussi.comparator;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
