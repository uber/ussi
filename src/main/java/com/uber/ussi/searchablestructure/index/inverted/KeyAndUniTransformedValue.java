/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.inverted;

/** A sparse key and its contribution to the comparator-specific unilateral value. */
public final class KeyAndUniTransformedValue {
  private final long sparseKey;
  private final double uniTransformedValue;

  public KeyAndUniTransformedValue(long sparseKey, double uniTransformedValue) {
    this.sparseKey = sparseKey;
    this.uniTransformedValue = uniTransformedValue;
  }

  long getSparseKey() {
    return sparseKey;
  }

  double getUniTransformedValue() {
    return uniTransformedValue;
  }
}
