/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.sparse;

/**
 * Build-time inverted-list entry ordered by unilateral value and then row number.
 *
 * <p>The row's value at the sparse key travels with it, so sorting cannot separate the two.
 */
final class RowNumAndUniValue implements Comparable<RowNumAndUniValue> {
  private final long rowNum;
  private final double uniValue;
  private final float value;

  RowNumAndUniValue(long rowNum, double uniValue) {
    this(rowNum, uniValue, 0.0f);
  }

  RowNumAndUniValue(long rowNum, double uniValue, float value) {
    this.rowNum = rowNum;
    this.uniValue = uniValue;
    this.value = value;
  }

  long getRowNum() {
    return rowNum;
  }

  float getValue() {
    return value;
  }

  @Override
  public int compareTo(RowNumAndUniValue other) {
    int uniValueComparison = Double.compare(uniValue, other.uniValue);
    if (uniValueComparison != 0) {
      return uniValueComparison;
    }
    return Long.compare(rowNum, other.rowNum);
  }
}
