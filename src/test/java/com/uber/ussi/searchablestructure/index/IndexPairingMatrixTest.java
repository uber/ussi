package com.uber.ussi.searchablestructure.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.uber.ussi.TestLongObjectMaps;
import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.config.NamespaceConfig.CandidateGeneratorType;
import com.uber.ussi.utils.Constants;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Every record type against every inverted structure against every candidate generator.
 *
 * <p>The record type and the structure vary independently, so the cases form a product, and
 * enumerating it keeps a rule from quietly covering a cell no single-purpose test would visit.
 */
class IndexPairingMatrixTest {

  /**
   * Why a cell is invalid, or {@link #VALID} when it is not. Every inverted structure stores both
   * record types that have terms, so no cell here fails for want of a shared record type; only the
   * matrix structure leaves that gap, and it keeps no lists for a generator to walk.
   */
  private enum Expectation {
    VALID,
    /** The structure keys its lists by signatures the comparator does not generate. */
    NO_SIGNATURES,
    /** The comparator cannot score a row from the keys it shares with the query. */
    NO_MERGE
  }

  /**
   * A comparator named by what it reads and the generator it is configured with, or null for none.
   * The generator has to be one the comparator accepts: MinHash collides at a similarity over
   * distinct terms, and the weighted samplers at one over counts.
   */
  private record ComparatorCase(
      String name,
      String comparatorType,
      String normalizerType,
      String signatureGenerator) {

    boolean generatesSignatures() {
      return signatureGenerator != null;
    }

    boolean readsSequences() {
      return comparatorType.equals("ngld");
    }
  }

  private static final ComparatorCase SPARSE_WITH_SIGNATURES =
      new ComparatorCase("jaccard with a generator", "jaccard", "identity", "minhash");
  private static final ComparatorCase SPARSE_WITHOUT_SIGNATURES =
      new ComparatorCase("jaccard without a generator", "jaccard", "identity", null);
  private static final ComparatorCase SPARSE_NEVER_SIGNATURES =
      new ComparatorCase("l2", "l2", "reciprocal", null);
  private static final ComparatorCase SEQUENCE_WITH_SIGNATURES =
      new ComparatorCase("ngld with a generator", "ngld", "complement", "icws");
  private static final ComparatorCase SEQUENCE_WITHOUT_SIGNATURES =
      new ComparatorCase("ngld without a generator", "ngld", "complement", null);

  private static final IndexType[] INVERTED_STRUCTURES = {
    IndexType.INVERTED_TERM, IndexType.INVERTED_SIGNATURE, IndexType.INVERTED_HYBRID
  };

  private record Cell(
      ComparatorCase comparator,
      IndexType indexType,
      CandidateGeneratorType candidateGeneratorType,
      Expectation expectation) {
    String describe() {
      return String.format(
          "%s on %s with %s",
          comparator.name(), indexType.getParamValue(), candidateGeneratorType.getParamValue());
    }
  }

  private static List<Cell> matrix() {
    List<Cell> cells = new ArrayList<>();
    for (ComparatorCase comparator :
        List.of(
            SPARSE_WITH_SIGNATURES,
            SPARSE_WITHOUT_SIGNATURES,
            SPARSE_NEVER_SIGNATURES,
            SEQUENCE_WITH_SIGNATURES,
            SEQUENCE_WITHOUT_SIGNATURES)) {
      for (IndexType indexType : INVERTED_STRUCTURES) {
        for (CandidateGeneratorType candidateGeneratorType : CandidateGeneratorType.values()) {
          cells.add(
              new Cell(
                  comparator,
                  indexType,
                  candidateGeneratorType,
                  expect(comparator, indexType, candidateGeneratorType)));
        }
      }
    }
    return cells;
  }

  private static Expectation expect(
      ComparatorCase comparator,
      IndexType indexType,
      CandidateGeneratorType candidateGeneratorType) {
    if (indexType.keysBySignatures() && !comparator.generatesSignatures()) {
      return Expectation.NO_SIGNATURES;
    }
    if (candidateGeneratorType == CandidateGeneratorType.SPARS_MERGE
        && comparator.readsSequences()) {
      return Expectation.NO_MERGE;
    }
    return Expectation.VALID;
  }

  @Test
  void everyPairingIsAcceptedOrRejectedAsTheRulesSay() {
    List<String> unexpected = new ArrayList<>();
    for (Cell cell : matrix()) {
      List<String> violations = config(cell).collectViolations(IndexConfigValidator.getInstance());
      boolean valid = violations.isEmpty();
      if (valid != (cell.expectation() == Expectation.VALID)) {
        unexpected.add(
            String.format(
                "%s: expected %s, got %s",
                cell.describe(), cell.expectation(), valid ? "VALID" : violations));
      }
    }

    assertEquals(List.of(), unexpected);
  }

  /** A config the validator accepts must build, and one it rejects must be refused. */
  @Test
  void everyAcceptedPairingBuildsAndEveryRejectedOneDoesNot() {
    for (Cell cell : matrix()) {
      NamespaceConfig config = config(cell);
      if (cell.expectation() == Expectation.VALID) {
        assertTrue(
            IndexFactory.createIndex(
                    config, TestLongObjectMaps.longObjectMap(), TestLongObjectMaps.longObjectMap())
                != null,
            cell.describe());
      } else {
        assertThrows(
            RuntimeException.class,
            () ->
                IndexFactory.createIndex(
                    config,
                    TestLongObjectMaps.longObjectMap(),
                    TestLongObjectMaps.longObjectMap()),
            cell.describe());
      }
    }
  }

  private static NamespaceConfig config(Cell cell) {
    NamespaceConfig.Builder builder =
        NamespaceConfig.builder()
            .minTermsAndValuesLength(0)
            .maxTermsAndValuesLength(Constants.NUM_SIGNATURES_PER_ROW + 1)
            .maxCacheSize(10)
            .cacheType("scan")
            .indexType(cell.indexType().getParamValue())
            .indexParams(
                Map.of(
                    Constants.CANDIDATE_GENERATOR, cell.candidateGeneratorType().getParamValue()))
            .comparatorType(cell.comparator().comparatorType())
            .comparatorNormalizerType(cell.comparator().normalizerType())
            .maxNumSearchableStructures(3)
            .maxNumSimilarities(10);
    if (cell.comparator().generatesSignatures()) {
      builder.comparatorParams(
          Map.of(Constants.SIGNATURE_GENERATOR, cell.comparator().signatureGenerator()));
    }
    return builder.build();
  }
}
