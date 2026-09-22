package com.uber.ussi.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.uber.ussi.config.NamespaceConfig.CandidateGeneratorType;
import com.uber.ussi.config.NamespaceConfig.PopularTermDiscardScope;
import com.uber.ussi.utils.ConfigKeys;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
class NamespaceConfigTest {

  private static NamespaceConfig.Builder validBuilder() {
    return NamespaceConfig.builder()
        .minTermsAndValuesLength(0)
        .maxTermsAndValuesLength(4)
        .maxCacheSize(10)
        .cacheType("scan")
        .indexType("inverted_hybrid")
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
  void validateRejectsMoreSimilaritiesThanTheCeiling() {
    NamespaceConfig config =
        validBuilder()
            .maxNumSimilarities(NamespaceConfig.MAX_NUM_SIMILARITIES_CEILING + 1)
            .build();

    assertFalse(config.collectStructuralViolations().isEmpty());
    assertThrows(IllegalArgumentException.class, config::validate);
  }

  @Test
  void validateAcceptsTheCeilingItself() {
    NamespaceConfig config =
        validBuilder().maxNumSimilarities(NamespaceConfig.MAX_NUM_SIMILARITIES_CEILING).build();

    config.validate(); // Should not throw.
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
  void validateRejectsUnsupportedCandidateGeneratorType() {
    NamespaceConfig config =
        validBuilder()
            .indexParams(Map.of(ConfigKeys.CANDIDATE_GENERATOR, "uni_outward"))
            .build();

    assertFalse(config.collectStructuralViolations().isEmpty());
    assertThrows(IllegalArgumentException.class, config::validate);
  }

  @Test
  void validateRejectsAnUnsupportedPopularTermDiscardScopeInEitherParamMap() {
    NamespaceConfig indexScope =
        validBuilder()
            .indexParams(Map.of(ConfigKeys.POPULAR_TERM_DISCARD_SCOPE, "verification_only"))
            .build();
    NamespaceConfig cacheScope =
        validBuilder()
            .cacheParams(Map.of(ConfigKeys.POPULAR_TERM_DISCARD_SCOPE, "verification_only"))
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


  private static final ParamLookupCase[] PARAM_LOOKUP_CASES = {
    new ParamLookupCase("index param", NamespaceConfig::getIndexParam, "ik", "iv"),
    new ParamLookupCase("index param, other case", NamespaceConfig::getIndexParam, "IK", "iv"),
    new ParamLookupCase("index param, unset", NamespaceConfig::getIndexParam, "missing", null),
    new ParamLookupCase("cache param", NamespaceConfig::getCacheParam, "ck", "cv"),
    new ParamLookupCase("comparator param", NamespaceConfig::getComparatorParam, "pk", "pv"),
  };

  private static NamespaceConfig.Builder fullBuilder() {
    return NamespaceConfig.builder()
        .minTermsAndValuesLength(1)
        .maxTermsAndValuesLength(4)
        .maxCacheSize(10)
        .cacheType("scan")
        .cacheParams(Map.of("ck", "cv"))
        .indexType("scan")
        .indexParams(Map.of("ik", "iv"))
        .comparatorType("l2")
        .comparatorParams(Map.of("pk", "pv"))
        .comparatorNormalizerType("identity")
        .comparatorNormalizerParams(Map.of("nk", "nv"))
        .maxNumSearchableStructures(3)
        .maxNumSimilarities(5);
  }

  @Test
  void paramLookupCases() {
    NamespaceConfig config = fullBuilder().build();
    for (ParamLookupCase testCase : PARAM_LOOKUP_CASES) {
      assertEquals(testCase.expected, testCase.lookup.apply(config, testCase.key), testCase.name);
    }
  }

  @Test
  void readDoubleIndexParamUsesTheDefaultWhenUnset() {
    NamespaceConfig config = fullBuilder().build();
    assertEquals(0.25, config.readDoubleIndexParam("unset_param", 0.25));
  }

  @Test
  void getCandidateGeneratorTypeCases() {
    NamespaceConfig defaults = fullBuilder().build();
    NamespaceConfig merge =
        fullBuilder()
            .indexParams(
                Map.of(
                    ConfigKeys.CANDIDATE_GENERATOR,
                    CandidateGeneratorType.SPARS_MERGE.getParamValue()))
            .build();

    assertEquals(CandidateGeneratorType.SPARS, defaults.getCandidateGeneratorType());
    assertEquals(CandidateGeneratorType.SPARS_MERGE, merge.getCandidateGeneratorType());
  }

  /** An index reads the scope from the index params and a cache reads it from the cache params. */
  @Test
  void getPopularTermDiscardScopeCases() {
    assertEquals(
        PopularTermDiscardScope.CANDIDATES_AND_VERIFICATION,
        withDiscardParams(Map.of()).getIndexPopularTermDiscardScope(),
        "unset");
    assertEquals(
        PopularTermDiscardScope.CANDIDATES_AND_VERIFICATION,
        withDiscardScope("  ").getIndexPopularTermDiscardScope(),
        "blank");
    assertEquals(
        PopularTermDiscardScope.CANDIDATES_ONLY,
        withDiscardScope(" Candidates_Only ").getIndexPopularTermDiscardScope(),
        "trimmed and mixed case");
    assertEquals(
        PopularTermDiscardScope.CANDIDATES_AND_VERIFICATION,
        withDiscardScope(PopularTermDiscardScope.CANDIDATES_AND_VERIFICATION.getParamValue())
            .getIndexPopularTermDiscardScope(),
        "stated explicitly");
    assertEquals(
        PopularTermDiscardScope.CANDIDATES_ONLY,
        withDiscardScope(PopularTermDiscardScope.CANDIDATES_ONLY.getParamValue())
            .getCachePopularTermDiscardScope(),
        "read from the cache params");
    assertThrows(
        IllegalArgumentException.class,
        () -> withDiscardScope("verification_only").getIndexPopularTermDiscardScope(),
        "unsupported");
  }

  /** Returns a config carrying {@code scope} in both its index and its cache params. */
  private static NamespaceConfig withDiscardScope(String scope) {
    return withDiscardParams(Map.of(ConfigKeys.POPULAR_TERM_DISCARD_SCOPE, scope));
  }

  private static NamespaceConfig withDiscardParams(Map<String, String> params) {
    return fullBuilder().indexParams(params).cacheParams(params).build();
  }

  @Test
  void gettersReturnConfiguredValues() {
    NamespaceConfig config = fullBuilder().build();

    assertEquals(1, config.getMinTermsAndValuesLength());
    assertEquals(4, config.getMaxTermsAndValuesLength());
    assertEquals(10, config.getMaxCacheSize());
    assertEquals("scan", config.getCacheType());
    assertEquals(Map.of("ck", "cv"), config.getCacheParams());
    assertEquals("scan", config.getIndexType());
    assertEquals(Map.of("ik", "iv"), config.getIndexParams());
    assertEquals("l2", config.getComparatorType());
    assertEquals(Map.of("pk", "pv"), config.getComparatorParams());
    assertEquals("identity", config.getComparatorNormalizerType());
    assertEquals(Map.of("nk", "nv"), config.getComparatorNormalizerParams());
    assertEquals(3, config.getMaxNumSearchableStructures());
    assertEquals(5, config.getMaxNumSimilarities());
  }

  @Test
  void nullParamsDefaultToEmpty() {
    NamespaceConfig config =
        fullBuilder()
            .cacheParams(null)
            .indexParams(null)
            .comparatorParams(null)
            .comparatorNormalizerParams(null)
            .build();

    assertTrue(config.getCacheParams().isEmpty());
    assertTrue(config.getIndexParams().isEmpty());
    assertTrue(config.getComparatorParams().isEmpty());
    assertTrue(config.getComparatorNormalizerParams().isEmpty());
  }

  @Test
  void equalConfigsAreEqualAndShareHashCode() {
    NamespaceConfig first = fullBuilder().build();
    NamespaceConfig second = fullBuilder().build();

    assertEquals(first, second);
    assertEquals(first.hashCode(), second.hashCode());
    assertEquals(first, first);
  }

  @Test
  void differentConfigsAreNotEqual() {
    NamespaceConfig first = fullBuilder().build();
    NamespaceConfig second = fullBuilder().maxCacheSize(99).build();

    assertNotEquals(first, second);
    assertNotEquals(first, "not-a-config");
  }

  @Test
  void toStringIncludesKeyFields() {
    String text = fullBuilder().build().toString();

    assertTrue(text.contains("NamespaceConfig{"));
    assertTrue(text.contains("comparatorType='l2'"));
  }

  @Test
  void collectStructuralViolationsReportsMultipleProblems() {
    NamespaceConfig config = fullBuilder().maxCacheSize(0).maxNumSimilarities(0).build();

    List<String> violations = config.collectStructuralViolations();

    assertTrue(violations.size() > 1);
  }

  @Test
  void validateFormatsMultipleViolations() {
    NamespaceConfig config =
        fullBuilder().minTermsAndValuesLength(-1).maxTermsAndValuesLength(-2).build();

    IllegalArgumentException error =
        assertThrows(IllegalArgumentException.class, config::validate);

    assertTrue(error.getMessage().contains("NamespaceConfig has"));
    assertTrue(error.getMessage().contains("minTermsAndValuesLength must be >= 0"));
  }

  /**
   * Equality decides whether a namespace may be reused for a configuration, so a difference in any
   * one field has to be visible. Each case differs from the same base in exactly one field.
   */
  @Test
  void distinguishesConfigsDifferingInAnySingleField() {
    NamespaceConfig base = fullBuilder().build();
    List<NamespaceConfig> differingInOneField =
        List.of(
            fullBuilder().minTermsAndValuesLength(2).build(),
            fullBuilder().maxTermsAndValuesLength(5).build(),
            fullBuilder().maxCacheSize(11).build(),
            fullBuilder().maxNumSearchableStructures(4).build(),
            fullBuilder().maxNumSimilarities(6).build(),
            fullBuilder().cacheType("inverted_term").build(),
            fullBuilder().cacheParams(Map.of("ck", "other")).build(),
            fullBuilder().indexType("matrix").build(),
            fullBuilder().indexParams(Map.of("ik", "other")).build(),
            fullBuilder().comparatorType("jaccard").build(),
            fullBuilder().comparatorParams(Map.of("pk", "other")).build(),
            fullBuilder().comparatorNormalizerType("reciprocal").build(),
            fullBuilder().comparatorNormalizerParams(Map.of("nk", "other")).build());

    for (NamespaceConfig other : differingInOneField) {
      assertNotEquals(base, other, other.toString());
    }
  }

  @Test
  void equalsItselfAndAnIdenticalConfigAndNothingElse() {
    NamespaceConfig config = fullBuilder().build();
    NamespaceConfig identical = fullBuilder().build();

    assertEquals(config, config);
    assertEquals(config, identical);
    assertEquals(config.hashCode(), identical.hashCode());
    assertNotEquals(config, null);
    assertNotEquals(config, "not a config");
  }

  @FunctionalInterface
  private interface ParamLookup {
    String apply(NamespaceConfig config, String key);
  }

  private record ParamLookupCase(String name, ParamLookup lookup, String key, String expected) {}
}
