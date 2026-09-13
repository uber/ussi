package com.uber.ussi.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.uber.ussi.utils.Constants;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NamespaceConfigTest {

  private static NamespaceConfig.Builder validBuilder() {
    return NamespaceConfig.builder()
        .minTermsAndValuesLength(0)
        .maxTermsAndValuesLength(4)
        .maxCacheSize(10)
        .cacheType("generic")
        .indexType("sparse")
        .comparatorType("l2")
        .comparatorNormalizerType("identity")
        .maxNumSearchableStructures(3)
        .maxNumSimilarities(5);
  }

  @Test
  void builderTrimsAndLowerCasesType() {
    NamespaceConfig config = validBuilder().comparatorType("  L2  ").build();
    assertEquals("l2", config.getComparatorType());
  }

  @Test
  void validatePassesForValidConfig() {
    NamespaceConfig config = validBuilder().build();
    assertTrue(config.collectStructuralViolations().isEmpty());
    config.validate(); // Should not throw.
  }

  @Test
  void validateRejectsNonPositiveMaxCacheSize() {
    NamespaceConfig config = validBuilder().maxCacheSize(0).build();
    List<String> violations = config.collectStructuralViolations();
    assertFalse(violations.isEmpty());
    assertThrows(IllegalArgumentException.class, config::validate);
  }

  @Test
  void validateRejectsMinTermsLengthGreaterThanMax() {
    NamespaceConfig config =
        validBuilder().minTermsAndValuesLength(5).maxTermsAndValuesLength(2).build();
    assertThrows(IllegalArgumentException.class, config::validate);
  }

  @Test
  void validateRejectsBlankComparatorType() {
    NamespaceConfig config = validBuilder().comparatorType("   ").build();
    assertThrows(IllegalArgumentException.class, config::validate);
  }

  @Test
  void validateRejectsMaxNumSearchableStructuresLessThanThree() {
    NamespaceConfig config = validBuilder().maxNumSearchableStructures(2).build();
    List<String> violations = config.collectStructuralViolations();

    assertFalse(violations.isEmpty());
    assertThrows(IllegalArgumentException.class, config::validate);
  }

  @Test
  void validateRejectsUnsupportedSparseCandidateGenerator() {
    NamespaceConfig config =
        validBuilder()
            .indexParams(Map.of(Constants.SPARSE_CANDIDATE_GENERATOR, "uni_outward"))
            .build();

    assertFalse(config.collectStructuralViolations().isEmpty());
    assertThrows(IllegalArgumentException.class, config::validate);
  }

  @Test
  void validateRejectsAnUnsupportedPopularTermDiscardScopeInEitherParamMap() {
    NamespaceConfig indexScope =
        validBuilder()
            .indexParams(Map.of(Constants.POPULAR_TERM_DISCARD_SCOPE, "verification_only"))
            .build();
    NamespaceConfig cacheScope =
        validBuilder()
            .cacheParams(Map.of(Constants.POPULAR_TERM_DISCARD_SCOPE, "verification_only"))
            .build();

    for (NamespaceConfig config : List.of(indexScope, cacheScope)) {
      assertFalse(config.collectStructuralViolations().isEmpty());
      assertThrows(IllegalArgumentException.class, config::validate);
    }
  }

  @Test
  void collectViolationsAppendsWhatEachValidatorReports() {
    NamespaceConfig config = validBuilder().build();

    assertTrue(config.collectViolations().isEmpty());
    assertEquals(
        List.of("first", "second"),
        config.collectViolations(
            (validated, violations) -> violations.add("first"),
            (validated, violations) -> violations.add("second")));
  }

  @Test
  void validateThrowsWhenAValidatorReportsAViolation() {
    NamespaceConfig config = validBuilder().build();
    NamespaceConfigValidator validator = (validated, violations) -> violations.add("nope");

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, () -> config.validate(validator));
    assertEquals("nope", error.getMessage());
  }
}
