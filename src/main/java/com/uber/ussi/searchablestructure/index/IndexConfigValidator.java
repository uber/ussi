/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index;

import com.uber.ussi.comparator.ComparatorFactory;
import com.uber.ussi.config.ConfigViolations;
import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.config.NamespaceConfig.SparseCandidateGenerator;
import com.uber.ussi.config.NamespaceConfigValidator;
import com.uber.ussi.searchablestructure.metadata.MetadataFilteringStrategy;
import com.uber.ussi.utils.Constants;
import java.util.List;
import java.util.Locale;

/** Reports the index params and index/comparator pairings that the index types would reject. */
public final class IndexConfigValidator implements NamespaceConfigValidator {
  private static final IndexConfigValidator INSTANCE = new IndexConfigValidator();
  private static final String L2_COMPARATOR_TYPE =
      ComparatorFactory.COMPARATOR_TYPE.L2.name().toLowerCase(Locale.ROOT);

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
    if (IndexFactory.IndexType.DENSE.name().toLowerCase(Locale.ROOT).equals(indexType)
        && !L2_COMPARATOR_TYPE.equals(config.getComparatorType())) {
      violations.add(
          String.format(
              "indexType %s supports only comparatorType %s, got %s.",
              indexType, L2_COMPARATOR_TYPE, config.getComparatorType()));
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
   * The merge generator walks whole inverted lists, so it needs an index type that keeps them
   * uni-sorted. On the approximate index types it also has to verify candidates through the
   * comparator, which rules out L2 because its signatures are not similarity-preserving.
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
    String indexParamValue = SparseCandidateGenerator.SPARS_MERGE.getIndexParamValue();
    if (!IndexFactory.supportsSparseCandidateGenerator(indexType)) {
      violations.add(
          String.format(
              "%s=%s is supported only for indexType inverted, sparse, or signature, got %s.",
              Constants.SPARSE_CANDIDATE_GENERATOR, indexParamValue, indexType));
    }
    if (IndexFactory.mergeRequiresCandidateVerification(indexType)
        && L2_COMPARATOR_TYPE.equals(config.getComparatorType())) {
      violations.add(
          String.format(
              "%s=%s is not supported with comparatorType %s for indexType %s.",
              Constants.SPARSE_CANDIDATE_GENERATOR,
              indexParamValue,
              L2_COMPARATOR_TYPE,
              indexType));
    }
  }
}
