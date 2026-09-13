/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.sparse.generator;

import java.util.Arrays;

/**
 * The uni-sorted rows of one sparse key, with the rows' values at that key alongside.
 *
 * <p>The arrays are owned by the index and must not be mutated. Values are only populated when a
 * candidate generator scores from them, so callers that need them must know they were requested.
 *
 * <p>Public only so that the sparse indexes in the parent package can reach it. Nothing outside
 * this library's sparse implementation should depend on it.
 */
public final class SparseInvertedList {
  private final long[] rowNums;
  private final float[] values;

  public SparseInvertedList(long[] rowNums, float[] values) {
    this.rowNums = rowNums;
    this.values = values;
  }

  public long[] getRowNums() {
    return rowNums;
  }

  public float[] getValues() {
    return values;
  }

  public int size() {
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
