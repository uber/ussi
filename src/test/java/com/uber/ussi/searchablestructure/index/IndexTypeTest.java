package com.uber.ussi.searchablestructure.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.uber.ussi.comparator.Comparator;
import com.uber.ussi.comparator.ComparatorFactory;
import com.uber.ussi.comparator.ComparatorType;
import com.uber.ussi.config.ConfigVocabulary;
import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.entity.termsandvalues.RecordType;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class IndexTypeTest {

  @Test
  void paramValuesAreTheLowercasedNames() {
    assertEquals("scan", IndexType.SCAN.getParamValue());
    assertEquals("inverted_term", IndexType.INVERTED_TERM.getParamValue());
    assertEquals(IndexType.MATRIX, ConfigVocabulary.fromParamValue(IndexType.class, "matrix"));
    assertEquals(
        IndexType.INVERTED_HYBRID,
        ConfigVocabulary.fromParamValue(IndexType.class, " INVERTED_Hybrid "));
  }

  @Test
  void anUnknownParamValueHasNoIndexType() {
    assertNull(ConfigVocabulary.fromParamValue(IndexType.class, "hnsw"));
  }

  /** A structure that keys its lists by a record's own terms stores either type that has terms. */
  @Test
  void storableRecordTypesAreTheTypesTheStructureKeeps() {
    assertEquals(Set.of(RecordType.ORDER_AGNOSTIC_DENSE), IndexType.MATRIX.getStorableRecordTypes());
    assertEquals(
        Set.of(RecordType.ORDER_AGNOSTIC_SPARSE, RecordType.SEQUENCE),
        IndexType.INVERTED_TERM.getStorableRecordTypes());
    assertEquals(
        Set.of(RecordType.ORDER_AGNOSTIC_SPARSE),
        IndexType.INVERTED_SIGNATURE.getStorableRecordTypes());
    assertEquals(
        Set.of(
            RecordType.ORDER_AGNOSTIC_DENSE, RecordType.ORDER_AGNOSTIC_SPARSE, RecordType.SEQUENCE),
        IndexType.SCAN.getStorableRecordTypes());
  }

  /** The stored type is the one the structure keeps and the comparator reads, leaving one. */
  @Test
  void resolvingRecordTypesLeavesOneTypePerStructureAndComparator() {
    Comparator l2 = comparator("l2", "reciprocal");
    Comparator jaccard = comparator("jaccard", "identity");
    Comparator ngld = comparator("ngld", "complement");

    assertEquals(
        Set.of(RecordType.ORDER_AGNOSTIC_DENSE), IndexType.MATRIX.resolveRecordTypes(l2));
    assertEquals(
        Set.of(RecordType.ORDER_AGNOSTIC_SPARSE), IndexType.INVERTED_TERM.resolveRecordTypes(l2));
    assertEquals(
        Set.of(RecordType.ORDER_AGNOSTIC_SPARSE),
        IndexType.INVERTED_TERM.resolveRecordTypes(jaccard));
    assertEquals(Set.of(RecordType.SEQUENCE), IndexType.INVERTED_TERM.resolveRecordTypes(ngld));
  }

  /** A comparator reading nothing a structure keeps is a pairing with no record type at all. */
  @Test
  void resolvingRecordTypesIsEmptyForAnImpossiblePairing() {
    assertEquals(Set.of(), IndexType.MATRIX.resolveRecordTypes(comparator("ngld", "complement")));
    assertEquals(
        Set.of(), IndexType.INVERTED_SIGNATURE.resolveRecordTypes(comparator("ngld", "complement")));
  }

  /** The scan structure never reads a record's type, so it never asks which type it holds. */
  @Test
  void resolvingRecordTypesIsAmbiguousOnlyWhereTheAnswerIsUnused() {
    assertEquals(
        Set.of(RecordType.ORDER_AGNOSTIC_DENSE, RecordType.ORDER_AGNOSTIC_SPARSE),
        IndexType.SCAN.resolveRecordTypes(comparator("l2", "reciprocal")));
    assertEquals(
        Set.of(RecordType.ORDER_AGNOSTIC_DENSE, RecordType.ORDER_AGNOSTIC_SPARSE),
        IndexType.SCAN.resolveRecordTypes(comparator("jaccard", "identity")));
  }

  /**
   * The matrix structure shares a record type with every order-agnostic comparator, so what keeps
   * it to one is the arithmetic it implements rather than the type it stores.
   */
  @Test
  void onlyTheMatrixStructureNamesTheComparatorItComputesItself() {
    assertEquals(
        Set.of(RecordType.ORDER_AGNOSTIC_DENSE),
        IndexType.MATRIX.resolveRecordTypes(comparator("jaccard", "identity")));

    assertEquals(Set.of(ComparatorType.L2), IndexType.MATRIX.getRequiredComparatorTypes());
    assertEquals(Set.of(), IndexType.SCAN.getRequiredComparatorTypes());
    assertEquals(Set.of(), IndexType.INVERTED_TERM.getRequiredComparatorTypes());
    assertEquals(Set.of(), IndexType.INVERTED_SIGNATURE.getRequiredComparatorTypes());
    assertEquals(Set.of(), IndexType.INVERTED_HYBRID.getRequiredComparatorTypes());
  }

  @Test
  void onlyTheInvertedStructuresKeepTheListsTheGeneratorsWalk() {
    assertTrue(IndexType.INVERTED_TERM.supportsCandidateGenerator());
    assertTrue(IndexType.INVERTED_SIGNATURE.supportsCandidateGenerator());
    assertTrue(IndexType.INVERTED_HYBRID.supportsCandidateGenerator());
    assertFalse(IndexType.SCAN.supportsCandidateGenerator());
    assertFalse(IndexType.MATRIX.supportsCandidateGenerator());
  }

  @Test
  void onlyTheSignatureKeyedStructuresNeedAGenerator() {
    assertTrue(IndexType.INVERTED_SIGNATURE.requiresSignatureSupport());
    assertTrue(IndexType.INVERTED_HYBRID.requiresSignatureSupport());
    assertFalse(IndexType.INVERTED_TERM.requiresSignatureSupport());
    assertFalse(IndexType.SCAN.requiresSignatureSupport());
  }

  /**
   * A conjunction is a similarity only when the lists carry a record's own values, so it needs a
   * term-keyed structure and a type whose terms determine its similarity.
   */
  @Test
  void onlyTermKeyedListsOverSparseRecordsDetermineSimilarity() {
    assertTrue(
        IndexType.INVERTED_TERM.conjunctionDeterminesSimilarity(
            RecordType.ORDER_AGNOSTIC_SPARSE));
    assertFalse(IndexType.INVERTED_TERM.conjunctionDeterminesSimilarity(RecordType.SEQUENCE));
    assertFalse(
        IndexType.INVERTED_SIGNATURE.conjunctionDeterminesSimilarity(
            RecordType.ORDER_AGNOSTIC_SPARSE));
    assertFalse(
        IndexType.INVERTED_HYBRID.conjunctionDeterminesSimilarity(
            RecordType.ORDER_AGNOSTIC_SPARSE));
  }

  /**
   * Scan scores every row through the comparator, so it is the exact fallback for anything another
   * structure can do. Widening a structure without widening scan would leave that pairing with no
   * structure that scores it exactly.
   */
  @Test
  void scanStoresAndReadsEveryPairingAnotherStructureDoes() {
    Set<RecordType> scanStores = IndexType.SCAN.getStorableRecordTypes();
    for (IndexType indexType : IndexType.values()) {
      assertTrue(
          scanStores.containsAll(indexType.getStorableRecordTypes()),
          indexType.getParamValue() + " stores a record type scan does not.");
    }

    for (Comparator comparator : everyComparator()) {
      Set<RecordType> scanReads = IndexType.SCAN.resolveRecordTypes(comparator);
      for (IndexType indexType : IndexType.values()) {
        assertTrue(
            scanReads.containsAll(indexType.resolveRecordTypes(comparator)),
            indexType.getParamValue() + " resolves a record type scan does not.");
      }
    }

    // Storing the type is only half of it: scan also imposes neither of the checks that would
    // reject a comparator the pairing otherwise allows.
    assertEquals(Set.of(), IndexType.SCAN.getRequiredComparatorTypes());
    assertFalse(IndexType.SCAN.requiresSignatureSupport());
  }

  private static List<Comparator> everyComparator() {
    return List.of(
        comparator("l2", "reciprocal"),
        comparator("jaccard", "identity"),
        comparator("ruzicka", "identity"),
        comparator("gld", "reciprocal"),
        comparator("ngld", "complement"));
  }

  private static Comparator comparator(String comparatorType, String normalizerType) {
    return ComparatorFactory.createComparator(
        NamespaceConfig.builder()
            .minTermsAndValuesLength(0)
            .maxTermsAndValuesLength(2)
            .maxCacheSize(10)
            .cacheType("scan")
            .indexType("scan")
            .comparatorType(comparatorType)
            .comparatorNormalizerType(normalizerType)
            .maxNumSearchableStructures(3)
            .maxNumSimilarities(10)
            .build());
  }
}
