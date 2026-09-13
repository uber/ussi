package com.uber.ussi.searchablestructure.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.utils.Constants;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CacheConfigValidatorTest {

  private static final ValidationCase[] VALIDATION_CASES = {
    new ValidationCase("sparse cache without params", builder -> builder, true),
    new ValidationCase(
        "max fraction at zero",
        builder ->
            builder.cacheParams(Map.of(Constants.MAX_FRACTION_IDS_PER_SPARSE_KEY, "0")),
        false),
    new ValidationCase(
        "confidence below one half",
        builder ->
            builder.cacheParams(
                Map.of(Constants.MAX_FRACTION_IDS_PER_SPARSE_KEY_CONFIDENCE, "0.4")),
        false),
    new ValidationCase(
        "unparseable reevaluation fraction",
        builder ->
            builder.cacheParams(
                Map.of(Constants.FULL_REEVALUATION_CACHE_SIZE_DECREASE_FRACTION, "not-a-number")),
        false),
    new ValidationCase(
        "generic cache ignores sparse params",
        builder ->
            builder
                .cacheType("generic")
                .cacheParams(Map.of(Constants.MAX_FRACTION_IDS_PER_SPARSE_KEY, "0")),
        true),
    /*
     * The sparse cache keys its inverted lists by the record's own terms and reads a value per
     * term, neither of which an ordered sequence supplies, so such a namespace caches generically.
     */
    new ValidationCase(
        "sparse cache with a sequence comparator",
        builder -> builder.comparatorType("ngld").comparatorNormalizerType("complement"),
        false),
    /*
     * An unknown comparator has no answer to what it reads, and ComparatorConfigValidator already
     * reports the name, so this validator stays quiet rather than reporting a consequence of it.
     */
    new ValidationCase(
        "sparse cache with a comparator that cannot be created",
        builder -> builder.comparatorType("cosine"),
        true),
    new ValidationCase(
        "generic cache with a sequence comparator",
        builder ->
            builder
                .cacheType("generic")
                .indexType("sequence")
                .comparatorType("ngld")
                .comparatorNormalizerType("complement"),
        true),
  };

  @Test
  void validationCases() {
    for (ValidationCase testCase : VALIDATION_CASES) {
      List<String> violations = violations(testCase.build(sparseCacheBuilder()));
      assertEquals(testCase.valid, violations.isEmpty(), testCase.name);
    }
  }

  private static NamespaceConfig.Builder sparseCacheBuilder() {
    return NamespaceConfig.builder()
        .minTermsAndValuesLength(0)
        .maxTermsAndValuesLength(4)
        .maxCacheSize(10)
        .cacheType(CacheFactory.CacheType.SPARSE.name().toLowerCase(Locale.ROOT))
        .indexType("inverted")
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
