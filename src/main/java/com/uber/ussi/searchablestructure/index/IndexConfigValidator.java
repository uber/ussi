/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index;

import com.uber.ussi.comparator.Comparator;
import com.uber.ussi.comparator.ComparatorFactory;
import com.uber.ussi.comparator.ComparatorType;
import com.uber.ussi.comparator.SignatureBounded;
import com.uber.ussi.config.ConfigViolations;
import com.uber.ussi.config.ConfigVocabulary;
import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.config.NamespaceConfig.CandidateGeneratorType;
import com.uber.ussi.config.NamespaceConfigValidator;
import com.uber.ussi.entity.termsandvalues.RecordType;
import com.uber.ussi.searchablestructure.metadata.MetadataFilteringStrategy;
import com.uber.ussi.utils.Constants;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/**
 * Reports the index types, index params, and index/comparator pairings that no index could be
 * built from.
 */
public final class IndexConfigValidator implements NamespaceConfigValidator {
  /** Every key some layer reads from indexParams, whatever structure is configured. */
  private static final Set<String> RECOGNIZED_KEYS =
      Set.of(
          Index.MAX_PRE_FILTERING_ROWS_RATIO,
          Index.METADATA_FILTERING_STRATEGY,
          Constants.MAX_FRACTION_IDS_PER_TERM,
          Constants.CANDIDATE_GENERATOR,
          Constants.POPULAR_TERM_DISCARD_SCOPE);

  private static final IndexConfigValidator INSTANCE = new IndexConfigValidator();

  private IndexConfigValidator() {}

  public static IndexConfigValidator getInstance() {
    return INSTANCE;
  }

  @Override
  public void collectViolations(NamespaceConfig config, List<String> violations) {
    ConfigViolations.checkNoUnknownKeys(
        violations, "indexParams", config.getIndexParams(), RECOGNIZED_KEYS);
    IndexType indexType = ConfigVocabulary.fromParamValue(IndexType.class, config.getIndexType());
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
          Constants.MAX_FRACTION_IDS_PER_TERM,
          config.getIndexParam(Constants.MAX_FRACTION_IDS_PER_TERM),
          0.0,
          1.0);
    }
    ComparatorType comparatorType =
        ConfigVocabulary.fromParamValue(ComparatorType.class, config.getComparatorType());
    collectRequiredComparatorViolations(config, indexType, comparatorType, violations);
    RecordType recordType = resolveRecordType(config, indexType, violations);
    collectSignatureSupportViolations(config, indexType, comparatorType, violations);
    collectCandidateGeneratorTypeViolations(
        config, indexType, comparatorType, recordType, violations);
  }

  /** An indexType that is non-blank and names no structure is invalid. */
  private static void collectIndexTypeViolations(
      NamespaceConfig config, @Nullable IndexType indexType, List<String> violations) {
    if (indexType != null || config.getIndexType().isEmpty()) {
      return;
    }
    violations.add(
        ConfigVocabulary.unsupported("indexType", config.getIndexType(), IndexType.class));
  }

  /**
   * A structure that computes similarity itself reports only the comparators whose arithmetic it
   * implements, whatever record types it and the configured comparator happen to share.
   */
  private static void collectRequiredComparatorViolations(
      NamespaceConfig config,
      IndexType indexType,
      @Nullable ComparatorType comparatorType,
      List<String> violations) {
    Set<ComparatorType> requiredComparatorTypes = indexType.getRequiredComparatorTypes();
    if (requiredComparatorTypes.isEmpty()
        || comparatorType == null
        || requiredComparatorTypes.contains(comparatorType)) {
      return;
    }
    violations.add(
        String.format(
            "indexType %s computes similarity itself, so it needs comparatorType %s, got %s.",
            indexType.getParamValue(),
            requiredComparatorTypes.stream()
                .map(ComparatorType::getParamValue)
                .sorted()
                .collect(Collectors.joining(" or ")),
            config.getComparatorType()));
  }

  /** A signature-keyed structure is invalid with a comparator that generates no signatures. */
  private static void collectSignatureSupportViolations(
      NamespaceConfig config,
      IndexType indexType,
      @Nullable ComparatorType comparatorType,
      List<String> violations) {
    if (!indexType.keysBySignatures()) {
      return;
    }
    Comparator comparator = ComparatorFactory.tryCreateComparator(config);
    if (comparator == null || comparatorType == null) {
      return;
    }
    if (comparator instanceof SignatureBounded
        && ComparatorFactory.createSignatureGenerator(config) != null) {
      return;
    }
    // A comparator that generates no signatures needs a different structure, not another param.
    if (comparatorType.getSupportedSignatureGeneratorTypes().isEmpty()) {
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
              Constants.SIGNATURE_GENERATOR));
    }
  }

  private static void collectMetadataFilteringStrategyViolations(
      NamespaceConfig config, List<String> violations) {
    String rawValue = config.getIndexParam(Index.METADATA_FILTERING_STRATEGY);
    if (rawValue == null || rawValue.trim().isEmpty()) {
      return;
    }
    if (ConfigVocabulary.fromParamValue(MetadataFilteringStrategy.class, rawValue) == null) {
      violations.add(
          ConfigVocabulary.unsupported(
              Index.METADATA_FILTERING_STRATEGY, rawValue, MetadataFilteringStrategy.class));
    }
  }

  /**
   * Returns the record type an index configured this way stores, reporting a violation and
   * returning null when the structure and the comparator share no record type. The type is left
   * unresolved only for the scan structure, which scores through the comparator and never reads a
   * record's type.
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
  private static void collectCandidateGeneratorTypeViolations(
      NamespaceConfig config,
      IndexType indexType,
      @Nullable ComparatorType comparatorType,
      @Nullable RecordType recordType,
      List<String> violations) {
    CandidateGeneratorType candidateGeneratorType;
    try {
      candidateGeneratorType = config.getCandidateGeneratorType();
    } catch (IllegalArgumentException e) {
      // An unparseable value is already reported by the config's structural checks.
      return;
    }
    if (candidateGeneratorType != CandidateGeneratorType.SPARS_MERGE) {
      return;
    }
    String mergeParamValue = CandidateGeneratorType.SPARS_MERGE.getParamValue();
    if (!indexType.supportsCandidateGenerator()) {
      violations.add(
          String.format(
              "%s=%s is supported only for the inverted index types, got %s.",
              Constants.CANDIDATE_GENERATOR, mergeParamValue, indexType.getParamValue()));
      return;
    }
    if (comparatorType == null) {
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
        && comparatorType.getSupportedSignatureGeneratorTypes().isEmpty()) {
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
        .map(RecordType::getDisplayName)
        .sorted()
        .collect(Collectors.joining(", "));
  }
}
