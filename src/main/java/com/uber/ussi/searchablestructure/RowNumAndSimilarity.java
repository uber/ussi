/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure;

import java.util.Objects;

/** A single USSI search hit keyed by the internal row number. */
public final class RowNumAndSimilarity {
  /** Output order: highest similarity first, then lowest rowNum for deterministic ties. */
  public static final java.util.Comparator<RowNumAndSimilarity> NEAREST_FIRST =
      java.util.Comparator.comparingDouble(RowNumAndSimilarity::getSimilarity)
          .reversed()
          .thenComparingLong(RowNumAndSimilarity::getRowNum);

  /**
   * Heap order: the least desirable retained result compares first, so a bounded heap can evict it.
   */
  public static final java.util.Comparator<RowNumAndSimilarity> TOP_RESULTS_HEAP_ORDER =
      java.util.Comparator.comparingDouble(RowNumAndSimilarity::getSimilarity)
          .thenComparing(
              java.util.Comparator.comparingLong(RowNumAndSimilarity::getRowNum).reversed());

  private final long rowNum;
  private final float similarity;

  public RowNumAndSimilarity(long rowNum, float similarity) {
    this.rowNum = rowNum;
    this.similarity = similarity;
  }

  public long getRowNum() {
    return rowNum;
  }

  public float getSimilarity() {
    return similarity;
  }

  @Override
  public String toString() {
    return "RowNumAndSimilarity{" + "rowNum=" + rowNum + ", similarity=" + similarity + '}';
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof RowNumAndSimilarity)) {
      return false;
    }
    RowNumAndSimilarity that = (RowNumAndSimilarity) o;
    return rowNum == that.rowNum && Float.compare(that.similarity, similarity) == 0;
  }

  @Override
  public int hashCode() {
    return Objects.hash(rowNum, similarity);
  }
}
