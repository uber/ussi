/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.inverted;

/**
 * Query key data ordered for low-cost unordered-prefix candidate generation. Shared by the
 * inverted indexes and the inverted term cache.
 */
public final class KeyAndPrefixFilteringData implements Comparable<KeyAndPrefixFilteringData> {
  private final long key;
  private final int numRows;
  private final double uniTransformedValue;

  public KeyAndPrefixFilteringData(long key, int numRows, double uniTransformedValue) {
    this.key = key;
    this.numRows = numRows;
    this.uniTransformedValue = uniTransformedValue;
  }

  public long getKey() {
    return key;
  }

  public int getNumRows() {
    return numRows;
  }

  public double getUniTransformedValue() {
    return uniTransformedValue;
  }

  @Override
  public int compareTo(KeyAndPrefixFilteringData other) {
    int numRowsComparison = Integer.compare(numRows, other.numRows);
    if (numRowsComparison != 0) {
      return numRowsComparison;
    }
    int uniValueComparison = Double.compare(other.uniTransformedValue, uniTransformedValue);
    if (uniValueComparison != 0) {
      return uniValueComparison;
    }
    return Long.compare(key, other.key);
  }
}
