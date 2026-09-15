package com.uber.ussi.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.uber.ussi.config.NamespaceConfig.PopularTermDiscardScope;
import com.uber.ussi.config.NamespaceConfig.CandidateGeneratorType;
import com.uber.ussi.utils.ConfigKeys;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NamespaceConfigAccessorsTest {

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

  @FunctionalInterface
  private interface ParamLookup {
    String apply(NamespaceConfig config, String key);
  }

  private record ParamLookupCase(String name, ParamLookup lookup, String key, String expected) {}
}
