package com.uber.ussi.searchablestructure.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.uber.ussi.TestLongObjectMaps;
import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.config.NamespaceConfig.CandidateGenerator;
import com.uber.ussi.utils.Constants;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Every record layout against every inverted structure against every candidate generator.
 *
 * <p>The layout and the structure vary independently, so the cases form a product, and enumerating
 * it keeps a rule from quietly covering a cell no single-purpose test would visit.
 */
class IndexPairingMatrixTest {

  /** Why a cell is invalid, or {@link #VALID} when it is not. */
  private enum Expectation {
    VALID,
    /** The structure stores no layout the comparator reads. */
    NO_SHARED_LAYOUT,
    /** The structure keys its lists by signatures the comparator does not generate. */
    NO_SIGNATURES,
    /** The comparator cannot score a row from the keys it shares with the query. */
    NO_MERGE
  }

  /** A comparator named by what it reads and what it can generate. */
  private record ComparatorCase(
      String name, String comparatorType, String normalizerType, boolean signatureGenerator) {}

  private static final ComparatorCase SPARSE_WITH_SIGNATURES =
      new ComparatorCase("jaccard with a generator", "jaccard", "identity", true);
  private static final ComparatorCase SPARSE_WITHOUT_SIGNATURES =
      new ComparatorCase("jaccard without a generator", "jaccard", "identity", false);
  private static final ComparatorCase SPARSE_NEVER_SIGNATURES =
      new ComparatorCase("l2", "l2", "reciprocal", false);
  private static final ComparatorCase SEQUENCE =
      new ComparatorCase("ngld", "ngld", "complement", false);

  private static final IndexType[] INVERTED_STRUCTURES = {
    IndexType.INVERTED_TERM, IndexType.INVERTED_SIGNATURE, IndexType.INVERTED_HYBRID
  };

  private record Cell(
      ComparatorCase comparator,
      IndexType indexType,
      CandidateGenerator candidateGenerator,
      Expectation expectation) {
    String describe() {
      return String.format(
          "%s on %s with %s",
          comparator.name(), indexType.getParamValue(), candidateGenerator.getParamValue());
    }
  }

  private static List<Cell> matrix() {
    List<Cell> cells = new ArrayList<>();
    for (ComparatorCase comparator :
        List.of(
            SPARSE_WITH_SIGNATURES,
            SPARSE_WITHOUT_SIGNATURES,
            SPARSE_NEVER_SIGNATURES,
            SEQUENCE)) {
      for (IndexType indexType : INVERTED_STRUCTURES) {
        for (CandidateGenerator candidateGenerator : CandidateGenerator.values()) {
          cells.add(
              new Cell(
                  comparator,
                  indexType,
                  candidateGenerator,
                  expect(comparator, indexType, candidateGenerator)));
        }
      }
    }
    return cells;
  }

  private static Expectation expect(
      ComparatorCase comparator, IndexType indexType, CandidateGenerator candidateGenerator) {
    boolean readsSequences = comparator == SEQUENCE;
    if (readsSequences && indexType != IndexType.INVERTED_TERM) {
      return Expectation.NO_SHARED_LAYOUT;
    }
    if (indexType.requiresSignatureSupport() && !comparator.signatureGenerator()) {
      return Expectation.NO_SIGNATURES;
    }
    if (candidateGenerator == CandidateGenerator.SPARS_MERGE && readsSequences) {
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
            .maxTermsAndValuesLength(Constants.NUM_SIGNATURES_PER_ID + 1)
            .maxCacheSize(10)
            .cacheType("scan")
            .indexType(cell.indexType().getParamValue())
            .indexParams(
                Map.of(
                    Constants.CANDIDATE_GENERATOR, cell.candidateGenerator().getParamValue()))
            .comparatorType(cell.comparator().comparatorType())
            .comparatorNormalizerType(cell.comparator().normalizerType())
            .maxNumSearchableStructures(3)
            .maxNumSimilarities(10);
    if (cell.comparator().signatureGenerator()) {
      builder.comparatorParams(Map.of(Constants.SIGNATURE_GENERATOR_TYPE, "minhash"));
    }
    return builder.build();
  }
}
