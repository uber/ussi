/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure;

import com.carrotsearch.hppc.LongHashSet;
import com.carrotsearch.hppc.LongObjectHashMap;
import com.carrotsearch.hppc.cursors.LongCursor;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;
import com.uber.ussi.utils.BoundedSizeMaxHeap;
import java.util.List;

/**
 * A scan of one structure's rows, divided between the threads one search may use.
 *
 * <p>This is the counterpart of {@link ParallelShardSearch}, which runs one complete search per
 * shard. Here there is one structure and one scan, and what the threads divide is its rows.
 *
 * <p>Rows score independently and only the best few survive, so parts of the rows can be scanned at
 * the same time: each part keeps its own heap and the heaps merge once the parts finish, a merge of
 * the part count multiplied by the result count. Both the rows a structure holds and a set of
 * candidate rows a metadata filter produced are scanned this way.
 *
 * <p>Only a scan whose rows all score against the same minimum similarity belongs here. A scan that
 * raises its minimum similarity as its heap fills prunes using what it has already scored, and
 * parts each raising a minimum similarity from their own heap would prune less than the whole scan
 * does, so such a scan keeps its single heap and its pruning instead. How much pruning it would
 * lose depends on the data, so no measurement would settle it.
 *
 * <p>Whether to split at all is worth deciding, because a scan can be short enough that handing its
 * parts out costs more than the scan; how finely to split is not, because that cost does not grow
 * with the number of parts enough to matter. So a scan below a minimum amount of work runs on the
 * calling thread and a scan above it uses every thread it may.
 *
 * <p>The minimum earns its place at high query rates rather than on an idle machine: the budget is
 * derived from the searches observed in flight, and searches short enough to leave the cores idle
 * between them read as lower concurrency than they impose, so the budget can sit above one thread
 * while a small cache is serving hundreds of thousands of searches a second. Handing out parts for
 * each of them costs far more than it saves.
 */
public final class ParallelRowScan {

  /**
   * Row visits below which a scan is not worth handing to other threads, where a visit is one row
   * element compared. Measured as the point at which splitting stops losing, on record lengths an
   * order of magnitude apart and on machines with a core count apart.
   */
  private static final int MIN_ROW_VISITS_TO_SPLIT = 4_096;

  private ParallelRowScan() {}

  /** Scores a row the scan holds, keeping the best of them. */
  @FunctionalInterface
  public interface RowScorer {
    void score(
        long rowNum,
        LongTermsAndValues termsAndValues,
        BoundedSizeMaxHeap<RowNumAndSimilarity> rows);
  }

  /** Scores a candidate row, which the caller looks up itself, keeping the best of them. */
  @FunctionalInterface
  public interface CandidateScorer {
    void score(long rowNum, BoundedSizeMaxHeap<RowNumAndSimilarity> rows);
  }

  /** Scores the rows one range covers, keeping the best of them. */
  @FunctionalInterface
  private interface RangeScorer {
    List<RowNumAndSimilarity> score(int fromIndex, int toIndex);
  }

  /**
   * The best {@code maxResults} rows of {@code rowNumToTermsAndValuesMap} by {@code scorer},
   * scanned in at most {@code parallelism} parts.
   *
   * <p>The result is the result of scanning sequentially. Parts see disjoint rows and share
   * nothing, and the heap order is total, so the merged heap holds what a single heap would hold.
   *
   * <p>Callers hold the map still for the duration, as they do for a sequential scan.
   */
  public static List<RowNumAndSimilarity> search(
      LongObjectHashMap<LongTermsAndValues> rowNumToTermsAndValuesMap,
      LongTermsAndValues record,
      int parallelism,
      int maxResults,
      RowScorer scorer) {
    return inRanges(
        rangeCount(rowNumToTermsAndValuesMap.size(), rowVisitCost(record), parallelism),
        rowNumToTermsAndValuesMap.keys.length,
        maxResults,
        (fromSlot, toSlot) ->
            scanSlots(rowNumToTermsAndValuesMap, fromSlot, toSlot, maxResults, scorer));
  }

  /**
   * The best {@code maxResults} of {@code candidateRowNums} by {@code scorer}, scanned in at most
   * {@code parallelism} parts. As with a scan of held rows, the result is the result of scanning
   * sequentially, and the caller holds the rows still for the duration.
   */
  public static List<RowNumAndSimilarity> searchCandidates(
      LongHashSet candidateRowNums,
      LongTermsAndValues record,
      int parallelism,
      int maxResults,
      CandidateScorer scorer) {
    int numRanges = rangeCount(candidateRowNums.size(), rowVisitCost(record), parallelism);
    if (numRanges == 1) {
      // Scanned where they lie, so a scan not worth splitting does not pay to copy them out.
      BoundedSizeMaxHeap<RowNumAndSimilarity> rows = newTopResultsHeap(maxResults);
      for (LongCursor candidate : candidateRowNums) {
        scorer.score(candidate.value, rows);
      }
      return rows.toList();
    }
    // A set cannot be divided into slot ranges the way the row map can. The map marks an empty slot
    // by holding no value in it, whereas a set holds only keys and keeps the zero key outside its
    // slots without exposing whether it is there, so a slot range cannot tell row zero from an
    // empty slot. Copying the candidates out gives parts something they can index.
    long[] rowNums = candidateRowNums.toArray();
    return inRanges(
        numRanges,
        rowNums.length,
        maxResults,
        (fromIndex, toIndex) -> scanRowNums(rowNums, fromIndex, toIndex, maxResults, scorer));
  }

  /**
   * Ranges to scan {@code numRows} in: every thread this search may use once the scan is worth
   * splitting, and one before that. No range is without a row in it.
   */
  static int rangeCount(int numRows, int rowVisitCost, int parallelism) {
    if ((long) numRows * rowVisitCost < MIN_ROW_VISITS_TO_SPLIT) {
      return 1;
    }
    return Math.max(1, Math.min(parallelism, numRows));
  }

  /** Scans {@code numIndexes} worth of rows in {@code numRanges} ranges, merging what each keeps. */
  private static List<RowNumAndSimilarity> inRanges(
      int numRanges, int numIndexes, int maxResults, RangeScorer rangeScorer) {
    int indexesPerRange = (numIndexes + numRanges - 1) / numRanges;
    return ParallelSearch.inParallel(
        numRanges,
        maxResults,
        range -> {
          int fromIndex = range * indexesPerRange;
          int toIndex = Math.min(numIndexes, fromIndex + indexesPerRange);
          return fromIndex >= toIndex ? List.of() : rangeScorer.score(fromIndex, toIndex);
        });
  }

  /**
   * Row elements a comparison visits, used only to decide whether to split. Either array is empty
   * when the record carries the other alone, and they match in length when it carries both.
   */
  private static int rowVisitCost(LongTermsAndValues record) {
    return Math.max(1, Math.max(record.getTerms().length, record.getValues().length));
  }

  /** Scores the rows held in a slot range. Empty slots hold no value and are skipped. */
  private static List<RowNumAndSimilarity> scanSlots(
      LongObjectHashMap<LongTermsAndValues> rowNumToTermsAndValuesMap,
      int fromSlot,
      int toSlot,
      int maxResults,
      RowScorer scorer) {
    BoundedSizeMaxHeap<RowNumAndSimilarity> rows = newTopResultsHeap(maxResults);
    long[] rowNums = rowNumToTermsAndValuesMap.keys;
    Object[] termsAndValues = rowNumToTermsAndValuesMap.values;
    for (int slot = fromSlot; slot < toSlot; slot++) {
      Object value = termsAndValues[slot];
      if (value == null) {
        continue;
      }
      scorer.score(rowNums[slot], (LongTermsAndValues) value, rows);
    }
    return rows.toList();
  }

  /** Scores the candidate rows an index range covers. */
  private static List<RowNumAndSimilarity> scanRowNums(
      long[] rowNums, int fromIndex, int toIndex, int maxResults, CandidateScorer scorer) {
    BoundedSizeMaxHeap<RowNumAndSimilarity> rows = newTopResultsHeap(maxResults);
    for (int index = fromIndex; index < toIndex; index++) {
      scorer.score(rowNums[index], rows);
    }
    return rows.toList();
  }

  private static BoundedSizeMaxHeap<RowNumAndSimilarity> newTopResultsHeap(int maxResults) {
    return new BoundedSizeMaxHeap<>(maxResults, RowNumAndSimilarity.TOP_RESULTS_HEAP_ORDER);
  }



}
