/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.matrix;

import com.uber.ussi.searchablestructure.result.ResultHeaps;
import com.uber.ussi.searchablestructure.result.RowNumAndSimilarity;
import com.uber.ussi.utils.BoundedSizeMaxHeap;
import java.util.List;

/**
 * Where a scored row is offered, whatever scored it and wherever it was scored.
 *
 * <p>An implementation offers a row number and the similarity it reached, and this keeps the best
 * of them and discards the rest. Which rows are worth offering is the implementation's to decide:
 * one holding a similarity for every row offers every row that reaches the minimum, and one that
 * has already reduced its rows offers only what survived. Both reach the same answer because the
 * bound and the ordering are applied here rather than in either of them.
 */
final class RowCollector {

  private final BoundedSizeMaxHeap<RowNumAndSimilarity> kept;
  private final float minSimilarity;

  RowCollector(RowSelection selection) {
    this.kept = ResultHeaps.newTopResults(selection.getMaxNumRows());
    this.minSimilarity = selection.getMinSimilarity();
  }

  /** Offers a scored row, which is kept if it reaches the minimum and beats what is held. */
  void offer(long rowNum, float similarity) {
    if (similarity >= minSimilarity) {
      kept.add(new RowNumAndSimilarity(rowNum, similarity));
    }
  }

  List<RowNumAndSimilarity> toList() {
    return kept.toList();
  }
}
