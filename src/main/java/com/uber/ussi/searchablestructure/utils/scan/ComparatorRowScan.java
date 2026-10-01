/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.utils.scan;

import com.uber.ussi.comparator.Comparator;
import com.uber.ussi.entity.termsandvalues.LongTermsAndValues;
import com.uber.ussi.searchablestructure.result.ResultHeaps;
import com.uber.ussi.searchablestructure.result.RowNumAndSimilarity;
import com.uber.ussi.searchablestructure.utils.parallel.SharedMinSimilarity;
import com.uber.ussi.utils.BoundedSizeMaxHeap;
import java.util.Objects;

/** Comparator scoring shared by full-scan caches and indexes. */
public final class ComparatorRowScan {

  private ComparatorRowScan() {}

  /**
   * Scores {@code candidateRow} against {@code query} and adds it to {@code rows} when similarity
   * meets the tightened minimum.
   */
  public static void tryAddSimilarRow(
      BoundedSizeMaxHeap<RowNumAndSimilarity> rows,
      long rowNum,
      LongTermsAndValues query,
      LongTermsAndValues candidateRow,
      Comparator comparator,
      float minSimilarity,
      SharedMinSimilarity sharedMinSimilarity) {
    Objects.requireNonNull(rows, "rows is null.");
    Objects.requireNonNull(query, "query is null.");
    Objects.requireNonNull(candidateRow, "candidateRow is null.");
    Objects.requireNonNull(comparator, "comparator is null.");
    Objects.requireNonNull(sharedMinSimilarity, "sharedMinSimilarity is null.");
    float tightened =
        ResultHeaps.tightenedMinSimilarity(rows, minSimilarity, sharedMinSimilarity);
    float similarity = (float) comparator.getSimilarity(query, candidateRow, tightened);
    if (similarity >= tightened) {
      rows.add(new RowNumAndSimilarity(rowNum, similarity));
    }
  }
}
