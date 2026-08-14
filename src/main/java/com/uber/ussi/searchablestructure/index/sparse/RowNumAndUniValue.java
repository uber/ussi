/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.sparse;

/** Build-time inverted-list entry ordered by unilateral value and then row number. */
final class RowNumAndUniValue implements Comparable<RowNumAndUniValue> {
  private final long rowNum;
  private final double uniValue;

  RowNumAndUniValue(long rowNum, double uniValue) {
    this.rowNum = rowNum;
    this.uniValue = uniValue;
  }

  long getRowNum() {
    return rowNum;
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
