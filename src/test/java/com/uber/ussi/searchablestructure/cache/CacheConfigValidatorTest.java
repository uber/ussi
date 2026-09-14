package com.uber.ussi.searchablestructure.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.utils.Constants;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CacheConfigValidatorTest {

  private static final ValidationCase[] VALIDATION_CASES = {
    new ValidationCase("inverted term cache without params", builder -> builder, true),
    /*
     * The cache structures were renamed after their structure, so the names they were configured
     * with before no longer resolve and are reported as the typos they now are.
     */
    new ValidationCase(
        "the cache type this vocabulary replaced", builder -> builder.cacheType("sparse"), false),
    new ValidationCase(
        "max fraction at zero",
        builder ->
            builder.cacheParams(Map.of(Constants.MAX_FRACTION_IDS_PER_KEY, "0")),
        false),
    new ValidationCase(
        "confidence below one half",
        builder ->
            builder.cacheParams(
                Map.of(Constants.MAX_FRACTION_IDS_PER_KEY_CONFIDENCE, "0.4")),
        false),
    new ValidationCase(
        "unparseable reevaluation fraction",
        builder ->
            builder.cacheParams(
                Map.of(Constants.FULL_REEVALUATION_CACHE_SIZE_DECREASE_FRACTION, "not-a-number")),
        false),
    new ValidationCase(
        "the scan cache ignores inverted-term params",
        builder ->
            builder
                .cacheType("scan")
                .cacheParams(Map.of(Constants.MAX_FRACTION_IDS_PER_KEY, "0")),
        true),
    /*
     * The inverted term cache keys its inverted lists by the record's own terms and reads a value
     * per term, neither of which an ordered sequence supplies, so such a namespace caches through
     * the scan cache.
     */
    new ValidationCase(
        "inverted term cache with a sequence comparator",
        builder -> builder.comparatorType("ngld").comparatorNormalizerType("complement"),
        false),
    /*
     * An unknown comparator has no answer to what it reads, and ComparatorConfigValidator already
     * reports the name, so this validator stays quiet rather than reporting a consequence of it.
     */
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

  private static NamespaceConfig.Builder invertedTermCacheBuilder() {
    return NamespaceConfig.builder()
        .minTermsAndValuesLength(0)
        .maxTermsAndValuesLength(4)
        .maxCacheSize(10)
        .cacheType(CacheFactory.CacheType.INVERTED_TERM.getParamValue())
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
