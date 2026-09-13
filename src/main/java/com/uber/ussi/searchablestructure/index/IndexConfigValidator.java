/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index;

import com.uber.ussi.comparator.Comparator;
import com.uber.ussi.comparator.ComparatorFactory;
import com.uber.ussi.config.ConfigViolations;
import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.config.NamespaceConfig.SparseCandidateGenerator;
import com.uber.ussi.config.NamespaceConfigValidator;
import com.uber.ussi.entity.termsandvalues.RecordType;
import com.uber.ussi.searchablestructure.metadata.MetadataFilteringStrategy;
import com.uber.ussi.utils.Constants;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Reports the index params and index/comparator pairings that the index types would reject. */
public final class IndexConfigValidator implements NamespaceConfigValidator {
  private static final IndexConfigValidator INSTANCE = new IndexConfigValidator();

  private IndexConfigValidator() {}

  public static IndexConfigValidator getInstance() {
    return INSTANCE;
  }

  @Override
  public void collectViolations(NamespaceConfig config, List<String> violations) {
    String indexType = config.getIndexType();
    ConfigViolations.checkDoubleInRange(
        violations,
        Index.MAX_PRE_FILTERING_ROWS_RATIO,
        config.getIndexParam(Index.MAX_PRE_FILTERING_ROWS_RATIO),
        0.0,
        1.0);
    collectMetadataFilteringStrategyViolations(config, violations);
    if (IndexFactory.supportsSparseCandidateGenerator(indexType)) {
      ConfigViolations.checkDoubleAboveMinInRange(
          violations,
          Constants.MAX_FRACTION_IDS_PER_SPARSE_KEY,
          config.getIndexParam(Constants.MAX_FRACTION_IDS_PER_SPARSE_KEY),
          0.0,
          1.0);
    }
    collectSparseCandidateGeneratorViolations(config, indexType, violations);
    collectRecordTypeViolations(config, indexType, violations);
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
   * The merge generator walks whole inverted lists, so it needs an index type that keeps them
   * uni-sorted and a comparator that can score a row from the keys it shares with the query, which
   * rules out the order-sensitive sequence comparators entirely. On the approximate index types it
   * also has to verify candidates through the comparator's signatures, which rules out L2 because
   * it has none that preserve similarity.
   */
  private static void collectSparseCandidateGeneratorViolations(
      NamespaceConfig config, String indexType, List<String> violations) {
    SparseCandidateGenerator sparseCandidateGenerator;
    try {
      sparseCandidateGenerator = config.getSparseCandidateGenerator();
    } catch (IllegalArgumentException e) {
      // An unparseable value is already reported by the config's structural checks.
      return;
    }
    if (sparseCandidateGenerator != SparseCandidateGenerator.SPARS_MERGE) {
      return;
    }
    if (!ComparatorFactory.isSupportedComparatorType(config.getComparatorType())) {
      /*
       * Every rule below asks what a named comparator supports, which has no answer when the name
       * is not one of them. ComparatorConfigValidator reports the name instead.
       */
      return;
    }
    String mergeParamValue = SparseCandidateGenerator.SPARS_MERGE.getParamValue();
    if (!IndexFactory.supportsSparseCandidateGenerator(indexType)) {
      violations.add(
          String.format(
              "%s=%s is supported only for indexType %s, got %s.",
              Constants.SPARSE_CANDIDATE_GENERATOR,
              mergeParamValue,
              IndexFactory.describeSparseCandidateGeneratorIndexTypes(),
              indexType));
    }
    Comparator comparator = ComparatorFactory.tryCreateComparator(config);
    if (comparator != null && !comparator.supportsMergeCandidateGeneration()) {
      violations.add(
          String.format(
              "%s=%s is not supported with comparatorType %s.",
              Constants.SPARSE_CANDIDATE_GENERATOR, mergeParamValue, config.getComparatorType()));
      return;
    }
    if (IndexFactory.mergeRequiresCandidateVerification(indexType)
        && ComparatorFactory.getSupportedSignatureGeneratorTypes(config.getComparatorType())
            .isEmpty()) {
      violations.add(
          String.format(
              "%s=%s is not supported with comparatorType %s for indexType %s.",
              Constants.SPARSE_CANDIDATE_GENERATOR,
              mergeParamValue,
              config.getComparatorType(),
              indexType));
    }
  }

  /**
   * An index stores one record type and a comparator reads a set of them, so the two can only be
   * paired when the index's type is one the comparator reads. This is what keeps a sequence
   * comparator off the index types that key their lists by a record's own terms, which for a
   * sequence are neither sorted nor distinct, and equally what keeps the term-based comparators off
   * the dense index. The generic index is exempt: it scans and scores through the comparator, so it
   * never reads a record's layout itself.
   */
  private static void collectRecordTypeViolations(
      NamespaceConfig config, String indexType, List<String> violations) {
    RecordType indexRecordType = IndexFactory.getRecordType(indexType);
    Comparator comparator = ComparatorFactory.tryCreateComparator(config);
    if (indexRecordType == null || comparator == null) {
      return;
    }
    Set<RecordType> comparatorRecordTypes = comparator.getSupportedRecordTypes();
    if (!comparatorRecordTypes.contains(indexRecordType)) {
      violations.add(
          String.format(
              "indexType %s stores %s records, which comparatorType %s cannot read; it reads %s.",
              indexType,
              indexRecordType.name().toLowerCase(Locale.ROOT),
              config.getComparatorType(),
              describeRecordTypes(comparatorRecordTypes)));
    }
  }

  private static String describeRecordTypes(Set<RecordType> recordTypes) {
    return recordTypes.stream()
        .map(recordType -> recordType.name().toLowerCase(Locale.ROOT))
        .sorted()
        .collect(Collectors.joining(", "));
  }
}
