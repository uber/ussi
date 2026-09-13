/* AUTHOR: Shijie Lu (shijie@uber.com), Shalini Kedlaya (skedlaya@uber.com), Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.searchablestructure.index.inverted;

/** A key and its contribution to the comparator-specific unilateral value. */
public final class KeyAndUniTransformedValue {
  private final long key;
  private final double uniTransformedValue;

  public KeyAndUniTransformedValue(long key, double uniTransformedValue) {
    this.key = key;
    this.uniTransformedValue = uniTransformedValue;
  }

  long getKey() {
    return key;
  }

  double getUniTransformedValue() {
    return uniTransformedValue;
  }
}
