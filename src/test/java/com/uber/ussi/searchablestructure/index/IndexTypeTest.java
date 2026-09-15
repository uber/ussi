package com.uber.ussi.searchablestructure.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.uber.ussi.comparator.Comparator;
import com.uber.ussi.comparator.ComparatorFactory;
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

  /**
   * An inverted structure stores either type that has terms, whether it keys its lists by those
   * terms or by signatures drawn from them.
   */
  @Test
  void storableRecordTypesAreTheTypesTheStructureKeeps() {
    assertEquals(Set.of(RecordType.DENSE), IndexType.MATRIX.getStorableRecordTypes());
    assertEquals(
        Set.of(RecordType.SPARSE, RecordType.SEQUENCE),
        IndexType.INVERTED_TERM.getStorableRecordTypes());
    assertEquals(
        Set.of(RecordType.SPARSE, RecordType.SEQUENCE),
        IndexType.INVERTED_SIGNATURE.getStorableRecordTypes());
    assertEquals(
        Set.of(RecordType.SPARSE, RecordType.SEQUENCE),
        IndexType.INVERTED_HYBRID.getStorableRecordTypes());
    assertEquals(
        Set.of(
            RecordType.DENSE, RecordType.SPARSE, RecordType.SEQUENCE),
        IndexType.SCAN.getStorableRecordTypes());
  }

  /** The stored type is the one the structure keeps and the comparator reads, leaving one. */
  @Test
  void resolvingRecordTypesLeavesOneTypePerStructureAndComparator() {
    Comparator l2 = comparator("l2", "reciprocal");
    Comparator jaccard = comparator("jaccard", "identity");
    Comparator ngld = comparator("ngld", "complement");

    assertEquals(
        Set.of(RecordType.DENSE), IndexType.MATRIX.resolveRecordTypes(l2));
    assertEquals(
        Set.of(RecordType.SPARSE), IndexType.INVERTED_TERM.resolveRecordTypes(l2));
    assertEquals(
        Set.of(RecordType.SPARSE),
        IndexType.INVERTED_TERM.resolveRecordTypes(jaccard));
    assertEquals(Set.of(RecordType.SEQUENCE), IndexType.INVERTED_TERM.resolveRecordTypes(ngld));
  }

  /**
   * A comparator reading nothing a structure keeps is a pairing with no record type at all. Only
   * the matrix structure leaves one now: it is the one that stores neither type having terms.
   */
  @Test
  void resolvingRecordTypesIsEmptyForAnImpossiblePairing() {
    assertEquals(Set.of(), IndexType.MATRIX.resolveRecordTypes(comparator("ngld", "complement")));
    assertEquals(Set.of(), IndexType.MATRIX.resolveRecordTypes(comparator("gld", "reciprocal")));
  }

  /** The scan structure never reads a record's type, so it never asks which type it holds. */
  @Test
  void resolvingRecordTypesIsAmbiguousOnlyWhereTheAnswerIsUnused() {
    assertEquals(
        Set.of(RecordType.DENSE, RecordType.SPARSE),
        IndexType.SCAN.resolveRecordTypes(comparator("l2", "reciprocal")));
    assertEquals(
        Set.of(RecordType.DENSE, RecordType.SPARSE),
        IndexType.SCAN.resolveRecordTypes(comparator("jaccard", "identity")));
  }

  /**
   * The matrix structure shares a record type with every order-agnostic comparator, so what keeps
   * it to some of them is the arithmetic they can supply rather than the type it stores.
   */
  @Test
  void onlyTheMatrixStructureScoresByDotProducts() {
    assertEquals(
        Set.of(RecordType.DENSE),
        IndexType.MATRIX.resolveRecordTypes(comparator("jaccard", "identity")));

    assertTrue(IndexType.MATRIX.scoresByDotProducts());
    assertFalse(IndexType.SCAN.scoresByDotProducts());
    assertFalse(IndexType.INVERTED_TERM.scoresByDotProducts());
    assertFalse(IndexType.INVERTED_SIGNATURE.scoresByDotProducts());
    assertFalse(IndexType.INVERTED_HYBRID.scoresByDotProducts());

    assertTrue(comparator("l2", "reciprocal").supportsDotProductScoring());
    assertFalse(comparator("jaccard", "identity").supportsDotProductScoring());
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
    assertTrue(IndexType.INVERTED_SIGNATURE.keysBySignatures());
    assertTrue(IndexType.INVERTED_HYBRID.keysBySignatures());
    assertFalse(IndexType.INVERTED_TERM.keysBySignatures());
    assertFalse(IndexType.SCAN.keysBySignatures());
  }

  /**
   * A conjunction is a similarity only when the lists carry a record's own values, so it needs a
   * term-keyed structure and a type whose terms determine its similarity.
   */
  @Test
  void onlyTermKeyedListsOverSparseRecordsDetermineSimilarity() {
    assertTrue(
        IndexType.INVERTED_TERM.conjunctionDeterminesSimilarity(
            RecordType.SPARSE));
    assertFalse(IndexType.INVERTED_TERM.conjunctionDeterminesSimilarity(RecordType.SEQUENCE));
    assertFalse(
        IndexType.INVERTED_SIGNATURE.conjunctionDeterminesSimilarity(
            RecordType.SPARSE));
    assertFalse(
        IndexType.INVERTED_HYBRID.conjunctionDeterminesSimilarity(
            RecordType.SPARSE));
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
    assertFalse(IndexType.SCAN.scoresByDotProducts());
    assertFalse(IndexType.SCAN.keysBySignatures());
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
