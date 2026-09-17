/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure;

import com.uber.ussi.utils.BoundedSizeMaxHeap;
import java.util.List;

/**
 * Runs several searches of one structure at the same time, and keeps the nearest rows across all of
 * them.
 *
 * <p>A structure is searched this way either as shards or as ranges of its rows. Either way the
 * searches run on the threads {@link SearchThreads} gives out, and what each keeps is merged once
 * all of them have finished.
 *
 * <p>Each search keeps its own heap. No heap is shared between threads.
 */
final class ParallelSearch {

  private ParallelSearch() {}

  /** One of the searches, against a minimum similarity shared with the others. */
  public interface Searcher {
    List<RowNumAndSimilarity> search(int searchNumber, SharedMinSimilarity sharedMinSimilarity);
  }

  /**
   * The nearest {@code maxResults} rows across {@code numSearches} searches.
   *
   * <p>The searches share one minimum similarity, seeded at {@code minSimilarity}, so that each
   * prunes at what any of them has proved. That recovers the pruning they lose by keeping separate
   * heaps.
   */
  static List<RowNumAndSimilarity> searchInParallel(
      int numSearches, int maxResults, float minSimilarity, Searcher searcher) {
    BoundedSizeMaxHeap<RowNumAndSimilarity> nearestRowNums =
        new BoundedSizeMaxHeap<>(maxResults, RowNumAndSimilarity.TOP_RESULTS_HEAP_ORDER);
    if (numSearches <= 0) {
      return nearestRowNums.toList();
    }
    SharedMinSimilarity sharedMinSimilarity = new SharedMinSimilarity(minSimilarity);
    @SuppressWarnings("unchecked")
    List<RowNumAndSimilarity>[] keptBySearch = new List[numSearches];
    SearchThreads.runInParallel(
        numSearches,
        searchNumber ->
            keptBySearch[searchNumber] = searcher.search(searchNumber, sharedMinSimilarity));
    for (List<RowNumAndSimilarity> kept : keptBySearch) {
      if (kept != null) {
        nearestRowNums.addAll(kept);
      }
    }
    return nearestRowNums.toList();
  }
}
