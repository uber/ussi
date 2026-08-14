/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi;

import java.util.Arrays;
import java.util.Objects;

/** Ordered USSI search results. */
public final class SearchResults {
  private static final long[] EMPTY_ROW_NUMS = new long[0];
  private static final float[] EMPTY_SIMILARITIES = new float[0];

  private final long[] rowNums;
  private final float[] similarities;

  public SearchResults(long[] rowNums, float[] similarities) {
    Objects.requireNonNull(rowNums, "rowNums");
    Objects.requireNonNull(similarities, "similarities");
    if (rowNums.length != similarities.length) {
      throw new IllegalArgumentException(
          String.format(
              "rowNums length (%s) and similarities length (%s) must match.",
              rowNums.length, similarities.length));
    }
    this.rowNums = rowNums.length == 0 ? EMPTY_ROW_NUMS : rowNums.clone();
    this.similarities = similarities.length == 0 ? EMPTY_SIMILARITIES : similarities.clone();
  }

  public int size() {
    return rowNums.length;
  }

  public boolean isEmpty() {
    return rowNums.length == 0;
  }

  public long getRowNum(int index) {
    return rowNums[index];
  }

  public float getSimilarity(int index) {
    return similarities[index];
  }

  public long[] getRowNums() {
    return rowNums.clone();
  }

  public float[] getSimilarities() {
    return similarities.clone();
  }

  @Override
  public String toString() {
    return "SearchResults{"
        + "rowNums="
        + Arrays.toString(rowNums)
        + ", similarities="
        + Arrays.toString(similarities)
        + '}';
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof SearchResults)) {
      return false;
    }
    SearchResults that = (SearchResults) o;
    return Arrays.equals(rowNums, that.rowNums) && Arrays.equals(similarities, that.similarities);
  }

  @Override
  public int hashCode() {
    int result = Arrays.hashCode(rowNums);
    result = 31 * result + Arrays.hashCode(similarities);
    return result;
  }
}
