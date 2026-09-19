/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.inverted;

/**
 * Query key data ordered for low-cost prefix candidate generation. Shared by the inverted indexes
 * and the inverted term cache.
 *
 * <p>The prefix is chosen per query, cheapest inverted list first, and ends once the partial
 * unilateral value of the visited keys exceeds the most a qualifying candidate may leave unmatched.
 * It is not a prefix under a global term order, so a query and the rows it is compared against need
 * not agree on one and a key's cost can change as the index does.
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
