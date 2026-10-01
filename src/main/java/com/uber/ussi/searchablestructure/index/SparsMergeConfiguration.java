/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index;

import com.uber.ussi.comparator.ComparatorType;
import com.uber.ussi.config.NamespaceConfig.PopularTermDiscardScope;
import com.uber.ussi.entity.termsandvalues.RecordType;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * Rules for {@code spars_merge} shared by config validation and inverted-index construction.
 *
 * <p>Validation and index wiring read the same predicates so a namespace cannot pass validation
 * yet build a different merge mode.
 */
public final class SparsMergeConfiguration {

  private SparsMergeConfiguration() {}

  /**
   * Returns whether {@code spars_merge} may run with the configured comparator on this index shape.
   */
  public static boolean doesMergeSupportCandidateGeneration(
      IndexType indexType, @Nullable RecordType recordType, ComparatorType comparatorType) {
    Objects.requireNonNull(indexType, "indexType is null.");
    Objects.requireNonNull(comparatorType, "comparatorType is null.");
    return comparatorType.similarityFromConfiguredConjunction()
        || doesMergeUseSequenceIndexedMultisetPartialConjunction(
            indexType, recordType, comparatorType);
  }

  /**
   * Returns whether partial merge conjunction uses the configured comparator's {@link
   * com.uber.ussi.comparator.ConjunctionScored} facet rather than the indexed-multiset Ruzicka
   * helper.
   */
  public static boolean doesPartialConjunctionUseConfiguredComparator(
      IndexType indexType,
      RecordType recordType,
      ComparatorType comparatorType,
      boolean doesMergeScoreFromAccumulatedConjunction) {
    Objects.requireNonNull(recordType, "recordType is null.");
    if (doesMergeScoreFromAccumulatedConjunction) {
      return true;
    }
    return indexType.doesConjunctionDetermineSimilarity(recordType)
        && comparatorType.similarityFromConfiguredConjunction();
  }

  /**
   * Returns whether merge treats the accumulated conjunction as each row's exact similarity rather
   * than a bound before verification.
   */
  public static boolean doesMergeScoreFromAccumulatedConjunction(
      IndexType indexType,
      RecordType recordType,
      PopularTermDiscardScope popularTermDiscardScope) {
    Objects.requireNonNull(indexType, "indexType is null.");
    Objects.requireNonNull(recordType, "recordType is null.");
    Objects.requireNonNull(popularTermDiscardScope, "popularTermDiscardScope is null.");
    return indexType.doesConjunctionDetermineSimilarity(recordType)
        && popularTermDiscardScope == PopularTermDiscardScope.CANDIDATES_AND_VERIFICATION;
  }

  /**
   * Returns whether merge bounds rows with partial Ruzicka conjunction over indexed term counts
   * while verification still scores ordered sequences.
   */
  public static boolean doesMergeUseSequenceIndexedMultisetPartialConjunction(
      IndexType indexType, @Nullable RecordType recordType, ComparatorType comparatorType) {
    Objects.requireNonNull(indexType, "indexType is null.");
    Objects.requireNonNull(comparatorType, "comparatorType is null.");
    return indexType == IndexType.INVERTED_TERM
        && recordType == RecordType.SEQUENCE
        && comparatorType.boundsKeyShare();
  }

  /**
   * Returns whether {@code spars_merge} on this index shape may bound similarity without the merged
   * conjunction being the row's score, while the configured {@link ComparatorType} supports no
   * signature generator. Such a pairing cannot verify candidates on a signature-keyed structure.
   */
  public static boolean doesMergeRequireSignatureGeneratingComparator(
      IndexType indexType, RecordType recordType, ComparatorType comparatorType) {
    Objects.requireNonNull(indexType, "indexType is null.");
    Objects.requireNonNull(recordType, "recordType is null.");
    Objects.requireNonNull(comparatorType, "comparatorType is null.");
    if (indexType.doesConjunctionDetermineSimilarity(recordType)) {
      return false;
    }
    return comparatorType.getSupportedSignatureGeneratorTypes().isEmpty();
  }
}
