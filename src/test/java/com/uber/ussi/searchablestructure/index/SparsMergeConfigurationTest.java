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
        SparsMergeConfiguration.supportsMergeCandidateGeneration(
            IndexType.INVERTED_TERM, RecordType.SPARSE, ComparatorType.JACCARD));
    assertTrue(
        SparsMergeConfiguration.supportsMergeCandidateGeneration(
            IndexType.INVERTED_TERM, RecordType.SEQUENCE, ComparatorType.NGLD));
    assertFalse(
        SparsMergeConfiguration.supportsMergeCandidateGeneration(
            IndexType.INVERTED_TERM, RecordType.SPARSE, ComparatorType.NGLD));
  }

  @Test
  void scoresFromAccumulatedConjunctionOnlyOnSparseTermKeyedLists() {
    assertTrue(
        SparsMergeConfiguration.scoresFromAccumulatedConjunction(
            IndexType.INVERTED_TERM,
            RecordType.SPARSE,
            PopularTermDiscardScope.CANDIDATES_AND_VERIFICATION));
    assertFalse(
        SparsMergeConfiguration.scoresFromAccumulatedConjunction(
            IndexType.INVERTED_TERM,
            RecordType.SEQUENCE,
            PopularTermDiscardScope.CANDIDATES_AND_VERIFICATION));
    assertFalse(
        SparsMergeConfiguration.scoresFromAccumulatedConjunction(
            IndexType.INVERTED_TERM,
            RecordType.SPARSE,
            PopularTermDiscardScope.CANDIDATES_ONLY));
  }
}
