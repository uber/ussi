package com.uber.ussi.searchablestructure.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.uber.ussi.comparator.ComparatorFactory;
import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.config.NamespaceConfig.CandidateGenerator;
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
        builder -> builder.indexParams(Map.of(Constants.MAX_FRACTION_IDS_PER_KEY, "bad")),
        false),
    new ValidationCase(
        "max fraction at zero",
        builder -> builder.indexParams(Map.of(Constants.MAX_FRACTION_IDS_PER_KEY, "0")),
        false),
    new ValidationCase(
        "pre-filtering ratio above one",
        builder -> builder.indexParams(Map.of(Index.MAX_PRE_FILTERING_ROWS_RATIO, "1.5")),
        false),
    new ValidationCase(
        "unsupported metadata strategy",
        builder -> builder.indexParams(Map.of(Index.METADATA_FILTERING_STRATEGY, "guesswork")),
        false),
    /*
     * The structures were renamed after their structure, so the names they were configured with
     * before no longer resolve and are reported as the typos they now are.
     */
    new ValidationCase(
        "the index type this vocabulary replaced", builder -> builder.indexType("term"), false),
    new ValidationCase(
        "spars merge on the scan structure",
        builder ->
            builder
                .indexType(lowerCase(IndexType.SCAN))
                .indexParams(Map.of(Constants.CANDIDATE_GENERATOR, sparsMerge())),
        false),
    new ValidationCase(
        "spars merge with l2 on the signature structure",
        builder ->
            builder
                .indexType(lowerCase(IndexType.INVERTED_SIGNATURE))
                .comparatorType(lowerCase(ComparatorFactory.COMPARATOR_TYPE.L2))
                .indexParams(Map.of(Constants.CANDIDATE_GENERATOR, sparsMerge())),
        false),
    new ValidationCase(
        "spars merge with l2 on the term structure",
        builder ->
            builder
                .indexType(lowerCase(IndexType.INVERTED_TERM))
                .comparatorType(lowerCase(ComparatorFactory.COMPARATOR_TYPE.L2))
                .indexParams(Map.of(Constants.CANDIDATE_GENERATOR, sparsMerge())),
        true),
    /*
     * The sequence comparators score a pair by running a dynamic program over the ordered
     * sequences, which the shared keys only bound, so merge is unsupported on every structure
     * rather than only on the ones that verify candidates through signatures.
     */
    new ValidationCase(
        "spars merge with ngld on the term structure",
        builder ->
            builder
                .indexType(lowerCase(IndexType.INVERTED_TERM))
                .comparatorType(lowerCase(ComparatorFactory.COMPARATOR_TYPE.NGLD))
                .indexParams(Map.of(Constants.CANDIDATE_GENERATOR, sparsMerge())),
        false),
    new ValidationCase(
        "spars merge with gld on the hybrid structure",
        builder ->
            builder
                .indexType(lowerCase(IndexType.INVERTED_HYBRID))
                .comparatorType(lowerCase(ComparatorFactory.COMPARATOR_TYPE.GLD))
                .indexParams(Map.of(Constants.CANDIDATE_GENERATOR, sparsMerge())),
        false),
    // Ruzicka scores a row from the keys it shares with the query, so merge stays available.
    new ValidationCase(
        "spars merge with ruzicka on the term structure",
        builder ->
            builder
                .indexType(lowerCase(IndexType.INVERTED_TERM))
                .comparatorType(lowerCase(ComparatorFactory.COMPARATOR_TYPE.RUZICKA))
                .indexParams(Map.of(Constants.CANDIDATE_GENERATOR, sparsMerge())),
        true),
    /*
     * A sequence has no values and its terms are neither sorted nor distinct, so the structures
     * that can hold one are the term-keyed structure, which keys the element multiset instead, and
     * the scan structure, which never reads a record's terms itself. The signature structures
     * cannot, because a signature generator reads a record as a set of terms and values.
     */
    new ValidationCase(
        "ngld on the term structure",
        builder ->
            builder
                .indexType(lowerCase(IndexType.INVERTED_TERM))
                .comparatorType(lowerCase(ComparatorFactory.COMPARATOR_TYPE.NGLD)),
        true),
    new ValidationCase(
        "gld on the scan structure",
        builder ->
            builder
                .indexType(lowerCase(IndexType.SCAN))
                .comparatorType(lowerCase(ComparatorFactory.COMPARATOR_TYPE.GLD)),
        true),
    new ValidationCase(
        "ngld on the signature structure",
        builder ->
            builder
                .indexType(lowerCase(IndexType.INVERTED_SIGNATURE))
                .comparatorType(lowerCase(ComparatorFactory.COMPARATOR_TYPE.NGLD)),
        false),
    // Discarding a popular element drops it from the sequences too, so the pairing stays valid.
    new ValidationCase(
        "the term structure discarding popular elements",
        builder ->
            builder
                .indexType(lowerCase(IndexType.INVERTED_TERM))
                .comparatorType(lowerCase(ComparatorFactory.COMPARATOR_TYPE.NGLD))
                .indexParams(Map.of(Constants.MAX_FRACTION_IDS_PER_KEY, "0.05")),
        true),
    new ValidationCase(
        "the term structure keeping every element",
        builder ->
            builder
                .indexType(lowerCase(IndexType.INVERTED_TERM))
                .comparatorType(lowerCase(ComparatorFactory.COMPARATOR_TYPE.NGLD))
                .indexParams(Map.of(Constants.MAX_FRACTION_IDS_PER_KEY, "1.0")),
        true),
    // L2 reads both dense and sparse records, so no structure here can turn it away.
    new ValidationCase(
        "l2 on the term structure",
        builder ->
            builder
                .indexType(lowerCase(IndexType.INVERTED_TERM))
                .comparatorType(lowerCase(ComparatorFactory.COMPARATOR_TYPE.L2)),
        true),
    new ValidationCase(
        "the matrix structure with a comparator that cannot read dense records",
        builder ->
            builder
                .indexType(lowerCase(IndexType.MATRIX))
                .comparatorType(lowerCase(ComparatorFactory.COMPARATOR_TYPE.JACCARD)),
        false),
    new ValidationCase(
        "the matrix structure with a comparator that can",
        builder ->
            builder
                .indexType(lowerCase(IndexType.MATRIX))
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

  @Test
  void aBlankIndexTypeIsReportedOnlyByTheConfigItself() {
    List<String> violations = violations(validBuilder().indexType("").build());

    assertEquals(List.of("indexType must be a non-blank string."), violations);
  }

  /**
   * A name that resolves to no structure is reported on its own: the rules that pair a structure
   * with a comparator have no structure to ask about, and the params a structure reads are not
   * known to be the ones configured.
   */
  @Test
  void anUnsupportedIndexTypeNamesTheSupportedOnes() {
    NamespaceConfig config =
        validBuilder()
            .indexType("term")
            .indexParams(Map.of(Constants.MAX_FRACTION_IDS_PER_KEY, "0"))
            .build();

    List<String> violations = violations(config);

    assertEquals(
        List.of(
            "Unsupported indexType (term). Supported values: "
                + "scan, matrix, inverted_term, inverted_signature, inverted_hybrid."),
        violations);
  }

  /**
   * The tabular cases only check that something was reported, so pin the reason too: a sequence
   * comparator must be rejected for being unable to merge at all, not for the signature rule that
   * applies only to the approximate structures.
   */
  @Test
  void mergeWithASequenceComparatorIsRejectedForBeingUnsupported() {
    NamespaceConfig config =
        validBuilder()
            .indexType(lowerCase(IndexType.INVERTED_TERM))
            .comparatorType(lowerCase(ComparatorFactory.COMPARATOR_TYPE.NGLD))
            .indexParams(Map.of(Constants.CANDIDATE_GENERATOR, sparsMerge()))
            .build();

    List<String> violations = violations(config);

    assertEquals(1, violations.size(), violations.toString());
    assertEquals(
        String.format(
            "%s=%s is not supported with comparatorType ngld.",
            Constants.CANDIDATE_GENERATOR, sparsMerge()),
        violations.get(0));
  }

  @Test
  void validateThrowsForTheMatrixStructureWithAComparatorThatCannotReadDenseRecords() {
    NamespaceConfig config =
        validBuilder()
            .indexType(lowerCase(IndexType.MATRIX))
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
  void anUnparseableCandidateGeneratorIsLeftToTheStructuralChecks() {
    NamespaceConfig config =
        validBuilder()
            .indexParams(Map.of(Constants.CANDIDATE_GENERATOR, "uni_outward"))
            .build();

    List<String> structuralViolations = config.collectStructuralViolations();

    assertEquals(structuralViolations, violations(config));
    assertEquals(1, structuralViolations.size(), structuralViolations.toString());
  }

  /**
   * Every rule this validator has about the comparator needs a comparator to ask. Neither an
   * unknown name nor params it cannot be built from leaves one to ask, and the comparator
   * validator reports both, so nothing is reported here rather than a consequence of them.
   */
  @Test
  void aComparatorThatCannotBeCreatedIsLeftToTheComparatorValidator() {
    NamespaceConfig unknownType =
        validBuilder()
            .indexType(lowerCase(IndexType.INVERTED_SIGNATURE))
            .comparatorType("cosine")
            .indexParams(Map.of(Constants.CANDIDATE_GENERATOR, sparsMerge()))
            .build();
    /*
     * Dense records and merging are both things jaccard would be reported for, so this pins that
     * the unbuildable params, not the pairing, are what the violation list is left pointed at.
     */
    Map<String, String> unbuildableParams = Map.of(Constants.SIGNATURE_GENERATOR_TYPE, "superhash");
    NamespaceConfig unbuildableOnMatrixStructure =
        validBuilder()
            .indexType(lowerCase(IndexType.MATRIX))
            .comparatorParams(unbuildableParams)
            .build();
    NamespaceConfig unbuildableWithMerge =
        validBuilder()
            .indexType(lowerCase(IndexType.INVERTED_SIGNATURE))
            .comparatorParams(unbuildableParams)
            .indexParams(Map.of(Constants.CANDIDATE_GENERATOR, sparsMerge()))
            .build();

    assertEquals(List.of(), violations(unknownType), "unknown type");
    assertEquals(
        List.of(), violations(unbuildableOnMatrixStructure), "unbuildable on the matrix structure");
    assertEquals(List.of(), violations(unbuildableWithMerge), "unbuildable with merge");
  }

  private static NamespaceConfig.Builder validBuilder() {
    return NamespaceConfig.builder()
        .minTermsAndValuesLength(0)
        .maxTermsAndValuesLength(4)
        .maxCacheSize(10)
        .cacheType("scan")
        .indexType("inverted_term")
        .comparatorType("jaccard")
        .comparatorNormalizerType("identity")
        .maxNumSearchableStructures(3)
        .maxNumSimilarities(5);
  }

  private static List<String> violations(NamespaceConfig config) {
    return config.collectViolations(IndexConfigValidator.getInstance());
  }

  private static String sparsMerge() {
    return CandidateGenerator.SPARS_MERGE.getParamValue();
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
