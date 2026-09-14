/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.entity.termsandvalues;

/**
 * How a {@link LongTermsAndValues} lays its data out. A comparator declares which types it reads
 * and a searchable structure declares which one it stores, so the two are paired by agreeing on a
 * type. Sparse and dense name how a record's coordinates are addressed, by its own terms or by
 * position, not how many are populated.
 */
public enum RecordType {
  /** No terms, and the values are a vector of a fixed dimension. */
  ORDER_AGNOSTIC_DENSE("dense"),

  /** Terms in ascending order without repeats, and one value per term. */
  ORDER_AGNOSTIC_SPARSE("sparse"),

  /** Terms in the order the elements arrived, repeats included, and no values. */
  SEQUENCE("sequence");

  private final String displayName;

  RecordType(String displayName) {
    this.displayName = displayName;
  }

  /**
   * Returns the name a message names this record type by. A config never names one; see IndexType.
   * The order-agnostic distinction a constant carries is a maintainer's concern, so a message
   * names only how a record is addressed.
   */
  public String getDisplayName() {
    return displayName;
  }
}
