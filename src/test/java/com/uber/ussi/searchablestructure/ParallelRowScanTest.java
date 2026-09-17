/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carrotsearch.hppc.LongHashSet;
import com.carrotsearch.hppc.cursors.LongObjectCursor;
import com.carrotsearch.hppc.LongObjectHashMap;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValuesTestFactory;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Tests that a split scan returns what a sequential scan returns, however it is split. */
public final class ParallelRowScanTest {

  private static final int TERMS_PER_RECORD = 8;

  @Test
  public void returnsWhatASequentialScanReturns() {
    int[][] rowsAndParallelism = {
      {1, 1}, {1, 8}, {500, 1}, {500, 8}, {5_000, 1}, {5_000, 2}, {5_000, 8}, {5_000, 64},
    };
    for (int[] testCase : rowsAndParallelism) {
      int numRows = testCase[0];
      int parallelism = testCase[1];
      LongObjectHashMap<LongTermsAndValues> rows = corpus(numRows);
      LongTermsAndValues query = record(7);

      List<RowNumAndSimilarity> sequential = scanAll(rows, query, /* parallelism */ 1, numRows);
      List<RowNumAndSimilarity> split = scanAll(rows, query, parallelism, numRows);

      assertEquals(
          asMap(sequential),
          asMap(split),
          String.format("%d rows scanned in up to %d parts", numRows, parallelism));
    }
  }

  @Test
  public void keepsTheSameBestRowsWhenOnlySomeAreKept() {
    LongObjectHashMap<LongTermsAndValues> rows = corpus(5_000);
    LongTermsAndValues query = record(7);

    // Scores are distinct per row here, so the best few are a single answer rather than a tie.
    List<RowNumAndSimilarity> sequential = scanAll(rows, query, /* parallelism */ 1, 10);
    List<RowNumAndSimilarity> split = scanAll(rows, query, /* parallelism */ 8, 10);

    assertEquals(10, split.size());
    assertEquals(asMap(sequential), asMap(split));
  }

  @Test
  public void scoresEveryRowExactlyOnce() {
    int numRows = 5_000;
    LongObjectHashMap<LongTermsAndValues> rows = corpus(numRows);
    Map<Long, Long> visits = new ConcurrentHashMap<>();

    ParallelRowScan.search(
        rows,
        record(7),
        /* parallelism */ 8,
        numRows,
        (rowNum, termsAndValues, heap) -> visits.merge(rowNum, 1L, Long::sum));

    assertEquals(numRows, visits.size());
    assertEquals(Set.of(1L), Set.copyOf(visits.values()));
  }

  @Test
  public void scansOnSeveralThreadsWhenThereAreThreadsToScanWith() {
    Set<String> threads = threadsUsedToScan(/* numRows */ 5_000, /* parallelism */ 8);

    assertTrue(threads.size() > 1, "Expected several threads, scanned on " + threads);
  }

  @Test
  public void scansOnTheCallingThreadAloneWhenTheBudgetIsOneThread() {
    Set<String> threads = threadsUsedToScan(/* numRows */ 5_000, /* parallelism */ 1);

    assertEquals(Set.of(Thread.currentThread().getName()), threads);
  }

  @Test
  public void scansOnTheCallingThreadAloneWhenTheScanIsTooSmallToHandOut() {
    Set<String> threads = threadsUsedToScan(/* numRows */ 10, /* parallelism */ 8);

    assertEquals(Set.of(Thread.currentThread().getName()), threads);
  }

  @Test
  public void splitsOnlyScansWorthHandingOut() {
    int[][] rowsVisitCostParallelismAndParts = {
      // Too little work to be worth handing out, whatever the threads on offer.
      {1, 1, 8, 1},
      {10, 8, 8, 1},
      {255, 16, 8, 1},
      // Past the minimum the scan uses every thread it may, rather than holding parts back.
      {256, 16, 8, 8},
      {256, 16, 48, 48},
      {1_000_000, 8, 8, 8},
      // The budget caps the count however much work there is.
      {1_000_000, 8, 1, 1},
      {1_000_000, 8, 3, 3},
      // Longer records reach the minimum in fewer rows.
      {512, 1, 8, 1},
      {512, 8, 8, 8},
      // No part without a row in it, however long the records.
      {2, 8_192, 48, 2},
    };
    for (int[] testCase : rowsVisitCostParallelismAndParts) {
      int numRows = testCase[0];
      int rowVisitCost = testCase[1];
      int parallelism = testCase[2];

      assertEquals(
          testCase[3],
          ParallelRowScan.rangeCount(numRows, rowVisitCost, parallelism),
          String.format(
              "%d rows costing %d visits each, in up to %d parts",
              numRows, rowVisitCost, parallelism));
    }
  }

  @Test
  public void returnsWhatASequentialScanOfCandidatesReturns() {
    int[][] candidatesAndParallelism = {
      {1, 8}, {500, 1}, {500, 8}, {5_000, 2}, {5_000, 8}, {5_000, 64},
    };
    for (int[] testCase : candidatesAndParallelism) {
      int numCandidates = testCase[0];
      int parallelism = testCase[1];
      LongObjectHashMap<LongTermsAndValues> rows = corpus(numCandidates);
      LongHashSet candidates = candidatesOf(rows);
      LongTermsAndValues query = record(7);

      List<RowNumAndSimilarity> sequential =
          scanCandidates(candidates, rows, query, /* parallelism */ 1, numCandidates);
      List<RowNumAndSimilarity> split =
          scanCandidates(candidates, rows, query, parallelism, numCandidates);

      assertEquals(
          asMap(sequential),
          asMap(split),
          String.format("%d candidates scanned in up to %d parts", numCandidates, parallelism));
    }
  }

  @Test
  public void scoresEveryCandidateExactlyOnceIncludingRowZero() {
    // A set keeps row zero apart from its slots and will not say whether it holds it, so a split
    // that reads those slots loses row zero silently. This is what the candidates are copied for.
    int numCandidates = 5_000;
    LongHashSet candidates = candidatesOf(corpus(numCandidates));
    Map<Long, Long> visits = new ConcurrentHashMap<>();

    ParallelRowScan.searchCandidates(
        candidates,
        record(7),
        /* parallelism */ 8,
        numCandidates,
        (rowNum, heap) -> visits.merge(rowNum, 1L, Long::sum));

    assertTrue(candidates.contains(0), "Expected row zero among the candidates");
    assertTrue(visits.containsKey(0L), "Row zero was never scored");
    assertEquals(numCandidates, visits.size());
    assertEquals(Set.of(1L), Set.copyOf(visits.values()));
  }

  @Test
  public void scansCandidatesOnSeveralThreadsWhenThereAreThreadsToScanWith() {
    Set<String> threads = threadsUsedToScanCandidates(/* numCandidates */ 5_000, 8);

    assertTrue(threads.size() > 1, "Expected several threads, scanned on " + threads);
  }

  @Test
  public void scansCandidatesOnTheCallingThreadAloneWhenTooSmallToHandOut() {
    Set<String> threads = threadsUsedToScanCandidates(/* numCandidates */ 10, 8);

    assertEquals(Set.of(Thread.currentThread().getName()), threads);
  }

  @Test
  public void reportsTheFailureOfAPartAsASequentialScanWould() {
    LongObjectHashMap<LongTermsAndValues> rows = corpus(5_000);
    AtomicLong scored = new AtomicLong();

    IllegalArgumentException failure =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                ParallelRowScan.search(
                    rows,
                    record(7),
                    /* parallelism */ 8,
                    10,
                    (rowNum, termsAndValues, heap) -> {
                      scored.incrementAndGet();
                      throw new IllegalArgumentException("Cannot score row " + rowNum);
                    }));

    assertTrue(failure.getMessage().startsWith("Cannot score row "), failure.getMessage());
    assertTrue(scored.get() > 0);
  }

  /** The threads a scan of {@code numCandidates} candidates runs on. */
  private static Set<String> threadsUsedToScanCandidates(int numCandidates, int parallelism) {
    Set<String> threads = ConcurrentHashMap.newKeySet();
    ParallelRowScan.searchCandidates(
        candidatesOf(corpus(numCandidates)),
        record(7),
        parallelism,
        numCandidates,
        (rowNum, heap) -> threads.add(Thread.currentThread().getName()));
    return Set.copyOf(threads);
  }

  /** Every candidate, scored the way a caller that looks its own rows up would. */
  private static List<RowNumAndSimilarity> scanCandidates(
      LongHashSet candidates,
      LongObjectHashMap<LongTermsAndValues> rows,
      LongTermsAndValues query,
      int parallelism,
      int maxResults) {
    return ParallelRowScan.searchCandidates(
        candidates,
        query,
        parallelism,
        maxResults,
        (rowNum, heap) ->
            heap.add(new RowNumAndSimilarity(rowNum, similarity(query, rows.get(rowNum)))));
  }

  private static LongHashSet candidatesOf(LongObjectHashMap<LongTermsAndValues> rows) {
    LongHashSet candidates = new LongHashSet(rows.size());
    for (LongObjectCursor<LongTermsAndValues> row : rows) {
      candidates.add(row.key);
    }
    return candidates;
  }

  /** The threads a scan of {@code numRows} rows runs on. */
  private static Set<String> threadsUsedToScan(int numRows, int parallelism) {
    Set<String> threads = ConcurrentHashMap.newKeySet();
    ParallelRowScan.search(
        corpus(numRows),
        record(7),
        parallelism,
        numRows,
        (rowNum, termsAndValues, heap) -> threads.add(Thread.currentThread().getName()));
    return Set.copyOf(threads);
  }

  /** Every row, scored by how close its single value is to the query's. */
  private static List<RowNumAndSimilarity> scanAll(
      LongObjectHashMap<LongTermsAndValues> rows,
      LongTermsAndValues query,
      int parallelism,
      int maxResults) {
    return ParallelRowScan.search(
        rows,
        query,
        parallelism,
        maxResults,
        (rowNum, termsAndValues, heap) ->
            heap.add(new RowNumAndSimilarity(rowNum, similarity(query, termsAndValues))));
  }

  /**
   * A stand-in for a comparator, distinct per row so that the best rows are a single answer. Row
   * numbers start at zero, which a hash map holds apart from the rest.
   */
  private static float similarity(LongTermsAndValues query, LongTermsAndValues record) {
    return 1.0f / (1.0f + Math.abs(query.getValues()[0] - record.getValues()[0]));
  }

  private static LongObjectHashMap<LongTermsAndValues> corpus(int numRows) {
    LongObjectHashMap<LongTermsAndValues> rows = new LongObjectHashMap<>();
    for (int rowNum = 0; rowNum < numRows; rowNum++) {
      rows.put(rowNum, record(rowNum));
    }
    return rows;
  }

  private static LongTermsAndValues record(int seed) {
    long[] terms = new long[TERMS_PER_RECORD];
    float[] values = new float[TERMS_PER_RECORD];
    for (int i = 0; i < TERMS_PER_RECORD; i++) {
      terms[i] = i;
      values[i] = seed + i;
    }
    return LongTermsAndValuesTestFactory.create(terms, values, /* uniValue */ seed);
  }

  private static Map<Long, Float> asMap(List<RowNumAndSimilarity> rows) {
    return rows.stream()
        .collect(
            Collectors.toMap(RowNumAndSimilarity::getRowNum, RowNumAndSimilarity::getSimilarity));
  }
}
