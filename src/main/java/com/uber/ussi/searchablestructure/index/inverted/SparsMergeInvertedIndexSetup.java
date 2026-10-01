/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.inverted;

import com.uber.ussi.comparator.Comparator;
import com.uber.ussi.comparator.ComparatorFactory;
import com.uber.ussi.comparator.ComparatorType;
import com.uber.ussi.comparator.KeyShareBounded;
import com.uber.ussi.config.NamespaceConfig.CandidateGeneratorType;
import com.uber.ussi.config.NamespaceConfig.PopularTermDiscardScope;
import com.uber.ussi.entity.termsandvalues.RecordType;
import com.uber.ussi.searchablestructure.index.IndexType;
import com.uber.ussi.searchablestructure.index.SparsMergeConfiguration;
import com.uber.ussi.searchablestructure.index.inverted.generator.MergeSearch;
import java.util.Objects;

/** Merge-related fields an inverted index derives once at construction for {@code spars_merge}. */
final class SparsMergeInvertedIndexSetup {

  private static final SparsMergeInvertedIndexSetup WITHOUT_MERGE =
      new SparsMergeInvertedIndexSetup(
          false, MergeSearch.PartialConjunctionPolicy.none(), false);

  private final boolean scoresFromConjunction;
  private final MergeSearch.PartialConjunctionPolicy partialConjunctionPolicy;
  private final boolean storesMergePostingValues;

  private SparsMergeInvertedIndexSetup(
      boolean scoresFromConjunction,
      MergeSearch.PartialConjunctionPolicy partialConjunctionPolicy,
      boolean storesMergePostingValues) {
    this.scoresFromConjunction = scoresFromConjunction;
    this.partialConjunctionPolicy = partialConjunctionPolicy;
    this.storesMergePostingValues = storesMergePostingValues;
  }

  static SparsMergeInvertedIndexSetup create(
      CandidateGeneratorType candidateGeneratorType,
      IndexType indexType,
      RecordType recordType,
      ComparatorType comparatorType,
      Comparator comparator,
      PopularTermDiscardScope popularTermDiscardScope) {
    Objects.requireNonNull(candidateGeneratorType, "candidateGeneratorType is null.");
    Objects.requireNonNull(indexType, "indexType is null.");
    Objects.requireNonNull(recordType, "recordType is null.");
    Objects.requireNonNull(comparatorType, "comparatorType is null.");
    Objects.requireNonNull(comparator, "comparator is null.");
    Objects.requireNonNull(popularTermDiscardScope, "popularTermDiscardScope is null.");
    if (candidateGeneratorType != CandidateGeneratorType.SPARS_MERGE) {
      return WITHOUT_MERGE;
    }
    boolean scoresFromConjunction =
        SparsMergeConfiguration.scoresFromAccumulatedConjunction(
            indexType, recordType, popularTermDiscardScope);
    MergeSearch.PartialConjunctionPolicy partialConjunctionPolicy =
        partialConjunctionPolicyForMerge(
            indexType, recordType, comparatorType, comparator, scoresFromConjunction);
    return new SparsMergeInvertedIndexSetup(
        scoresFromConjunction,
        partialConjunctionPolicy,
        storesMergePostingValues(
            scoresFromConjunction, partialConjunctionPolicy, indexType, recordType));
  }

  private static boolean storesMergePostingValues(
      boolean scoresFromConjunction,
      MergeSearch.PartialConjunctionPolicy partialConjunctionPolicy,
      IndexType indexType,
      RecordType recordType) {
    return scoresFromConjunction
        || (partialConjunctionPolicy.usesPartialConjunction()
            && (indexType.conjunctionDeterminesSimilarity(recordType)
                || recordType == RecordType.SEQUENCE));
  }

  boolean scoresFromConjunction() {
    return scoresFromConjunction;
  }

  MergeSearch.PartialConjunctionPolicy partialConjunctionPolicy() {
    return partialConjunctionPolicy;
  }

  boolean storesMergePostingValues() {
    return storesMergePostingValues;
  }

  private static MergeSearch.PartialConjunctionPolicy partialConjunctionPolicyForMerge(
      IndexType indexType,
      RecordType recordType,
      ComparatorType comparatorType,
      Comparator comparator,
      boolean scoresFromConjunction) {
    if (SparsMergeConfiguration.partialConjunctionUsesConfiguredComparator(
        indexType, recordType, comparatorType, scoresFromConjunction)) {
      return MergeSearch.PartialConjunctionPolicy.fromConfiguredComparator(
          ComparatorFactory.conjunctionScored(comparator, comparatorType));
    }
    if (SparsMergeConfiguration.usesSequenceIndexedMultisetPartialConjunction(
        indexType, recordType, comparatorType)) {
      KeyShareBounded keyShareBound = ComparatorFactory.keyShareBounded(comparator, comparatorType);
      return MergeSearch.PartialConjunctionPolicy.forSequenceIndexedMultisetMerge(
          keyShareBound,
          comparator.getComparatorNormalizer(),
          ComparatorFactory.createIndexedMultisetMergeConjunctionScored());
    }
    return MergeSearch.PartialConjunctionPolicy.none();
  }
}
