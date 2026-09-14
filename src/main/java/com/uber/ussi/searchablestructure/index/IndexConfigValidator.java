/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index;

import com.uber.ussi.comparator.Comparator;
import com.uber.ussi.comparator.ComparatorFactory;
import com.uber.ussi.comparator.SignatureComparator;
import com.uber.ussi.config.ConfigViolations;
import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.config.NamespaceConfig.CandidateGenerator;
import com.uber.ussi.config.NamespaceConfigValidator;
import com.uber.ussi.entity.termsandvalues.RecordType;
import com.uber.ussi.searchablestructure.metadata.MetadataFilteringStrategy;
import com.uber.ussi.utils.Constants;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/**
 * Reports the index types, index params, and index/comparator pairings that no index could be
 * built from.
 */
public final class IndexConfigValidator implements NamespaceConfigValidator {
  private static final IndexConfigValidator INSTANCE = new IndexConfigValidator();

  private IndexConfigValidator() {}

  public static IndexConfigValidator getInstance() {
    return INSTANCE;
  }

  @Override
  public void collectViolations(NamespaceConfig config, List<String> violations) {
    IndexType indexType = IndexType.fromParamValue(config.getIndexType());
    collectIndexTypeViolations(config, indexType, violations);
    ConfigViolations.checkDoubleInRange(
        violations,
        Index.MAX_PRE_FILTERING_ROWS_RATIO,
        config.getIndexParam(Index.MAX_PRE_FILTERING_ROWS_RATIO),
        0.0,
        1.0);
    collectMetadataFilteringStrategyViolations(config, violations);
    if (indexType == null) {
      return;
    }
    if (indexType.supportsCandidateGenerator()) {
      ConfigViolations.checkDoubleAboveMinInRange(
          violations,
          Constants.MAX_FRACTION_IDS_PER_KEY,
          config.getIndexParam(Constants.MAX_FRACTION_IDS_PER_KEY),
          0.0,
          1.0);
    }
    RecordType recordType = resolveRecordType(config, indexType, violations);
    collectSignatureSupportViolations(config, indexType, violations);
    collectCandidateGeneratorViolations(config, indexType, recordType, violations);
  }

  /** An indexType that is non-blank and names no structure is invalid. */
  private static void collectIndexTypeViolations(
      NamespaceConfig config, @Nullable IndexType indexType, List<String> violations) {
    if (indexType != null || config.getIndexType().isEmpty()) {
      return;
    }
    violations.add(
        String.format(
            "Unsupported indexType (%s). Supported values: %s.",
            config.getIndexType(),
            Arrays.stream(IndexType.values())
                .map(IndexType::getParamValue)
                .collect(Collectors.joining(", "))));
  }

  /** A signature-keyed structure is invalid with a comparator that generates no signatures. */
  private static void collectSignatureSupportViolations(
      NamespaceConfig config, IndexType indexType, List<String> violations) {
    if (!indexType.requiresSignatureSupport()) {
      return;
    }
    Comparator comparator = ComparatorFactory.tryCreateComparator(config);
    if (comparator == null) {
      return;
    }
    if (comparator instanceof SignatureComparator signatureComparator
        && signatureComparator.supportsSignatures()) {
      return;
    }
    // A comparator that generates no signatures needs a different structure, not another param.
    if (ComparatorFactory.getSupportedSignatureGeneratorTypes(config.getComparatorType())
        .isEmpty()) {
      violations.add(
          String.format(
              "indexType %s keys its lists by signatures, which comparatorType %s cannot generate.",
              indexType.getParamValue(), config.getComparatorType()));
    } else {
      violations.add(
          String.format(
              "indexType %s keys its lists by signatures, so comparatorType %s needs %s.",
              indexType.getParamValue(),
              config.getComparatorType(),
              Constants.SIGNATURE_GENERATOR_TYPE));
    }
  }

  private static void collectMetadataFilteringStrategyViolations(
      NamespaceConfig config, List<String> violations) {
    String rawValue = config.getIndexParam(Index.METADATA_FILTERING_STRATEGY);
    if (rawValue == null || rawValue.trim().isEmpty()) {
      return;
    }
    try {
      MetadataFilteringStrategy.fromIndexParam(rawValue);
    } catch (IllegalArgumentException e) {
      violations.add(
          String.format("Unsupported %s (%s).", Index.METADATA_FILTERING_STRATEGY, rawValue));
    }
  }

  /**
   * Returns the record type an index configured this way stores, reporting a violation and
   * returning null when the structure and the comparator share no record type. The type is left
   * unresolved only for the scan structure, which scores through the comparator and never reads a
   * record's layout.
   */
  @Nullable
  private static RecordType resolveRecordType(
      NamespaceConfig config, IndexType indexType, List<String> violations) {
    Comparator comparator = ComparatorFactory.tryCreateComparator(config);
    if (comparator == null) {
      return null;
    }
    Set<RecordType> recordTypes = indexType.resolveRecordTypes(comparator);
    if (recordTypes.isEmpty()) {
      violations.add(
          String.format(
              "indexType %s stores %s records, and comparatorType %s reads %s.",
              indexType.getParamValue(),
              describeRecordTypes(indexType.getStorableRecordTypes()),
              config.getComparatorType(),
              describeRecordTypes(comparator.getSupportedRecordTypes())));
      return null;
    }
    return recordTypes.size() == 1 ? recordTypes.iterator().next() : null;
  }

  /**
   * The merge generator needs uni-sorted inverted lists and a comparator that can score a row from
   * the keys it shares with the query. Where those keys only bound similarity it also needs
   * similarity-preserving signatures to verify candidates with.
   */
  private static void collectCandidateGeneratorViolations(
      NamespaceConfig config,
      IndexType indexType,
      @Nullable RecordType recordType,
      List<String> violations) {
    CandidateGenerator candidateGenerator;
    try {
      candidateGenerator = config.getCandidateGenerator();
    } catch (IllegalArgumentException e) {
      // An unparseable value is already reported by the config's structural checks.
      return;
    }
    if (candidateGenerator != CandidateGenerator.SPARS_MERGE) {
      return;
    }
    String mergeParamValue = CandidateGenerator.SPARS_MERGE.getParamValue();
    if (!indexType.supportsCandidateGenerator()) {
      violations.add(
          String.format(
              "%s=%s is supported only for the inverted index types, got %s.",
              Constants.CANDIDATE_GENERATOR, mergeParamValue, indexType.getParamValue()));
      return;
    }
    if (!ComparatorFactory.isSupportedComparatorType(config.getComparatorType())) {
      // The rules below need a known comparator; ComparatorConfigValidator reports the name.
      return;
    }
    Comparator comparator = ComparatorFactory.tryCreateComparator(config);
    if (comparator != null && !comparator.supportsMergeCandidateGeneration()) {
      violations.add(
          String.format(
              "%s=%s is not supported with comparatorType %s.",
              Constants.CANDIDATE_GENERATOR, mergeParamValue, config.getComparatorType()));
      return;
    }
    if (recordType != null
        && !indexType.conjunctionDeterminesSimilarity(recordType)
        && ComparatorFactory.getSupportedSignatureGeneratorTypes(config.getComparatorType())
            .isEmpty()) {
      violations.add(
          String.format(
              "%s=%s is not supported with comparatorType %s for indexType %s.",
              Constants.CANDIDATE_GENERATOR,
              mergeParamValue,
              config.getComparatorType(),
              indexType.getParamValue()));
    }
  }

  private static String describeRecordTypes(Set<RecordType> recordTypes) {
    return recordTypes.stream()
        .map(recordType -> recordType.name().toLowerCase(Locale.ROOT))
        .sorted()
        .collect(Collectors.joining(", "));
  }
}
