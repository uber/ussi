package com.uber.ussi.searchablestructure.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.uber.ussi.comparator.ComparatorFactory;
import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.config.NamespaceConfig.SparseCandidateGenerator;
import com.uber.ussi.utils.Constants;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IndexConfigValidatorTest {

  private static final ValidationCase[] VALIDATION_CASES = {
    new ValidationCase("defaults", builder -> builder, true),
    new ValidationCase(
        "unparseable max fraction",
        builder -> builder.indexParams(Map.of(Constants.MAX_FRACTION_IDS_PER_SPARSE_KEY, "bad")),
        false),
    new ValidationCase(
        "max fraction at zero",
        builder -> builder.indexParams(Map.of(Constants.MAX_FRACTION_IDS_PER_SPARSE_KEY, "0")),
        false),
    new ValidationCase(
        "pre-filtering ratio above one",
        builder -> builder.indexParams(Map.of(Index.MAX_PRE_FILTERING_ROWS_RATIO, "1.5")),
        false),
    new ValidationCase(
        "unsupported metadata strategy",
        builder -> builder.indexParams(Map.of(Index.METADATA_FILTERING_STRATEGY, "guesswork")),
        false),
    new ValidationCase(
        "spars merge on generic index",
        builder ->
            builder
                .indexType(lowerCase(IndexFactory.IndexType.GENERIC))
                .indexParams(Map.of(Constants.SPARSE_CANDIDATE_GENERATOR, sparsMerge())),
        false),
    new ValidationCase(
        "spars merge with l2 on signature index",
        builder ->
            builder
                .indexType(lowerCase(IndexFactory.IndexType.SIGNATURE))
                .comparatorType(lowerCase(ComparatorFactory.COMPARATOR_TYPE.L2))
                .indexParams(Map.of(Constants.SPARSE_CANDIDATE_GENERATOR, sparsMerge())),
        false),
    new ValidationCase(
        "spars merge with l2 on inverted index",
        builder ->
            builder
                .indexType(lowerCase(IndexFactory.IndexType.INVERTED))
                .comparatorType(lowerCase(ComparatorFactory.COMPARATOR_TYPE.L2))
                .indexParams(Map.of(Constants.SPARSE_CANDIDATE_GENERATOR, sparsMerge())),
        true),
    new ValidationCase(
        "dense index requires l2",
        builder ->
            builder
                .indexType(lowerCase(IndexFactory.IndexType.DENSE))
                .comparatorType("cosine"),
        false),
  };

  @Test
  void validationCases() {
    for (ValidationCase testCase : VALIDATION_CASES) {
      List<String> violations = violations(testCase.build(validBuilder()));
      assertEquals(testCase.valid, violations.isEmpty(), testCase.name);
    }
  }

  @Test
  void validateThrowsForDenseIndexWithNonL2Comparator() {
    NamespaceConfig config =
        validBuilder()
            .indexType(lowerCase(IndexFactory.IndexType.DENSE))
            .comparatorType("cosine")
            .build();
    assertThrows(
        IllegalArgumentException.class, () -> config.validate(IndexConfigValidator.getInstance()));
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
    return config.collectViolations(IndexConfigValidator.getInstance());
  }

  private static String sparsMerge() {
    return SparseCandidateGenerator.SPARS_MERGE.getIndexParamValue();
  }

  private static String lowerCase(Enum<?> value) {
    return value.name().toLowerCase(Locale.ROOT);
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
