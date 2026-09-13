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
    /*
     * The sequence comparators score a pair by running a dynamic program over the ordered
     * sequences, which the shared keys only bound, so merge is unsupported on every index type
     * rather than only on the ones that verify candidates through signatures.
     */
    new ValidationCase(
        "spars merge with ngld on inverted index",
        builder ->
            builder
                .indexType(lowerCase(IndexFactory.IndexType.INVERTED))
                .comparatorType(lowerCase(ComparatorFactory.COMPARATOR_TYPE.NGLD))
                .indexParams(Map.of(Constants.SPARSE_CANDIDATE_GENERATOR, sparsMerge())),
        false),
    new ValidationCase(
        "spars merge with gld on sparse index",
        builder ->
            builder
                .indexType(lowerCase(IndexFactory.IndexType.SPARSE))
                .comparatorType(lowerCase(ComparatorFactory.COMPARATOR_TYPE.GLD))
                .indexParams(Map.of(Constants.SPARSE_CANDIDATE_GENERATOR, sparsMerge())),
        false),
    // Ruzicka scores a row from the keys it shares with the query, so merge stays available.
    new ValidationCase(
        "spars merge with ruzicka on inverted index",
        builder ->
            builder
                .indexType(lowerCase(IndexFactory.IndexType.INVERTED))
                .comparatorType(lowerCase(ComparatorFactory.COMPARATOR_TYPE.RUZICKA))
                .indexParams(Map.of(Constants.SPARSE_CANDIDATE_GENERATOR, sparsMerge())),
        true),
    /*
      * A sequence record has no values and its terms are neither sorted nor distinct, so only the
      * sequence index, which indexes the element multiset instead, and the generic index, which
      * never reads terms itself, can hold one.
      */
    new ValidationCase(
        "ngld on sequence index",
        builder ->
            builder
                .indexType(lowerCase(IndexFactory.IndexType.SEQUENCE))
                .comparatorType(lowerCase(ComparatorFactory.COMPARATOR_TYPE.NGLD)),
        true),
    new ValidationCase(
        "gld on generic index",
        builder ->
            builder
                .indexType(lowerCase(IndexFactory.IndexType.GENERIC))
                .comparatorType(lowerCase(ComparatorFactory.COMPARATOR_TYPE.GLD)),
        true),
    new ValidationCase(
        "ngld on inverted index",
        builder ->
            builder
                .indexType(lowerCase(IndexFactory.IndexType.INVERTED))
                .comparatorType(lowerCase(ComparatorFactory.COMPARATOR_TYPE.NGLD)),
        false),
    new ValidationCase(
        "sequence index with a value comparator",
        builder ->
            builder
                .indexType(lowerCase(IndexFactory.IndexType.SEQUENCE))
                .comparatorType(lowerCase(ComparatorFactory.COMPARATOR_TYPE.JACCARD)),
        false),
    // Discarding a popular element drops it from the sequences too, so the pairing stays valid.
    new ValidationCase(
        "sequence index discarding popular elements",
        builder ->
            builder
                .indexType(lowerCase(IndexFactory.IndexType.SEQUENCE))
                .comparatorType(lowerCase(ComparatorFactory.COMPARATOR_TYPE.NGLD))
                .indexParams(Map.of(Constants.MAX_FRACTION_IDS_PER_SPARSE_KEY, "0.05")),
        true),
    new ValidationCase(
        "sequence index keeping every element",
        builder ->
            builder
                .indexType(lowerCase(IndexFactory.IndexType.SEQUENCE))
                .comparatorType(lowerCase(ComparatorFactory.COMPARATOR_TYPE.NGLD))
                .indexParams(Map.of(Constants.MAX_FRACTION_IDS_PER_SPARSE_KEY, "1.0")),
        true),
    // L2 reads both dense and sparse records, so neither index type can turn it away.
    new ValidationCase(
        "l2 on inverted index",
        builder ->
            builder
                .indexType(lowerCase(IndexFactory.IndexType.INVERTED))
                .comparatorType(lowerCase(ComparatorFactory.COMPARATOR_TYPE.L2)),
        true),
    new ValidationCase(
        "dense index with a comparator that cannot read dense records",
        builder ->
            builder
                .indexType(lowerCase(IndexFactory.IndexType.DENSE))
                .comparatorType(lowerCase(ComparatorFactory.COMPARATOR_TYPE.JACCARD)),
        false),
    new ValidationCase(
        "dense index with a comparator that can",
        builder ->
            builder
                .indexType(lowerCase(IndexFactory.IndexType.DENSE))
                .comparatorType(lowerCase(ComparatorFactory.COMPARATOR_TYPE.L2)),
        true),
  };

  @Test
  void validationCases() {
    for (ValidationCase testCase : VALIDATION_CASES) {
      List<String> violations = violations(testCase.build(validBuilder()));
      assertEquals(testCase.valid, violations.isEmpty(), testCase.name);
    }
  }

  /**
   * The tabular cases only check that something was reported, so pin the reason too: a sequence
   * comparator must be rejected for being unable to merge at all, not for the signature rule that
   * applies only to the approximate index types.
   */
  @Test
  void mergeWithASequenceComparatorIsRejectedForBeingUnsupported() {
    NamespaceConfig config =
        validBuilder()
            .indexType(lowerCase(IndexFactory.IndexType.SEQUENCE))
            .comparatorType(lowerCase(ComparatorFactory.COMPARATOR_TYPE.NGLD))
            .indexParams(Map.of(Constants.SPARSE_CANDIDATE_GENERATOR, sparsMerge()))
            .build();

    List<String> violations = violations(config);

    assertEquals(1, violations.size(), violations.toString());
    assertEquals(
        String.format(
            "%s=%s is not supported with comparatorType ngld.",
            Constants.SPARSE_CANDIDATE_GENERATOR, sparsMerge()),
        violations.get(0));
  }

  @Test
  void validateThrowsForDenseIndexWithAComparatorThatCannotReadDenseRecords() {
    NamespaceConfig config =
        validBuilder()
            .indexType(lowerCase(IndexFactory.IndexType.DENSE))
            .comparatorType(lowerCase(ComparatorFactory.COMPARATOR_TYPE.JACCARD))
            .build();
    assertThrows(
        IllegalArgumentException.class, () -> config.validate(IndexConfigValidator.getInstance()));
  }

  /**
   * An unparseable generator is reported by the config's own structural checks, so this validator
   * skips the rules that would need to know which generator was asked for.
   */
  @Test
  void anUnparseableSparseCandidateGeneratorIsLeftToTheStructuralChecks() {
    NamespaceConfig config =
        validBuilder()
            .indexParams(Map.of(Constants.SPARSE_CANDIDATE_GENERATOR, "uni_outward"))
            .build();

    List<String> structuralViolations = config.collectStructuralViolations();

    assertEquals(structuralViolations, violations(config));
    assertEquals(1, structuralViolations.size(), structuralViolations.toString());
  }

  /**
   * Every rule this validator has about the comparator needs a comparator to ask. Neither an
   * unknown name nor params it cannot be built from leaves one to ask, and ComparatorConfigValidator
   * reports both, so nothing is reported here rather than a consequence of them.
   */
  @Test
  void aComparatorThatCannotBeCreatedIsLeftToTheComparatorValidator() {
    NamespaceConfig unknownType =
        validBuilder()
            .indexType(lowerCase(IndexFactory.IndexType.SIGNATURE))
            .comparatorType("cosine")
            .indexParams(Map.of(Constants.SPARSE_CANDIDATE_GENERATOR, sparsMerge()))
            .build();
    /*
     * Dense records and merging are both things jaccard would be reported for, so this pins that
     * the unbuildable params, not the pairing, are what the violation list is left pointed at.
     */
    Map<String, String> unbuildableParams = Map.of(Constants.SIGNATURE_GENERATOR_TYPE, "superhash");
    NamespaceConfig unbuildableOnDenseIndex =
        validBuilder()
            .indexType(lowerCase(IndexFactory.IndexType.DENSE))
            .comparatorParams(unbuildableParams)
            .build();
    NamespaceConfig unbuildableWithMerge =
        validBuilder()
            .indexType(lowerCase(IndexFactory.IndexType.SIGNATURE))
            .comparatorParams(unbuildableParams)
            .indexParams(Map.of(Constants.SPARSE_CANDIDATE_GENERATOR, sparsMerge()))
            .build();

    assertEquals(List.of(), violations(unknownType), "unknown type");
    assertEquals(List.of(), violations(unbuildableOnDenseIndex), "unbuildable on dense index");
    assertEquals(List.of(), violations(unbuildableWithMerge), "unbuildable with merge");
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
    return SparseCandidateGenerator.SPARS_MERGE.getParamValue();
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
