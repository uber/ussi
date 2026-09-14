package com.uber.ussi.searchablestructure.index.inverted;

import static com.uber.ussi.TestLongObjectMaps.longObjectMap;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carrotsearch.hppc.LongHashSet;
import com.carrotsearch.hppc.LongObjectHashMap;
import com.carrotsearch.hppc.cursors.LongObjectCursor;
import com.uber.ussi.config.NamespaceConfig;
import com.uber.ussi.config.NamespaceConfig.PopularTermDiscardScope;
import com.uber.ussi.entity.meta.LongMeta;
import com.uber.ussi.entity.meta.MetaFilter;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValuesTestFactory;
import com.uber.ussi.error.IndexCreationError;
import com.uber.ussi.searchablestructure.RowNumAndSimilarity;
import com.uber.ussi.searchablestructure.index.scan.ScanIndex;
import com.uber.ussi.utils.Constants;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class TermIndexOnSequencesTest {
  private static final float DELTA = 1e-6f;
  private static final float[] NO_VALUES = new float[0];
  private static final List<String> COMPARATOR_TYPES = List.of("gld", "ngld");
  private static final float[] MIN_SIMILARITIES = {0.0f, 0.25f, 0.5f, 0.75f, 0.9f};

  @Test
  void theInvertedListsAreKeyedByDistinctElementsWhileScoringKeepsTheSequences() {
    LongObjectHashMap<LongTermsAndValues> rows = longObjectMap();
    // Element 1 occurs three times in one row, so it keys one list entry, not three.
    rows.put(7, sequence(1, 1, 2, 1));
    rows.put(8, sequence(2, 3));

    TermIndex index = new TermIndex(config("ngld"), rows, longObjectMap());

    assertEquals(2, index.size());
    assertEquals(3, index.getNumIndexedKeysForTests());
    assertArrayEquals(new long[] {7}, index.getRowNumsForKeyForTests(1));
    assertArrayEquals(new long[] {8, 7}, index.getRowNumsForKeyForTests(2));
    // The indexed form is the multiset: ascending, distinct, counts as values.
    assertArrayEquals(new long[] {1, 2}, index.getIndexedRow(7).getTerms());
    assertArrayEquals(new float[] {3.0f, 1.0f}, index.getIndexedRow(7).getValues());
    // The scored form is the sequence as it arrived, ordered and with repeats.
    assertArrayEquals(new long[] {1, 1, 2, 1}, index.getVerificationRow(7).getTerms());
    assertEquals(0, index.getVerificationRow(7).valuesLength());
    // A sequence and its multiset agree on the Uni value, so length filtering reads one bound.
    assertEquals(4.0, index.getIndexedRow(7).getUniValue(), DELTA);
    assertEquals(4.0, index.getVerificationRow(7).getUniValue(), DELTA);
  }

  @Test
  void similarSearchMatchesABruteForceScan() {
    Random random = new Random(7_311L);
    for (String comparatorType : COMPARATOR_TYPES) {
      LongObjectHashMap<LongTermsAndValues> rows = randomRows(random, 60);
      TermIndex index = new TermIndex(config(comparatorType), rows, longObjectMap());
      ScanIndex bruteForce =
          new ScanIndex(config(comparatorType, Map.of(), "scan"), rows, longObjectMap());

      for (int trial = 0; trial < 40; ++trial) {
        LongTermsAndValues query = randomSequence(random);
        for (float minSimilarity : MIN_SIMILARITIES) {
          List<RowNumAndSimilarity> expected =
              restrictToRowsSharingAnElement(
                  bruteForce.getSimilarRowNums(minSimilarity, query, null), rows, query);

          assertEquivalent(
              String.format(
                  "%s trial=%d minSimilarity=%s query=%s",
                  comparatorType, trial, minSimilarity, Arrays.toString(query.getTerms())),
              expected,
              index.getSimilarRowNums(minSimilarity, query, null));
        }
      }
    }
  }

  @Test
  void nearestNeighborSearchMatchesABruteForceScan() {
    Random random = new Random(5_150L);
    for (String comparatorType : COMPARATOR_TYPES) {
      LongObjectHashMap<LongTermsAndValues> rows = randomRows(random, 40);
      TermIndex index = new TermIndex(config(comparatorType), rows, longObjectMap());
      ScanIndex bruteForce =
          new ScanIndex(config(comparatorType, Map.of(), "scan"), rows, longObjectMap());

      for (int trial = 0; trial < 25; ++trial) {
        LongTermsAndValues query = randomSequence(random);
        int k = 1 + random.nextInt(5);
        List<RowNumAndSimilarity> expected =
            restrictToRowsSharingAnElement(
                bruteForce.getSimilarRowNums(0.0f, query, null), rows, query);
        expected.sort(RowNumAndSimilarity.NEAREST_FIRST);

        List<RowNumAndSimilarity> actual =
            new ArrayList<>(index.getNearestNeighborRowNums(k, query, null));

        // Tied rows are interchangeable in a top-k, so the similarities must agree, not the choice.
        assertEquals(
            Math.min(k, expected.size()),
            actual.size(),
            comparatorType + " trial=" + trial + " result count");
        actual.sort(RowNumAndSimilarity.NEAREST_FIRST);
        for (int i = 0; i < actual.size(); ++i) {
          assertEquals(
              expected.get(i).getSimilarity(),
              actual.get(i).getSimilarity(),
              DELTA,
              comparatorType + " trial=" + trial + " similarity at " + i);
        }
      }
    }
  }

  @Test
  void aQueryFindsARowItSharesOnlyRepeatedElementsWith() {
    LongObjectHashMap<LongTermsAndValues> rows = longObjectMap();
    rows.put(1, sequence(4, 4, 4, 4));
    TermIndex index = new TermIndex(config("ngld"), rows, longObjectMap());

    List<RowNumAndSimilarity> results = index.getSimilarRowNums(0.5f, sequence(4, 4, 4), null);

    assertEquals(1, results.size(), results.toString());
    // One deletion over a combined length of seven: NGLD = 2/8, so the similarity is 0.75.
    assertEquals(0.75f, results.get(0).getSimilarity(), DELTA);
  }

  @Test
  void orderIsWhatSeparatesTwoRowsWithTheSameElements() {
    LongObjectHashMap<LongTermsAndValues> rows = longObjectMap();
    rows.put(1, sequence(1, 2, 3, 4));
    rows.put(2, sequence(4, 3, 2, 1));
    TermIndex index = new TermIndex(config("ngld"), rows, longObjectMap());

    List<RowNumAndSimilarity> results = index.getSimilarRowNums(0.0f, sequence(1, 2, 3, 4), null);

    assertEquals(2, results.size(), results.toString());
    // Identical multisets, so a multiset measure would score these two alike; the order does not.
    assertEquals(1.0f, similarityOf(results, 1), DELTA);
    assertTrue(
        similarityOf(results, 2) < 1.0f,
        "the reversed row should not be an exact match: " + results);
  }

  @Test
  void aRecordCarryingValuesIsRejected() {
    LongObjectHashMap<LongTermsAndValues> rows = longObjectMap();
    rows.put(1, LongTermsAndValuesTestFactory.create(new long[] {1, 2}, new float[] {1f, 1f}, 2.0));

    IndexCreationError error =
        assertThrows(
            IndexCreationError.class, () -> new TermIndex(config("ngld"), rows, longObjectMap()));

    assertTrue(error.getMessage().contains("must have no values"), error.getMessage());
  }

  /** Paired with a comparator that reads sparse records, these rows are short of values. */
  @Test
  void sequenceRowsAreRejectedForAComparatorThatReadsSparseRecords() {
    LongObjectHashMap<LongTermsAndValues> rows = longObjectMap();
    rows.put(1, sequence(1, 2));
    NamespaceConfig config = config("jaccard");

    IndexCreationError error =
        assertThrows(
            IndexCreationError.class, () -> new TermIndex(config, rows, longObjectMap()));

    assertTrue(
        error.getMessage().contains("must have equal non-empty terms and values lengths"),
        error.getMessage());
  }

  @Test
  void aPopularElementIsDroppedFromTheSequencesAndNotOnlyFromTheInvertedLists() {
    LongObjectHashMap<LongTermsAndValues> rows = longObjectMap();
    // Element 9 is in all four rows, so a 0.75 cap makes it the only popular one.
    rows.put(1, sequence(9, 1, 2));
    rows.put(2, sequence(1, 9, 2));
    rows.put(3, sequence(9, 3, 4));
    rows.put(4, sequence(9, 5, 6));

    TermIndex index =
        new TermIndex(
            config("ngld", Map.of(Constants.MAX_FRACTION_IDS_PER_TERM, "0.75"), "inverted_term"),
            rows,
            longObjectMap());

    assertEquals(0, index.getRowNumsForKeyForTests(9).length);
    long[] rowNumsForElementTwo = index.getRowNumsForKeyForTests(2).clone();
    Arrays.sort(rowNumsForElementTwo);
    assertArrayEquals(new long[] {1, 2}, rowNumsForElementTwo);
    // The element is gone from the scored sequence too, so rows 1 and 2 become identical.
    assertArrayEquals(new long[] {1, 2}, index.getVerificationRow(1).getTerms());
    assertArrayEquals(new long[] {1, 2}, index.getVerificationRow(2).getTerms());
    assertEquals(2.0, index.getVerificationRow(1).getUniValue(), DELTA);

    List<RowNumAndSimilarity> results = index.getSimilarRowNums(1.0f, sequence(9, 1, 2), null);

    assertEquals(2, results.size(), results.toString());
    for (RowNumAndSimilarity result : results) {
      assertEquals(1.0f, result.getSimilarity(), DELTA, results.toString());
    }
  }

  @Test
  void candidatesOnlyKeepsThePopularElementInTheScoredSequences() {
    LongObjectHashMap<LongTermsAndValues> rows = longObjectMap();
    rows.put(1, sequence(9, 1, 2));
    rows.put(2, sequence(1, 9, 2));
    rows.put(3, sequence(9, 3, 4));
    rows.put(4, sequence(9, 5, 6));

    TermIndex index =
        new TermIndex(
            config(
                "ngld",
                Map.of(
                    Constants.MAX_FRACTION_IDS_PER_TERM,
                    "0.75",
                    Constants.POPULAR_TERM_DISCARD_SCOPE,
                    PopularTermDiscardScope.CANDIDATES_ONLY.getParamValue()),
                "inverted_term"),
            rows,
            longObjectMap());

    assertEquals(0, index.getRowNumsForKeyForTests(9).length);
    assertEquals(2, index.getIndexedRow(1).termsLength());
    // The scored sequences keep the discarded element, so rows 1 and 2 are transpositions apart.
    assertArrayEquals(new long[] {9, 1, 2}, index.getVerificationRow(1).getTerms());
    assertArrayEquals(new long[] {1, 9, 2}, index.getVerificationRow(2).getTerms());

    List<RowNumAndSimilarity> results = index.getSimilarRowNums(1.0f, sequence(9, 1, 2), null);

    assertEquals(1, results.size(), results.toString());
    assertEquals(1, results.get(0).getRowNum(), results.toString());
    assertEquals(1.0f, results.get(0).getSimilarity(), DELTA, results.toString());
  }

  @Test
  void aSequenceLeftEmptyByTheDiscardIsSimplyNotIndexed() {
    LongObjectHashMap<LongTermsAndValues> rows = longObjectMap();
    // Both of row 1's elements are popular, so nothing of it survives to be indexed or scored.
    rows.put(1, sequence(8, 9));
    rows.put(2, sequence(8, 9, 1));
    rows.put(3, sequence(9, 8, 2));
    rows.put(4, sequence(8, 9, 3));

    TermIndex index =
        new TermIndex(
            config("ngld", Map.of(Constants.MAX_FRACTION_IDS_PER_TERM, "0.75"), "inverted_term"),
            rows,
            longObjectMap());

    assertEquals(0, index.getVerificationRow(1).termsLength());
    assertEquals(1, index.getSimilarRowNums(0.5f, sequence(8, 9, 1), null).size());
    // A query that does not survive the discard matches nothing rather than everything.
    assertTrue(index.getSimilarRowNums(0.0f, sequence(8, 9), null).isEmpty());
  }

  @Test
  void aDeletedRowIsExcludedFromResults() {
    LongObjectHashMap<LongTermsAndValues> rows = longObjectMap();
    rows.put(1, sequence(1, 2, 3));
    rows.put(2, sequence(1, 2, 3));
    TermIndex index = new TermIndex(config("ngld"), rows, longObjectMap());

    assertTrue(index.delete(1));
    List<RowNumAndSimilarity> results = index.getSimilarRowNums(0.5f, sequence(1, 2, 3), null);

    assertEquals(1, results.size(), results.toString());
    assertEquals(2, results.get(0).getRowNum());
  }

  @Test
  void metadataFilteringExcludesRowsThatDoNotMatch() {
    LongObjectHashMap<LongTermsAndValues> rows = longObjectMap();
    rows.put(1, sequence(1, 2, 3));
    rows.put(2, sequence(1, 2, 3));
    LongObjectHashMap<LongMeta> metadata = longObjectMap();
    metadata.put(1, longMeta("city", "sf"));
    metadata.put(2, longMeta("city", "la"));
    TermIndex index = new TermIndex(config("ngld"), rows, metadata);

    List<RowNumAndSimilarity> results =
        index.getSimilarRowNums(
            0.5f, sequence(1, 2, 3), new MetaFilter(Map.of("city", List.of("sf"))));

    assertEquals(1, results.size(), results.toString());
    assertEquals(1, results.get(0).getRowNum());
  }

  private static LongMeta longMeta(String key, String value) {
    return new LongMeta(Map.of(key, value), /* requireLongKeysAndValues */ false);
  }

  private static float similarityOf(List<RowNumAndSimilarity> results, long rowNum) {
    for (RowNumAndSimilarity result : results) {
      if (result.getRowNum() == rowNum) {
        return result.getSimilarity();
      }
    }
    throw new AssertionError("rowNum " + rowNum + " is absent from " + results);
  }

  /** Drops the rows sharing no element with the query, which the inverted lists cannot reach. */
  private static List<RowNumAndSimilarity> restrictToRowsSharingAnElement(
      List<RowNumAndSimilarity> results,
      LongObjectHashMap<LongTermsAndValues> rows,
      LongTermsAndValues query) {
    LongHashSet queryElements = LongHashSet.from(query.getTerms());
    List<RowNumAndSimilarity> restricted = new ArrayList<>(results.size());
    for (RowNumAndSimilarity result : results) {
      LongTermsAndValues row = rows.get(result.getRowNum());
      for (int index = 0; index < row.termsLength(); ++index) {
        if (queryElements.contains(row.getTerm(index))) {
          restricted.add(result);
          break;
        }
      }
    }
    return restricted;
  }

  private static LongObjectHashMap<LongTermsAndValues> randomRows(Random random, int numRows) {
    LongObjectHashMap<LongTermsAndValues> rows = longObjectMap();
    for (int rowNum = 1; rowNum <= numRows; ++rowNum) {
      rows.put(rowNum, randomSequence(random));
    }
    return rows;
  }

  /** A short sequence over a small alphabet, so repeats and shared elements are both common. */
  private static LongTermsAndValues randomSequence(Random random) {
    long[] elements = new long[1 + random.nextInt(7)];
    for (int index = 0; index < elements.length; ++index) {
      elements[index] = random.nextInt(5);
    }
    return LongTermsAndValuesTestFactory.create(elements, NO_VALUES, elements.length);
  }

  private static LongTermsAndValues sequence(long... elements) {
    return LongTermsAndValuesTestFactory.create(elements, NO_VALUES, elements.length);
  }

  private static NamespaceConfig config(String comparatorType) {
    return config(comparatorType, Map.of(), "inverted_term");
  }

  private static NamespaceConfig config(
      String comparatorType, Map<String, String> indexParams, String indexType) {
    return NamespaceConfig.builder()
        .minTermsAndValuesLength(0)
        .maxTermsAndValuesLength(100)
        .maxCacheSize(100)
        .cacheType("scan")
        .indexType(indexType)
        .indexParams(indexParams)
        .comparatorType(comparatorType)
        // GLD reports an unbounded distance, so it needs a normalizer that maps one onto [0, 1].
        .comparatorNormalizerType(comparatorType.equals("gld") ? "reciprocal" : "complement")
        .maxNumSearchableStructures(3)
        .maxNumSimilarities(100)
        .build();
  }

  private static void assertEquivalent(
      String message, List<RowNumAndSimilarity> expected, List<RowNumAndSimilarity> actual) {
    List<RowNumAndSimilarity> sortedExpected = new ArrayList<>(expected);
    List<RowNumAndSimilarity> sortedActual = new ArrayList<>(actual);
    sortedExpected.sort(RowNumAndSimilarity.NEAREST_FIRST);
    sortedActual.sort(RowNumAndSimilarity.NEAREST_FIRST);
    assertEquals(
        sortedExpected.stream().map(RowNumAndSimilarity::getRowNum).sorted().toList(),
        sortedActual.stream().map(RowNumAndSimilarity::getRowNum).sorted().toList(),
        message + " expected=" + sortedExpected + " actual=" + sortedActual);
    for (int i = 0; i < sortedExpected.size(); ++i) {
      assertEquals(
          sortedExpected.get(i).getSimilarity(), sortedActual.get(i).getSimilarity(), DELTA);
    }
  }

  /** Guards the randomized cases against silently degenerating into empty result sets. */
  @Test
  void theRandomizedCorpusProducesMatchesToCompare() {
    Random random = new Random(7_311L);
    LongObjectHashMap<LongTermsAndValues> rows = randomRows(random, 60);
    TermIndex index = new TermIndex(config("ngld"), rows, longObjectMap());
    int numMatches = 0;
    for (LongObjectCursor<LongTermsAndValues> row : rows) {
      numMatches += index.getSimilarRowNums(0.5f, row.value, null).size();
    }

    assertTrue(numMatches > 100, "expected the corpus to produce matches, got " + numMatches);
  }
}
