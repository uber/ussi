/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.utils.parallel;

import com.carrotsearch.hppc.LongHashSet;
import com.carrotsearch.hppc.LongObjectHashMap;
import com.carrotsearch.hppc.cursors.LongCursor;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;
import com.uber.ussi.searchablestructure.result.ResultHeaps;
import com.uber.ussi.searchablestructure.result.RowNumAndSimilarity;
import com.uber.ussi.utils.BoundedSizeMaxHeap;
import java.util.List;

/**
 * A scan of one structure's rows, divided between the threads one search may use.
 *
 * <p>This is the counterpart of {@link ParallelShardSearch}, which runs one complete search per
 * shard. Here there is one structure and one scan, and what the threads divide is its rows.
 *
 * <p>Rows score independently and only the best few survive, so ranges of the rows can be scanned
 * at the same time. Each range keeps its own heap, and the heaps merge once the ranges finish, a
 * merge of the range count multiplied by the result count. Both the rows a structure holds and a
 * set of candidate rows a metadata filter produced are scanned this way.
 *
 * <p>Only a scan whose rows all score against the same minimum similarity belongs here. A scan that
 * raises its minimum similarity as its heap fills prunes using what it has already scored. Ranges
 * each raising a minimum similarity from their own heap would prune less than the whole scan does,
 * so such a scan keeps its single heap and its pruning instead. How much pruning it would lose
 * depends on the data, so no measurement would settle it.
 *
 * <p>Whether to divide the scan at all is worth deciding, because a scan can be short enough that
 * submitting its ranges costs more than the scan. How finely to divide it is not, because that cost
 * does not grow with the number of ranges enough to matter. A scan below a minimum amount of work
 * therefore runs on the calling thread, and a scan above it uses every thread it may.
 *
 * <p>The minimum earns its place at high query rates rather than on an idle machine. The budget is
 * derived from the concurrent searches observed. Searches short enough to leave the cores idle
 * between them are counted as fewer concurrent searches than they impose. The budget can therefore
 * stand above one thread while a small cache serves hundreds of thousands of searches a second.
 * Submitting ranges for each of those searches costs far more than it saves.
 */
public final class ParallelRowScan {

  /**
   * Row visits below which a scan is not worth dividing between threads, where a visit is one row
   * element compared. Measured as the point at which dividing stops losing, on record lengths an
   * order of magnitude apart and on machines with a core count apart.
   */
  private static final int MIN_NUM_ROW_VISITS_TO_DIVIDE = 4_096;

  private ParallelRowScan() {}

  /** Scores a row the scan holds, keeping the best of them. */
  @FunctionalInterface
  public interface RowScorer {
    void score(
        long rowNum,
        LongTermsAndValues termsAndValues,
        BoundedSizeMaxHeap<RowNumAndSimilarity> rows,
        SharedMinSimilarity sharedMinSimilarity);
  }

  /** Scores a candidate row, which the caller looks up itself, keeping the best of them. */
  @FunctionalInterface
  public interface CandidateScorer {
    void score(
        long rowNum,
        BoundedSizeMaxHeap<RowNumAndSimilarity> rows,
        SharedMinSimilarity sharedMinSimilarity);
  }

  /** Scores the rows one range covers, keeping the best of them. */
  @FunctionalInterface
  private interface RangeScorer {
    List<RowNumAndSimilarity> score(
        int fromIndex, int toIndex, SharedMinSimilarity sharedMinSimilarity);
  }

  /**
   * The best {@code maxResults} rows of {@code rowNumToTermsAndValuesMap} by {@code scorer},
   * scanned in at most {@code numThreads} ranges.
   *
   * <p>The result is the result of scanning sequentially. Ranges see disjoint rows and share
   * nothing, and the heap order is total, so the merged heap holds what a single heap would hold.
   *
   * <p>Callers hold the map still for the duration, as they do for a sequential scan.
   */
  public static List<RowNumAndSimilarity> search(
      LongObjectHashMap<LongTermsAndValues> rowNumToTermsAndValuesMap,
      LongTermsAndValues record,
      int numThreads,
      int maxResults,
      float minSimilarity,
      RowScorer scorer) {
    return scanInRanges(
        getNumRanges(rowNumToTermsAndValuesMap.size(), numVisitsPerRow(record), numThreads),
        rowNumToTermsAndValuesMap.keys.length,
        maxResults,
        minSimilarity,
        (fromSlot, toSlot, sharedMinSimilarity) ->
            scanSlots(
                rowNumToTermsAndValuesMap, fromSlot, toSlot, maxResults, scorer,
                sharedMinSimilarity));
  }

  /**
   * The best {@code maxResults} of {@code candidateRowNums} by {@code scorer}, scanned in at most
   * {@code numThreads} ranges. As with a scan of held rows, the result is the result of scanning
   * sequentially, and the caller holds the rows still for the duration.
   */
  public static List<RowNumAndSimilarity> searchCandidates(
      LongHashSet candidateRowNums,
      LongTermsAndValues record,
      int numThreads,
      int maxResults,
      float minSimilarity,
      CandidateScorer scorer) {
    int numRanges = getNumRanges(candidateRowNums.size(), numVisitsPerRow(record), numThreads);
    if (numRanges == 1) {
      // Scanned where they lie, so a scan not worth dividing does not pay to copy them out. One
      // range shares its minimum similarity with nobody, and raises it from its own heap alone.
      BoundedSizeMaxHeap<RowNumAndSimilarity> rows = ResultHeaps.newTopResults(maxResults);
      SharedMinSimilarity sharedMinSimilarity = new SharedMinSimilarity(minSimilarity);
      for (LongCursor candidate : candidateRowNums) {
        scorer.score(candidate.value, rows, sharedMinSimilarity);
      }
      return rows.toList();
    }
    // A set cannot be divided into slot ranges the way the row map can. The map marks an
    // empty slot by holding no value in it. A set holds only keys, and keeps the zero key
    // outside its slots without exposing whether it is there, so a slot range cannot tell
    // row zero from an empty slot. Copying the candidates out gives the ranges something
    // they can index.
    long[] rowNums = candidateRowNums.toArray();
    return scanInRanges(
        numRanges,
        rowNums.length,
        maxResults,
        minSimilarity,
        (fromIndex, toIndex, sharedMinSimilarity) ->
            scanRowNums(rowNums, fromIndex, toIndex, maxResults, scorer, sharedMinSimilarity));
  }

  /**
   * Ranges to scan {@code numRows} in: every thread this search may use once the scan is worth
   * dividing, and one before that. No range is without a row in it.
   */
  static int getNumRanges(int numRows, int numVisitsPerRow, int numThreads) {
    if ((long) numRows * numVisitsPerRow < MIN_NUM_ROW_VISITS_TO_DIVIDE) {
      return 1;
    }
    return Math.max(1, Math.min(numThreads, numRows));
  }

  /**
   * Scans {@code numIndexes} worth of rows in {@code numRanges} ranges, merging what each keeps.
   */
  private static List<RowNumAndSimilarity> scanInRanges(
      int numRanges,
      int numIndexes,
      int maxResults,
      float minSimilarity,
      RangeScorer rangeScorer) {
    int indexesPerRange = (numIndexes + numRanges - 1) / numRanges;
    return ParallelSearch.searchAndMerge(
        numRanges,
        maxResults,
        minSimilarity,
        (range, sharedMinSimilarity) -> {
          int fromIndex = range * indexesPerRange;
          int toIndex = Math.min(numIndexes, fromIndex + indexesPerRange);
          return fromIndex >= toIndex
              ? List.of()
              : rangeScorer.score(fromIndex, toIndex, sharedMinSimilarity);
        });
  }

  /**
   * Row elements a comparison visits, used only to decide whether to divide. Either array is empty
   * when the record carries the other alone, and they match in length when it carries both.
   */
  private static int numVisitsPerRow(LongTermsAndValues record) {
    return Math.max(1, Math.max(record.getTerms().length, record.getValues().length));
  }

  /** Scores the rows held in a slot range. Empty slots hold no value and are skipped. */
  private static List<RowNumAndSimilarity> scanSlots(
      LongObjectHashMap<LongTermsAndValues> rowNumToTermsAndValuesMap,
      int fromSlot,
      int toSlot,
      int maxResults,
      RowScorer scorer,
      SharedMinSimilarity sharedMinSimilarity) {
    BoundedSizeMaxHeap<RowNumAndSimilarity> rows = ResultHeaps.newTopResults(maxResults);
    long[] rowNums = rowNumToTermsAndValuesMap.keys;
    Object[] termsAndValues = rowNumToTermsAndValuesMap.values;
    for (int slot = fromSlot; slot < toSlot; slot++) {
      Object value = termsAndValues[slot];
      if (value == null) {
        continue;
      }
      scorer.score(rowNums[slot], (LongTermsAndValues) value, rows, sharedMinSimilarity);
    }
    return rows.toList();
  }

  /** Scores the candidate rows an index range covers. */
  private static List<RowNumAndSimilarity> scanRowNums(
      long[] rowNums,
      int fromIndex,
      int toIndex,
      int maxResults,
      CandidateScorer scorer,
      SharedMinSimilarity sharedMinSimilarity) {
    BoundedSizeMaxHeap<RowNumAndSimilarity> rows = ResultHeaps.newTopResults(maxResults);
    for (int index = fromIndex; index < toIndex; index++) {
      scorer.score(rowNums[index], rows, sharedMinSimilarity);
    }
    return rows.toList();
  }

}
