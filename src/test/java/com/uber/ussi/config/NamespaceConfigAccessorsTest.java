package com.uber.ussi.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.uber.ussi.config.NamespaceConfig.SparseCandidateGenerator;
import com.uber.ussi.utils.Constants;
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
        .cacheType("generic")
        .cacheParams(Map.of("ck", "cv"))
        .indexType("generic")
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
  void getSparseCandidateGeneratorCases() {
    NamespaceConfig defaults = fullBuilder().build();
    NamespaceConfig merge =
        fullBuilder()
            .indexParams(
                Map.of(
                    Constants.SPARSE_CANDIDATE_GENERATOR,
                    SparseCandidateGenerator.SPARS_MERGE.getIndexParamValue()))
            .build();

    assertEquals(SparseCandidateGenerator.SPARS, defaults.getSparseCandidateGenerator());
    assertEquals(SparseCandidateGenerator.SPARS_MERGE, merge.getSparseCandidateGenerator());
  }

  @Test
  void gettersReturnConfiguredValues() {
    NamespaceConfig config = fullBuilder().build();

    assertEquals(1, config.getMinTermsAndValuesLength());
    assertEquals(4, config.getMaxTermsAndValuesLength());
    assertEquals(10, config.getMaxCacheSize());
    assertEquals("generic", config.getCacheType());
    assertEquals(Map.of("ck", "cv"), config.getCacheParams());
    assertEquals("generic", config.getIndexType());
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
