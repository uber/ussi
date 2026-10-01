/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.uber.ussi.comparator.ComparatorType;
import com.uber.ussi.config.NamespaceConfig.PopularTermDiscardScope;
import com.uber.ussi.entity.termsandvalues.RecordType;
import org.junit.jupiter.api.Test;

class SparsMergeConfigurationTest {

  @Test
  void supportsMergeForConfiguredConjunctionAndSequenceMultisetPaths() {
    assertTrue(
        SparsMergeConfiguration.doesMergeSupportCandidateGeneration(
            IndexType.INVERTED_TERM, RecordType.SPARSE, ComparatorType.JACCARD));
    assertTrue(
        SparsMergeConfiguration.doesMergeSupportCandidateGeneration(
            IndexType.INVERTED_TERM, RecordType.SEQUENCE, ComparatorType.NGLD));
    assertFalse(
        SparsMergeConfiguration.doesMergeSupportCandidateGeneration(
            IndexType.INVERTED_TERM, RecordType.SPARSE, ComparatorType.NGLD));
  }

  @Test
  void doesPartialConjunctionUseConfiguredComparatorOnSparseMergePaths() {
    assertTrue(
        SparsMergeConfiguration.doesPartialConjunctionUseConfiguredComparator(
            IndexType.INVERTED_TERM,
            RecordType.SPARSE,
            ComparatorType.JACCARD,
            /* mergeScoresFromAccumulatedConjunction */ true));
    assertTrue(
        SparsMergeConfiguration.doesPartialConjunctionUseConfiguredComparator(
            IndexType.INVERTED_TERM,
            RecordType.SPARSE,
            ComparatorType.JACCARD,
            /* mergeScoresFromAccumulatedConjunction */ false));
    assertFalse(
        SparsMergeConfiguration.doesPartialConjunctionUseConfiguredComparator(
            IndexType.INVERTED_TERM,
            RecordType.SEQUENCE,
            ComparatorType.NGLD,
            /* mergeScoresFromAccumulatedConjunction */ false));
  }

  @Test
  void doesMergeRequireSignatureGeneratingComparatorWhenConjunctionDoesNotDetermineSimilarity() {
    assertFalse(
        SparsMergeConfiguration.doesMergeRequireSignatureGeneratingComparator(
            IndexType.INVERTED_TERM, RecordType.SPARSE, ComparatorType.JACCARD));
    assertFalse(
        SparsMergeConfiguration.doesMergeRequireSignatureGeneratingComparator(
            IndexType.INVERTED_TERM, RecordType.SEQUENCE, ComparatorType.NGLD));
    assertTrue(
        SparsMergeConfiguration.doesMergeRequireSignatureGeneratingComparator(
            IndexType.INVERTED_SIGNATURE, RecordType.SPARSE, ComparatorType.L2));
  }

  @Test
  void doesMergeScoreFromAccumulatedConjunctionOnlyOnSparseTermKeyedLists() {
    assertTrue(
        SparsMergeConfiguration.doesMergeScoreFromAccumulatedConjunction(
            IndexType.INVERTED_TERM,
            RecordType.SPARSE,
            PopularTermDiscardScope.CANDIDATES_AND_VERIFICATION));
    assertFalse(
        SparsMergeConfiguration.doesMergeScoreFromAccumulatedConjunction(
            IndexType.INVERTED_TERM,
            RecordType.SEQUENCE,
            PopularTermDiscardScope.CANDIDATES_AND_VERIFICATION));
    assertFalse(
        SparsMergeConfiguration.doesMergeScoreFromAccumulatedConjunction(
            IndexType.INVERTED_TERM,
            RecordType.SPARSE,
            PopularTermDiscardScope.CANDIDATES_ONLY));
  }
}
