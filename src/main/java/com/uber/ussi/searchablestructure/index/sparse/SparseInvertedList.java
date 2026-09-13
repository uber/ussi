/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.sparse;

import java.util.Arrays;

/**
 * The uni-sorted rows of one sparse key, with the rows' values at that key alongside.
 *
 * <p>The arrays are owned by the index and must not be mutated. Values are only populated when a
 * candidate generator scores from them, so callers that need them must know they were requested.
 */
final class SparseInvertedList {
  private final long[] rowNums;
  private final float[] values;

  SparseInvertedList(long[] rowNums, float[] values) {
    this.rowNums = rowNums;
    this.values = values;
  }

  long[] getRowNums() {
    return rowNums;
  }

  float[] getValues() {
    return values;
  }

  int size() {
    return rowNums.length;
  }

  @Override
  public boolean equals(Object object) {
    if (this == object) {
      return true;
    }
    if (!(object instanceof SparseInvertedList)) {
      return false;
    }
    SparseInvertedList that = (SparseInvertedList) object;
    return Arrays.equals(rowNums, that.rowNums) && Arrays.equals(values, that.values);
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(rowNums) * 31 + Arrays.hashCode(values);
  }
}
