package com.uber.ussi.searchablestructure.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.utils.ConfigKeys;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CacheConfigValidatorTest {

  private static final ValidationCase[] VALIDATION_CASES = {
    new ValidationCase("inverted term cache without params", builder -> builder, true),
    new ValidationCase(
        "a cache type that names no structure", builder -> builder.cacheType("sparse"), false),
    new ValidationCase(
        "max fraction at zero",
        builder ->
            builder.cacheParams(Map.of(ConfigKeys.MAX_FRACTION_IDS_PER_TERM, "0")),
        false),
    new ValidationCase(
        "confidence below one half",
        builder ->
            builder.cacheParams(
                Map.of(ConfigKeys.MAX_FRACTION_IDS_PER_TERM_CONFIDENCE, "0.4")),
        false),
    new ValidationCase(
        "unparseable reevaluation fraction",
        builder ->
            builder.cacheParams(
                Map.of(ConfigKeys.FULL_REEVALUATION_CACHE_SIZE_DECREASE_FRACTION, "not-a-number")),
        false),
    new ValidationCase(
        "the scan cache ignores inverted-term params",
        builder ->
            builder
                .cacheType("scan")
                .cacheParams(Map.of(ConfigKeys.MAX_FRACTION_IDS_PER_TERM, "0")),
        true),
    // The inverted term cache needs terms and a value per term, which a sequence does not supply.
    new ValidationCase(
        "inverted term cache with a sequence comparator",
        builder -> builder.comparatorType("ngld").comparatorNormalizerType("complement"),
        false),
    // An unknown comparator leaves nothing to ask, and the comparator validator reports the name.
    new ValidationCase(
        "inverted term cache with a comparator that cannot be created",
        builder -> builder.comparatorType("cosine"),
        true),
    new ValidationCase(
        "the scan cache with a sequence comparator",
        builder ->
            builder
                .cacheType("scan")
                .indexType("inverted_term")
                .comparatorType("ngld")
                .comparatorNormalizerType("complement"),
        true),
  };

  @Test
  void validationCases() {
    for (ValidationCase testCase : VALIDATION_CASES) {
      List<String> violations = violations(testCase.build(invertedTermCacheBuilder()));
      assertEquals(testCase.valid, violations.isEmpty(), testCase.name);
    }
  }

  @Test
  void aBlankCacheTypeIsReportedOnlyByTheConfigItself() {
    List<String> violations = violations(invertedTermCacheBuilder().cacheType("").build());

    assertEquals(List.of("cacheType must be a non-blank string."), violations);
  }

  @Test
  void anUnsupportedCacheTypeNamesTheSupportedOnes() {
    List<String> violations = violations(invertedTermCacheBuilder().cacheType("sparse").build());

    assertEquals(
        List.of("Unsupported cacheType (sparse). Supported values: scan, inverted_term."),
        violations);
  }

  @Test
  void anUnknownCacheParamKeyNamesTheRecognizedOnes() {
    List<String> violations =
        violations(
            invertedTermCacheBuilder().cacheParams(Map.of("max_fraction_ids", "0.5")).build());

    assertEquals(
        List.of(
            "Unknown cacheParams key (max_fraction_ids). Supported keys: "
                + "full_reevaluation_cache_size_decrease_fraction, max_fraction_ids_per_term, "
                + "max_fraction_ids_per_term_confidence, popular_term_discard_scope."),
        violations);
  }

  /** A key validation accepts is a key a read resolves, so casing cannot make the two disagree. */
  @Test
  void aRecognizedKeyUnderADifferentCasingIsNotUnknown() {
    NamespaceConfig config =
        invertedTermCacheBuilder()
            .cacheParams(Map.of(" Max_Fraction_Ids_Per_Term ", "0.5"))
            .build();

    assertEquals(List.of(), violations(config));
    assertEquals("0.5", config.getCacheParam(ConfigKeys.MAX_FRACTION_IDS_PER_TERM));
  }

  private static NamespaceConfig.Builder invertedTermCacheBuilder() {
    return NamespaceConfig.builder()
        .minTermsAndValuesLength(0)
        .maxTermsAndValuesLength(4)
        .maxCacheSize(10)
        .cacheType(CacheType.INVERTED_TERM.getParamValue())
        .indexType("inverted_term")
        .comparatorType("jaccard")
        .comparatorNormalizerType("identity")
        .maxNumSearchableStructures(3)
        .maxNumSimilarities(5);
  }

  private static List<String> violations(NamespaceConfig config) {
    return config.collectViolations(CacheConfigValidator.getInstance());
  }

  @FunctionalInterface
  private interface BuilderMutation {
    NamespaceConfig.Builder apply(NamespaceConfig.Builder builder);
  }

  private record ValidationCase(String name, BuilderMutation build, boolean valid) {
    NamespaceConfig build(NamespaceConfig.Builder builder) {
      return build.apply(builder).build();
    }
  }
}
