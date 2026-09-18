/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure;

import com.uber.ussi.searchablestructure.parallel.SharedMinSimilarity;
import com.uber.ussi.utils.BoundedSizeMaxHeap;

/**
 * The bounded heap of the rows a search keeps, and the similarity a row must beat to enter it.
 *
 * <p>Every structure keeps its results this way, so that the heaps of two searches of the same
 * rows hold the same rows and merge into what one heap would have held.
 */
public final class TopResults {
  private TopResults() {}

  public static BoundedSizeMaxHeap<RowNumAndSimilarity> newTopResultsHeap(int maxResults) {
    return new BoundedSizeMaxHeap<>(maxResults, RowNumAndSimilarity.TOP_RESULTS_HEAP_ORDER);
  }

  /**
   * Returns the similarity a row must beat once the heap is full. The weakest kept similarity still
   * qualifies, so the minimum similarity sits one step below it.
   */
  public static double getConservativeMinSimilarity(BoundedSizeMaxHeap<RowNumAndSimilarity> rows) {
    return Math.nextDown(rows.peek().getSimilarity());
  }

  /**
   * The similarity a row must reach to be worth scoring, which rises as the heap fills.
   *
   * <p>A full heap already holds as many rows as the search keeps, each at least as similar as its
   * weakest, so no row below that can reach the answer. Until the heap fills, nothing has been
   * proved and the search's own minimum stands. Comparators take this as a bound and abandon a row
   * that cannot reach it, so raising it saves the rest of that row's comparison.
   */
  public static float tightenedMinSimilarity(
      BoundedSizeMaxHeap<RowNumAndSimilarity> rows, float minSimilarity) {
    if (!rows.isFull()) {
      return minSimilarity;
    }
    return Math.max(minSimilarity, (float) getConservativeMinSimilarity(rows));
  }

  /**
   * The same, over a minimum similarity shared with the work units running beside this one. What
   * this work unit has proved is published for them, and what any of them has proved is taken
   * here.
   *
   * <p>Callers reach this for every row, and a row proving nothing new is the common case, so the
   * shared value is read before it is written and written only by a caller that raises it.
   */
  public static float tightenedMinSimilarity(
      BoundedSizeMaxHeap<RowNumAndSimilarity> rows,
      float minSimilarity,
      SharedMinSimilarity sharedMinSimilarity) {
    float proved = tightenedMinSimilarity(rows, minSimilarity);
    float published = sharedMinSimilarity.get();
    if (proved <= published) {
      return published;
    }
    sharedMinSimilarity.raiseTo(proved);
    return proved;
  }
}
