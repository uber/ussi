/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.sparse;

/** A sparse key and its contribution to the comparator-specific unilateral value. */
final class SparseKeyAndUniTransformedValue {
  private final long sparseKey;
  private final double uniTransformedValue;

  SparseKeyAndUniTransformedValue(long sparseKey, double uniTransformedValue) {
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
