/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.utils.parallel;

import com.uber.ussi.searchablestructure.result.RowNumAndSimilarity;
import com.uber.ussi.utils.BoundedSizeMaxHeap;
import java.util.List;

/**
 * Searches the work units one search divides into and keeps the nearest rows across all of them.
 *
 * <p>A work unit is a shard of a structure or a range of its rows. Either way the work units run on
 * the threads {@link SearchThreads} gives out, and what each keeps is merged once all of them have
 * finished.
 *
 * <p>Each work unit keeps its own heap. No heap is shared between threads.
 */
final class ParallelSearch {

  private ParallelSearch() {}

  /**
   * The nearest {@code maxResults} rows across {@code numWorkUnits} work units.
   *
   * <p>The work units share one minimum similarity, seeded at {@code minSimilarity}, so that each
   * prunes at what any of them has proved. That recovers the pruning they lose by keeping separate
   * heaps.
   */
  static List<RowNumAndSimilarity> searchAndMerge(
      int numWorkUnits, int maxResults, float minSimilarity, WorkUnitSearcher searcher) {
    BoundedSizeMaxHeap<RowNumAndSimilarity> nearestRowNums =
        new BoundedSizeMaxHeap<>(maxResults, RowNumAndSimilarity.TOP_RESULTS_HEAP_ORDER);
    if (numWorkUnits <= 0) {
      return nearestRowNums.toList();
    }
    SharedMinSimilarity sharedMinSimilarity = new SharedMinSimilarity(minSimilarity);
    @SuppressWarnings("unchecked")
    List<RowNumAndSimilarity>[] keptByWorkUnit = new List[numWorkUnits];
    SearchThreads.runInParallel(
        numWorkUnits,
        workUnitNumber ->
            keptByWorkUnit[workUnitNumber] =
                searcher.search(workUnitNumber, sharedMinSimilarity));
    for (List<RowNumAndSimilarity> kept : keptByWorkUnit) {
      if (kept != null) {
        nearestRowNums.addAll(kept);
      }
    }
    return nearestRowNums.toList();
  }
}
