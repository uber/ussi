/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index;

import com.uber.ussi.comparator.Comparator;
import com.uber.ussi.comparator.ComparatorFactory;
import com.uber.ussi.config.ConfigViolations;
import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.config.NamespaceConfig.CandidateGenerator;
import com.uber.ussi.config.NamespaceConfigValidator;
import com.uber.ussi.entity.termsandvalues.RecordType;
import com.uber.ussi.searchablestructure.metadata.MetadataFilteringStrategy;
import com.uber.ussi.utils.Constants;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/** Reports the index params and index/comparator pairings that the index types would reject. */
public final class IndexConfigValidator implements NamespaceConfigValidator {
  private static final IndexConfigValidator INSTANCE = new IndexConfigValidator();

  private IndexConfigValidator() {}

  public static IndexConfigValidator getInstance() {
    return INSTANCE;
  }

  @Override
  public void collectViolations(NamespaceConfig config, List<String> violations) {
    /*
     * An unrecognized index type is reported by IndexFactory rather than here, so it is treated
     * as a structure whose params and pairings nothing is known about.
     */
    IndexType indexType = IndexType.fromParamValue(config.getIndexType());
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
    collectCandidateGeneratorViolations(config, indexType, recordType, violations);
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
   * returning null when the structure and the comparator have no record type in common.
   *
   * <p>A structure stores a set of record types and a comparator reads a set of them, so the two
   * can only be paired on a type they share. This is what keeps a sequence comparator off the
   * structures whose keys are derived from a record rather than taken from it, and equally what
   * keeps the term-based comparators off the matrix structure. It leaves one type wherever the
   * choice matters: the scan structure is the only one storing more than one type a comparator
   * reads, and it scans and scores through the comparator without reading a record's layout at all.
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
   * The merge generator walks whole inverted lists, so it needs a structure that keeps them
   * uni-sorted and a comparator that can score a row from the keys it shares with the query, which
   * rules out the order-sensitive sequence comparators entirely. Where the keys do not determine a
   * candidate's similarity it also has to verify candidates through the comparator's signatures,
   * which rules out L2 because it has none that preserve similarity.
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
      /*
       * Every rule below asks what a named comparator supports, which has no answer when the name
       * is not one of them. ComparatorConfigValidator reports the name instead.
       */
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
