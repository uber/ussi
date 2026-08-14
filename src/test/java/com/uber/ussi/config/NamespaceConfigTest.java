package com.uber.ussi.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
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
}
