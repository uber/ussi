/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.sparse;

/**
 * Query sparse-key data ordered for low-cost unordered-prefix candidate generation. Shared by the
 * sparse index and the sparse cache.
 */
public final class SparseKeyAndPrefixFilteringData
    implements Comparable<SparseKeyAndPrefixFilteringData> {
  private final long sparseKey;
  private final int numRows;
  private final double uniTransformedValue;

  public SparseKeyAndPrefixFilteringData(long sparseKey, int numRows, double uniTransformedValue) {
    this.sparseKey = sparseKey;
    this.numRows = numRows;
    this.uniTransformedValue = uniTransformedValue;
  }

  public long getSparseKey() {
    return sparseKey;
  }

  public int getNumRows() {
    return numRows;
  }

  public double getUniTransformedValue() {
    return uniTransformedValue;
  }

  @Override
  public int compareTo(SparseKeyAndPrefixFilteringData other) {
    int numRowsComparison = Integer.compare(numRows, other.numRows);
    if (numRowsComparison != 0) {
      return numRowsComparison;
    }
    int uniValueComparison = Double.compare(other.uniTransformedValue, uniTransformedValue);
    if (uniValueComparison != 0) {
      return uniValueComparison;
    }
    return Long.compare(sparseKey, other.sparseKey);
  }
}
