package com.uber.ussi.searchablestructure.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.uber.ussi.comparator.ComparatorType;
import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.config.NamespaceConfig.CandidateGenerator;
import com.uber.ussi.utils.Constants;
import java.util.List;
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
    new ValidationCase(
        "an index type that names no structure", builder -> builder.indexType("term"), false),
    new ValidationCase(
        "spars merge on the scan structure",
        builder ->
            builder
                .indexType(IndexType.SCAN.getParamValue())
                .indexParams(Map.of(Constants.CANDIDATE_GENERATOR, sparsMerge())),
        false),
    new ValidationCase(
        "spars merge with l2 on the signature structure",
        builder ->
            builder
                .indexType(IndexType.INVERTED_SIGNATURE.getParamValue())
                .comparatorType(ComparatorType.L2.getParamValue())
                .indexParams(Map.of(Constants.CANDIDATE_GENERATOR, sparsMerge())),
        false),
    new ValidationCase(
        "spars merge with l2 on the term structure",
        builder ->
            builder
                .indexType(IndexType.INVERTED_TERM.getParamValue())
                .comparatorType(ComparatorType.L2.getParamValue())
                .indexParams(Map.of(Constants.CANDIDATE_GENERATOR, sparsMerge())),
        true),
    // Sequence comparators need the ordered sequence, not just shared keys, so merge never applies.
    new ValidationCase(
        "spars merge with ngld on the term structure",
        builder ->
            builder
                .indexType(IndexType.INVERTED_TERM.getParamValue())
                .comparatorType(ComparatorType.NGLD.getParamValue())
                .indexParams(Map.of(Constants.CANDIDATE_GENERATOR, sparsMerge())),
        false),
    new ValidationCase(
        "spars merge with gld on the hybrid structure",
        builder ->
            builder
                .indexType(IndexType.INVERTED_HYBRID.getParamValue())
                .comparatorType(ComparatorType.GLD.getParamValue())
                .indexParams(Map.of(Constants.CANDIDATE_GENERATOR, sparsMerge())),
        false),
    // Ruzicka scores a row from the keys it shares with the query, so merge stays available.
    new ValidationCase(
        "spars merge with ruzicka on the term structure",
        builder ->
            builder
                .indexType(IndexType.INVERTED_TERM.getParamValue())
                .comparatorType(ComparatorType.RUZICKA.getParamValue())
                .indexParams(Map.of(Constants.CANDIDATE_GENERATOR, sparsMerge())),
        true),
    // A sequence has no values, so only the term structure, which keys the element multiset, and
    // the scan structure, which never reads terms, can hold one.
    new ValidationCase(
        "ngld on the term structure",
        builder ->
            builder
                .indexType(IndexType.INVERTED_TERM.getParamValue())
                .comparatorType(ComparatorType.NGLD.getParamValue()),
        true),
    new ValidationCase(
        "gld on the scan structure",
        builder ->
            builder
                .indexType(IndexType.SCAN.getParamValue())
                .comparatorType(ComparatorType.GLD.getParamValue()),
        true),
    new ValidationCase(
        "ngld on the signature structure",
        builder ->
            builder
                .indexType(IndexType.INVERTED_SIGNATURE.getParamValue())
                .comparatorType(ComparatorType.NGLD.getParamValue()),
        false),
    // Discarding a popular element drops it from the sequences too, so the pairing stays valid.
    new ValidationCase(
        "the term structure discarding popular elements",
        builder ->
            builder
                .indexType(IndexType.INVERTED_TERM.getParamValue())
                .comparatorType(ComparatorType.NGLD.getParamValue())
                .indexParams(Map.of(Constants.MAX_FRACTION_IDS_PER_KEY, "0.05")),
        true),
    new ValidationCase(
        "the term structure keeping every element",
        builder ->
            builder
                .indexType(IndexType.INVERTED_TERM.getParamValue())
                .comparatorType(ComparatorType.NGLD.getParamValue())
                .indexParams(Map.of(Constants.MAX_FRACTION_IDS_PER_KEY, "1.0")),
        true),
    // L2 reads both dense and sparse records, so no structure here can turn it away.
    new ValidationCase(
        "l2 on the term structure",
        builder ->
            builder
                .indexType(IndexType.INVERTED_TERM.getParamValue())
                .comparatorType(ComparatorType.L2.getParamValue()),
        true),
    new ValidationCase(
        "the matrix structure with a comparator that cannot read dense records",
        builder ->
            builder
                .indexType(IndexType.MATRIX.getParamValue())
                .comparatorType(ComparatorType.JACCARD.getParamValue()),
        false),
    new ValidationCase(
        "the matrix structure with a comparator that can",
        builder ->
            builder
                .indexType(IndexType.MATRIX.getParamValue())
                .comparatorType(ComparatorType.L2.getParamValue()),
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
   * An unresolvable index type is reported alone: the pairing and param rules have no structure to
   * ask about.
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

  /** Pins the reason: merge is rejected for the sequence comparator, not for the signature rule. */
  @Test
  void mergeWithASequenceComparatorIsRejectedForBeingUnsupported() {
    NamespaceConfig config =
        validBuilder()
            .indexType(IndexType.INVERTED_TERM.getParamValue())
            .comparatorType(ComparatorType.NGLD.getParamValue())
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
            .indexType(IndexType.MATRIX.getParamValue())
            .comparatorType(ComparatorType.JACCARD.getParamValue())
            .build();
    assertThrows(
        IllegalArgumentException.class, () -> config.validate(IndexConfigValidator.getInstance()));
  }

  /** The config's own structural checks report it, so the generator-dependent rules are skipped. */
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
   * Every comparator rule here needs a comparator to ask, and the comparator validator already
   * reports both an unknown name and unbuildable params.
   */
  @Test
  void aComparatorThatCannotBeCreatedIsLeftToTheComparatorValidator() {
    NamespaceConfig unknownType =
        validBuilder()
            .indexType(IndexType.INVERTED_SIGNATURE.getParamValue())
            .comparatorType("cosine")
            .indexParams(Map.of(Constants.CANDIDATE_GENERATOR, sparsMerge()))
            .build();
    // Merging would be reported for jaccard on its own, so this pins the params.
    Map<String, String> unbuildableParams = Map.of(Constants.SIGNATURE_GENERATOR_TYPE, "superhash");
    // The structure names the comparator it computes from the config alone, so pairing it with
    // anything else is reported whether or not that comparator could be built. Naming l2 leaves
    // the params as the only thing wrong.
    NamespaceConfig unbuildableOnMatrixStructure =
        validBuilder()
            .indexType(IndexType.MATRIX.getParamValue())
            .comparatorType(ComparatorType.L2.getParamValue())
            .comparatorParams(unbuildableParams)
            .build();
    NamespaceConfig unbuildableWithMerge =
        validBuilder()
            .indexType(IndexType.INVERTED_SIGNATURE.getParamValue())
            .comparatorParams(unbuildableParams)
            .indexParams(Map.of(Constants.CANDIDATE_GENERATOR, sparsMerge()))
            .build();

    assertEquals(List.of(), violations(unknownType), "unknown type");
    assertEquals(
        List.of(), violations(unbuildableOnMatrixStructure), "unbuildable on the matrix structure");
    assertEquals(List.of(), violations(unbuildableWithMerge), "unbuildable with merge");
  }

  @Test
  void anUnknownIndexParamKeyNamesTheRecognizedOnes() {
    List<String> violations =
        violations(validBuilder().indexParams(Map.of("candidate_generation", "spars")).build());

    assertEquals(
        List.of(
            "Unknown indexParams key (candidate_generation). Supported keys: "
                + "candidate_generator, max_fraction_ids_per_key, max_pre_filtering_rows_ratio, "
                + "metadata_filtering_strategy, popular_term_discard_scope."),
        violations);
  }

  /**
   * The matrix structure stores dense records and so shares a record type with every
   * order-agnostic comparator. What it reports is L2, so the pairing is named rather than inferred.
   */
  @Test
  void theMatrixStructureIsReportedForAComparatorItCannotCompute() {
    List<String> violations =
        violations(
            validBuilder()
                .indexType(IndexType.MATRIX.getParamValue())
                .comparatorType(ComparatorType.JACCARD.getParamValue())
                .build());

    assertEquals(
        List.of(
            "indexType matrix computes similarity itself, so it needs comparatorType l2, "
                + "got jaccard."),
        violations);
  }

  @Test
  void theMatrixStructureIsValidWithTheComparatorItComputes() {
    NamespaceConfig config =
        validBuilder()
            .indexType(IndexType.MATRIX.getParamValue())
            .comparatorType(ComparatorType.L2.getParamValue())
            .comparatorNormalizerType("reciprocal")
            .build();

    assertEquals(List.of(), violations(config));
  }

  /** A key recognized here is one some layer reads, whatever structure the namespace names. */
  @Test
  void aKeyOnlyOneStructureReadsIsRecognizedForEveryStructure() {
    NamespaceConfig config =
        validBuilder()
            .indexType(IndexType.MATRIX.getParamValue())
            .comparatorType(ComparatorType.L2.getParamValue())
            .indexParams(Map.of(Constants.MAX_FRACTION_IDS_PER_KEY, "0.5"))
            .build();

    assertEquals(List.of(), violations(config));
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
