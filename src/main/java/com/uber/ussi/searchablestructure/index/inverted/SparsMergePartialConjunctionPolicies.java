/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.inverted;

import com.uber.ussi.comparator.Comparator;
import com.uber.ussi.comparator.ComparatorFactory;
import com.uber.ussi.comparator.ComparatorType;
import com.uber.ussi.comparator.ConjunctionScored;
import com.uber.ussi.comparator.KeyShareBounded;
import com.uber.ussi.entity.termsandvalues.RecordType;
import com.uber.ussi.searchablestructure.index.IndexType;
import com.uber.ussi.searchablestructure.index.inverted.generator.MergeSearch;
import java.util.Objects;

/**
 * Builds the partial-conjunction policy an inverted index uses with {@code spars_merge}.
 *
 * <p>Policy selection follows the configured {@link ComparatorType} and index shape. Merge search
 * receives a finished policy and does not inspect the comparator for optional facets.
 */
final class SparsMergePartialConjunctionPolicies {

  private SparsMergePartialConjunctionPolicies() {}

  static MergeSearch.PartialConjunctionPolicy create(
      IndexType indexType,
      RecordType recordType,
      ComparatorType comparatorType,
      Comparator comparator,
      boolean scoresFromConjunction) {
    Objects.requireNonNull(indexType, "indexType is null.");
    Objects.requireNonNull(recordType, "recordType is null.");
    Objects.requireNonNull(comparatorType, "comparatorType is null.");
    Objects.requireNonNull(comparator, "comparator is null.");
    if (scoresFromConjunction) {
      return MergeSearch.PartialConjunctionPolicy.fromConfiguredComparator(
          ComparatorFactory.conjunctionScored(comparator, comparatorType));
    }
    if (indexType.conjunctionDeterminesSimilarity(recordType)
        && comparatorType.similarityFromConfiguredConjunction()) {
      return MergeSearch.PartialConjunctionPolicy.fromConfiguredComparator(
          ComparatorFactory.conjunctionScored(comparator, comparatorType));
    }
    if (indexType == IndexType.INVERTED_TERM
        && recordType == RecordType.SEQUENCE
        && comparatorType.boundsKeyShare()) {
      KeyShareBounded keyShareBound = ComparatorFactory.keyShareBounded(comparator, comparatorType);
      return MergeSearch.PartialConjunctionPolicy.forSequenceIndexedMultisetMerge(
          keyShareBound,
          comparator.getComparatorNormalizer(),
          ComparatorFactory.createIndexedMultisetMergeConjunctionScored());
    }
    return MergeSearch.PartialConjunctionPolicy.none();
  }
}
