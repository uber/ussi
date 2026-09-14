/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.inverted.generator;

import java.util.Arrays;

/**
 * The uni-sorted rows of one key, with the rows' values at that key alongside.
 *
 * <p>The arrays are owned by the index and must not be mutated. Values are populated only when a
 * candidate generator scores from them.
 *
 * <p>Public only for the sibling inverted index packages.
 */
public final class InvertedList {
  private final long[] rowNums;
  private final float[] values;

  public InvertedList(long[] rowNums, float[] values) {
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
    if (!(object instanceof InvertedList)) {
      return false;
    }
    InvertedList that = (InvertedList) object;
    return Arrays.equals(rowNums, that.rowNums) && Arrays.equals(values, that.values);
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(rowNums) * 31 + Arrays.hashCode(values);
  }
}
