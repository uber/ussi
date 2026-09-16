/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure;

import com.carrotsearch.hppc.LongHashSet;
import com.carrotsearch.hppc.LongObjectHashMap;
import com.carrotsearch.hppc.cursors.LongCursor;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;
import com.uber.ussi.utils.BoundedSizeMaxHeap;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A scan, split across the threads one search may use.
 *
 * <p>Rows score independently and only the best few survive, so parts of the rows can be scanned at
 * the same time: each part keeps its own heap and the heaps merge once the parts finish, a merge of
 * the part count multiplied by the result count. Both the rows a structure holds and a set of
 * candidate rows a metadata filter produced are scanned this way.
 *
 * <p>Only a scan whose rows all score against the same threshold belongs here. A scan that raises
 * its threshold as its heap fills prunes using what it has already scored, and parts each raising a
 * threshold from their own heap would prune less than the whole scan does, so such a scan keeps its
 * single heap and its pruning instead. How much pruning it would lose depends on the data, so no
 * measurement would settle it.
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
public final class ScanSplit {

  /**
   * Row visits below which a scan is not worth handing to other threads, where a visit is one row
   * element compared. Measured as the point at which splitting stops losing, on record lengths an
   * order of magnitude apart and on machines with a core count apart.
   */
  private static final int MIN_ROW_VISITS_TO_SPLIT = 4_096;

  private static final ExecutorService SCANNERS = createScanners();

  private ScanSplit() {}

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

  /** Scores the rows one part covers, keeping the best of them. */
  @FunctionalInterface
  private interface PartScorer {
    BoundedSizeMaxHeap<RowNumAndSimilarity> score(int fromIndex, int toIndex);
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
    return inParts(
        partCount(rowNumToTermsAndValuesMap.size(), rowVisitCost(record), parallelism),
        rowNumToTermsAndValuesMap.keys.length,
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
    int parts = partCount(candidateRowNums.size(), rowVisitCost(record), parallelism);
    if (parts == 1) {
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
    return inParts(
        parts,
        rowNums.length,
        (fromIndex, toIndex) -> scanRowNums(rowNums, fromIndex, toIndex, maxResults, scorer));
  }

  /**
   * Parts to scan {@code numRows} in: every thread this search may use once the scan is worth
   * splitting, and one before that. No part is without a row in it.
   */
  static int partCount(int numRows, int rowVisitCost, int parallelism) {
    if ((long) numRows * rowVisitCost < MIN_ROW_VISITS_TO_SPLIT) {
      return 1;
    }
    return Math.max(1, Math.min(parallelism, numRows));
  }

  /** Scans {@code numIndexes} worth of rows in {@code parts}, merging what the parts keep. */
  private static List<RowNumAndSimilarity> inParts(
      int parts, int numIndexes, PartScorer partScorer) {
    if (parts == 1) {
      return partScorer.score(0, numIndexes).toList();
    }

    int indexesPerPart = (numIndexes + parts - 1) / parts;
    List<Future<BoundedSizeMaxHeap<RowNumAndSimilarity>>> handedOff = new ArrayList<>(parts - 1);
    for (int part = 1; part < parts; part++) {
      int fromIndex = part * indexesPerPart;
      int toIndex = Math.min(numIndexes, fromIndex + indexesPerPart);
      if (fromIndex >= toIndex) {
        break;
      }
      handedOff.add(SCANNERS.submit(() -> partScorer.score(fromIndex, toIndex)));
    }

    // The calling thread takes a part rather than waiting on all of them, which both uses the
    // thread already here and keeps the scan progressing when every scanner thread is busy.
    BoundedSizeMaxHeap<RowNumAndSimilarity> merged =
        partScorer.score(0, Math.min(numIndexes, indexesPerPart));
    for (Future<BoundedSizeMaxHeap<RowNumAndSimilarity>> part : handedOff) {
      merged.addAll(awaitPart(part, handedOff).toList());
    }
    return merged.toList();
  }

  /**
   * Row elements a comparison visits, used only to decide whether to split. Either array is empty
   * when the record carries the other alone, and they match in length when it carries both.
   */
  private static int rowVisitCost(LongTermsAndValues record) {
    return Math.max(1, Math.max(record.getTerms().length, record.getValues().length));
  }

  /** Scores the rows held in a slot range. Empty slots hold no value and are skipped. */
  private static BoundedSizeMaxHeap<RowNumAndSimilarity> scanSlots(
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
    return rows;
  }

  /** Scores the candidate rows an index range covers. */
  private static BoundedSizeMaxHeap<RowNumAndSimilarity> scanRowNums(
      long[] rowNums, int fromIndex, int toIndex, int maxResults, CandidateScorer scorer) {
    BoundedSizeMaxHeap<RowNumAndSimilarity> rows = newTopResultsHeap(maxResults);
    for (int index = fromIndex; index < toIndex; index++) {
      scorer.score(rowNums[index], rows);
    }
    return rows;
  }

  private static BoundedSizeMaxHeap<RowNumAndSimilarity> newTopResultsHeap(int maxResults) {
    return new BoundedSizeMaxHeap<>(maxResults, RowNumAndSimilarity.TOP_RESULTS_HEAP_ORDER);
  }

  /** The result of a part, abandoning the remaining parts if it did not produce one. */
  private static BoundedSizeMaxHeap<RowNumAndSimilarity> awaitPart(
      Future<BoundedSizeMaxHeap<RowNumAndSimilarity>> part,
      List<Future<BoundedSizeMaxHeap<RowNumAndSimilarity>>> parts) {
    try {
      return part.get();
    } catch (InterruptedException e) {
      cancel(parts);
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while scanning part of the rows.", e);
    } catch (ExecutionException e) {
      cancel(parts);
      // A sequential scan would have thrown this from the caller's thread, so it is rethrown.
      if (e.getCause() instanceof RuntimeException) {
        throw (RuntimeException) e.getCause();
      }
      throw new IllegalStateException("Failed to scan part of the rows.", e.getCause());
    }
  }

  private static void cancel(List<Future<BoundedSizeMaxHeap<RowNumAndSimilarity>>> parts) {
    for (Future<BoundedSizeMaxHeap<RowNumAndSimilarity>> part : parts) {
      part.cancel(true);
    }
  }

  /**
   * Threads for the parts a search hands off. Sized to the cores, which is what the searches in
   * flight demand together: searches are admitted up to the core count and each divides the cores
   * among its own parts.
   */
  private static ExecutorService createScanners() {
    AtomicInteger threadNumber = new AtomicInteger(1);
    return Executors.newFixedThreadPool(
        Math.max(1, Runtime.getRuntime().availableProcessors()),
        runnable -> {
          Thread thread = new Thread(runnable, "ussi-scan-" + threadNumber.getAndIncrement());
          thread.setDaemon(true);
          return thread;
        });
  }
}
