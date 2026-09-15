package com.uber.ussi.searchablestructure.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carrotsearch.hppc.LongObjectHashMap;
import com.uber.ussi.TestLongObjectMaps;
import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.config.NamespaceConfig.CandidateGeneratorType;
import com.uber.ussi.entity.meta.MetaFilter;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValuesTestFactory;
import com.uber.ussi.searchablestructure.RowNumAndSimilarity;
import com.uber.ussi.searchablestructure.index.inverted.SignatureIndex;
import com.uber.ussi.searchablestructure.index.scan.ScanIndex;
import com.uber.ussi.utils.ConfigKeys;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * Every record type against every inverted structure against every candidate generator.
 *
 * <p>The record type and the structure vary independently, so the cases form a product, and
 * enumerating it keeps a rule from quietly covering a cell no single-purpose test would visit. The
 * enumeration runs twice: once over the rules, which decides whether a cell is accepted at all, and
 * once over searches, which is what keeps an accepted cell from being accepted and then never
 * queried.
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
      return comparatorType.equals("gld") || comparatorType.equals("ngld");
    }
  }

  private static final ComparatorCase JACCARD =
      new ComparatorCase("jaccard with a generator", "jaccard", "identity", "minhash");
  private static final ComparatorCase RUZICKA =
      new ComparatorCase("ruzicka with a generator", "ruzicka", "identity", "icws");
  private static final ComparatorCase GLD =
      new ComparatorCase("gld with a generator", "gld", "reciprocal", "icws");
  private static final ComparatorCase NGLD =
      new ComparatorCase("ngld with a generator", "ngld", "complement", "icws");
  private static final ComparatorCase L2 = new ComparatorCase("l2", "l2", "reciprocal", null);

  private static final ComparatorCase JACCARD_WITHOUT_SIGNATURES =
      new ComparatorCase("jaccard without a generator", "jaccard", "identity", null);
  private static final ComparatorCase RUZICKA_WITHOUT_SIGNATURES =
      new ComparatorCase("ruzicka without a generator", "ruzicka", "identity", null);
  private static final ComparatorCase GLD_WITHOUT_SIGNATURES =
      new ComparatorCase("gld without a generator", "gld", "reciprocal", null);
  private static final ComparatorCase NGLD_WITHOUT_SIGNATURES =
      new ComparatorCase("ngld without a generator", "ngld", "complement", null);

  /** Every comparator, each configured with a generator it accepts, plus l2, which accepts none. */
  private static final List<ComparatorCase> EVERY_COMPARATOR =
      List.of(JACCARD, RUZICKA, L2, GLD, NGLD);

  /**
   * The same comparators without a generator. Only the rules care about these, since a
   * signature-keyed cell rejects them and the two remaining cells duplicate the list above.
   */
  private static final List<ComparatorCase> EVERY_COMPARATOR_WITHOUT_SIGNATURES =
      List.of(
          JACCARD_WITHOUT_SIGNATURES,
          RUZICKA_WITHOUT_SIGNATURES,
          GLD_WITHOUT_SIGNATURES,
          NGLD_WITHOUT_SIGNATURES);

  private static final IndexType[] INVERTED_STRUCTURES = {
    IndexType.INVERTED_TERM, IndexType.INVERTED_SIGNATURE, IndexType.INVERTED_HYBRID
  };

  /** Rows on both sides of the hybrid's internal boundary, so its two children both get used. */
  private static final int SHORT_RECORD_LENGTH = 8;
  private static final int LONG_RECORD_LENGTH = SignatureIndex.NUM_SIGNATURES_PER_ROW + 10;
  private static final int NUM_ROWS = 12;
  private static final float[] NO_VALUES = new float[0];
  private static final float SIMILARITY_EPSILON = 1.0e-6f;

  /**
   * Jaccard and Ruzicka on all six cells, l2 on the two term cells, and gld and ngld on the three
   * {@code spars} cells. Asserted so that a rule change cannot quietly empty the search matrix,
   * which would leave these tests passing over nothing.
   */
  private static final int NUM_VALID_CELLS = 20;

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

  private static List<Cell> matrix(List<ComparatorCase> comparators) {
    List<Cell> cells = new ArrayList<>();
    for (ComparatorCase comparator : comparators) {
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

  private static List<Cell> everyCell() {
    List<Cell> cells = new ArrayList<>(matrix(EVERY_COMPARATOR));
    cells.addAll(matrix(EVERY_COMPARATOR_WITHOUT_SIGNATURES));
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
    for (Cell cell : everyCell()) {
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
    for (Cell cell : everyCell()) {
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

  /**
   * A row queried by its own record comes back, whichever cell holds it.
   *
   * <p>This is the one completeness claim every cell owes, including the approximate ones: a record
   * shares every key with itself, so no candidate generator can miss it, and scoring runs on
   * canonical terms and values rather than on keys, so the similarity is exact. An approximate
   * structure is allowed to miss a merely similar row, which is why recall belongs in the
   * per-structure tests and only self-retrieval belongs here.
   */
  @Test
  void everyValidPairingFindsARowByItsOwnRecord() {
    List<String> problems = new ArrayList<>();
    for (Cell cell : validCells()) {
      LongObjectHashMap<LongTermsAndValues> rows = corpus(cell.comparator());
      Index index = index(cell, rows);

      for (long rowNum : TestLongObjectMaps.sortedKeys(rows)) {
        LongTermsAndValues query = rows.get(rowNum);
        List<RowNumAndSimilarity> results =
            index.getNearestNeighborRowNums(NUM_ROWS, query, MetaFilter.empty());

        String where = cell.describe() + " rowNum=" + rowNum;
        if (!rowNums(results).contains(rowNum)) {
          problems.add(where + ": did not find the row itself");
        } else if (Math.abs(1.0f - similarityOf(results, rowNum)) > SIMILARITY_EPSILON) {
          problems.add(
              where + ": scored the row against itself as " + similarityOf(results, rowNum));
        }
      }
    }

    assertEquals(List.of(), problems);
  }

  /**
   * Whatever a cell returns is well formed and scored exactly.
   *
   * <p>The scan index scores every row through the same comparator, so it is the oracle for what a
   * pair is worth. A cell may return a subset of what scan finds, since candidate generation prunes
   * and the signature-keyed cells are approximate, but every row it does return has to carry scan's
   * similarity, respect the threshold or the result limit, and arrive once.
   *
   * <p>Order is deliberately not asserted. A structure returns results unordered because
   * {@code NearestNeighborSearchIndex} merges every structure's results with the cache's and sorts
   * the union once; sorting per structure would be work thrown away.
   */
  @Test
  void everyValidPairingScoresWhateverItReturns() {
    List<String> problems = new ArrayList<>();
    for (Cell cell : validCells()) {
      Random random = new Random(51_239L + cell.describe().hashCode());
      LongObjectHashMap<LongTermsAndValues> rows = corpus(cell.comparator());
      Index index = index(cell, rows);
      ScanIndex oracle =
          new ScanIndex(scanConfig(cell.comparator()), rows, TestLongObjectMaps.longObjectMap());

      for (int queryIndex = 0; queryIndex < 8; ++queryIndex) {
        LongTermsAndValues query =
            record(random, cell.comparator(), queryIndex % 2 == 0 ? SHORT_RECORD_LENGTH : 24);
        List<RowNumAndSimilarity> exact =
            oracle.getSimilarRowNums(0.0f, query, MetaFilter.empty());

        int k = 1 + random.nextInt(4);
        String nearest = cell.describe() + " nearest queryIndex=" + queryIndex + " k=" + k;
        List<RowNumAndSimilarity> nearestResults =
            index.getNearestNeighborRowNums(k, query, MetaFilter.empty());
        if (nearestResults.size() > k) {
          problems.add(nearest + ": returned " + nearestResults.size() + " rows");
        }
        collectMalformed(problems, nearest, nearestResults, exact, 0.0f);

        float minSimilarity = 0.2f;
        collectMalformed(
            problems,
            cell.describe() + " similar queryIndex=" + queryIndex,
            index.getSimilarRowNums(minSimilarity, query, MetaFilter.empty()),
            exact,
            minSimilarity);
      }
    }

    assertEquals(List.of(), problems);
  }

  private static void collectMalformed(
      List<String> problems,
      String where,
      List<RowNumAndSimilarity> results,
      List<RowNumAndSimilarity> exact,
      float minSimilarity) {
    Set<Long> seen = new LinkedHashSet<>();
    for (RowNumAndSimilarity result : results) {
      long rowNum = result.getRowNum();
      if (!seen.add(rowNum)) {
        problems.add(where + ": repeated rowNum " + rowNum);
      }
      if (result.getSimilarity() < minSimilarity) {
        problems.add(
            where + ": rowNum " + rowNum + " scored " + result.getSimilarity() + " below threshold");
      }
      if (Math.abs(similarityOf(exact, rowNum) - result.getSimilarity()) > SIMILARITY_EPSILON) {
        problems.add(
            String.format(
                "%s: rowNum %s scored %s, a full scan scored %s",
                where, rowNum, result.getSimilarity(), similarityOf(exact, rowNum)));
      }
    }
  }

  private static List<Cell> validCells() {
    List<Cell> valid =
        matrix(EVERY_COMPARATOR).stream()
            .filter(cell -> cell.expectation() == Expectation.VALID)
            .toList();
    assertEquals(NUM_VALID_CELLS, valid.size(), "the searchable matrix changed size");
    return valid;
  }

  private static Index index(Cell cell, LongObjectHashMap<LongTermsAndValues> rows) {
    return IndexFactory.createIndex(config(cell), rows, TestLongObjectMaps.longObjectMap());
  }

  private static List<Long> rowNums(List<RowNumAndSimilarity> results) {
    return results.stream().map(RowNumAndSimilarity::getRowNum).toList();
  }

  private static float similarityOf(List<RowNumAndSimilarity> results, long rowNum) {
    for (RowNumAndSimilarity result : results) {
      if (result.getRowNum() == rowNum) {
        return result.getSimilarity();
      }
    }
    return 0.0f;
  }

  /** Half short rows and half long ones, so the hybrid routes to both of its children. */
  private static LongObjectHashMap<LongTermsAndValues> corpus(ComparatorCase comparator) {
    Random random = new Random(907_441L + comparator.comparatorType().hashCode());
    LongObjectHashMap<LongTermsAndValues> rows = TestLongObjectMaps.longObjectMap();
    for (long rowNum = 1; rowNum <= NUM_ROWS; ++rowNum) {
      int length = rowNum % 2 == 0 ? LONG_RECORD_LENGTH : SHORT_RECORD_LENGTH;
      rows.put(rowNum, record(random, comparator, length));
    }
    return rows;
  }

  private static LongTermsAndValues record(
      Random random, ComparatorCase comparator, int length) {
    return comparator.readsSequences()
        ? sequence(random, length)
        : sparseRecord(random, comparator, length);
  }

  /** A sequence carries its elements in order, with repeats, and holds no values. */
  private static LongTermsAndValues sequence(Random random, int length) {
    long[] elements = new long[length];
    for (int index = 0; index < elements.length; ++index) {
      elements[index] = random.nextInt(16);
    }
    return LongTermsAndValuesTestFactory.create(elements, NO_VALUES, elements.length);
  }

  /**
   * A sparse record's terms are distinct and ascending, so the alphabet has to outrun the longest
   * record rather than the shortest.
   */
  private static LongTermsAndValues sparseRecord(
      Random random, ComparatorCase comparator, int numTerms) {
    TreeMap<Long, Float> valuesByTerm = new TreeMap<>();
    while (valuesByTerm.size() < numTerms) {
      valuesByTerm.put(
          (long) random.nextInt(4 * LONG_RECORD_LENGTH), 0.25f * (1 + random.nextInt(8)));
    }
    long[] terms = new long[numTerms];
    float[] values = new float[numTerms];
    int index = 0;
    for (Map.Entry<Long, Float> entry : valuesByTerm.entrySet()) {
      terms[index] = entry.getKey();
      values[index] = entry.getValue();
      ++index;
    }
    return LongTermsAndValuesTestFactory.create(terms, values, uniValue(comparator, values));
  }

  /** Each comparator measures a record's own weight its own way. */
  private static double uniValue(ComparatorCase comparator, float[] values) {
    double uniValue = 0.0;
    for (float value : values) {
      uniValue +=
          switch (comparator.comparatorType()) {
            case "jaccard" -> Math.abs(Math.signum(value));
            case "ruzicka" -> Math.abs(value);
            case "l2" -> (double) value * value;
            default -> throw new IllegalArgumentException(
                "Not a sparse comparator: " + comparator.comparatorType());
          };
    }
    return uniValue;
  }

  private static NamespaceConfig config(Cell cell) {
    return config(
        cell.comparator(),
        cell.indexType().getParamValue(),
        Map.of(ConfigKeys.CANDIDATE_GENERATOR, cell.candidateGeneratorType().getParamValue()));
  }

  private static NamespaceConfig scanConfig(ComparatorCase comparator) {
    return config(comparator, IndexType.SCAN.getParamValue(), Map.of());
  }

  private static NamespaceConfig config(
      ComparatorCase comparator, String indexType, Map<String, String> indexParams) {
    NamespaceConfig.Builder builder =
        NamespaceConfig.builder()
            .minTermsAndValuesLength(0)
            .maxTermsAndValuesLength(4 * LONG_RECORD_LENGTH)
            .maxCacheSize(10)
            .cacheType("scan")
            .indexType(indexType)
            .indexParams(indexParams)
            .comparatorType(comparator.comparatorType())
            .comparatorNormalizerType(comparator.normalizerType())
            .maxNumSearchableStructures(3)
            .maxNumSimilarities(NUM_ROWS);
    if (comparator.generatesSignatures()) {
      builder.comparatorParams(
          Map.of(ConfigKeys.SIGNATURE_GENERATOR, comparator.signatureGenerator()));
    }
    return builder.build();
  }
}
