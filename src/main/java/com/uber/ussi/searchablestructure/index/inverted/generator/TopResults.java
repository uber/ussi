/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.inverted.generator;

import com.uber.ussi.searchablestructure.RowNumAndSimilarity;
import com.uber.ussi.utils.BoundedSizeMaxHeap;

/**
 * The bounded top-results heap shared by the candidate generators.
 *
 * <p>Public only so that the inverted indexes in the sibling packages can reach it. Nothing outside
 * this library's inverted implementation should depend on it.
 */
public final class TopResults {
  private TopResults() {}

  public static BoundedSizeMaxHeap<RowNumAndSimilarity> newTopResultsHeap(int maxResults) {
    return new BoundedSizeMaxHeap<>(maxResults, RowNumAndSimilarity.TOP_RESULTS_HEAP_ORDER);
  }

  /**
   * Returns the similarity a row must beat once the heap is full. The weakest kept similarity still
   * qualifies, so the threshold sits one step below it.
   */
  public static double getConservativeMinSimilarity(BoundedSizeMaxHeap<RowNumAndSimilarity> rows) {
    return Math.nextDown(rows.peek().getSimilarity());
  }
}
