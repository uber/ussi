/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.entity.termsandvalues;

/**
 * The format a {@link LongTermsAndValues} holds a record in. A comparator declares which formats
 * it reads and a searchable structure declares which one it stores, so the two are paired by
 * agreeing on a type. Sparse and dense name how a record's coordinates are addressed, by its own
 * terms or by position, not how many are populated. Both are order-agnostic, and a sequence is
 * the only type whose term order carries meaning.
 */
public enum RecordType {
  /** No terms, and the values are a vector of a fixed dimension. */
  DENSE("dense"),

  /** Terms in ascending order without repeats, and one value per term. */
  SPARSE("sparse"),

  /** Terms in the order they arrived, repeats included, and no values. */
  SEQUENCE("sequence");

  private final String displayName;

  RecordType(String displayName) {
    this.displayName = displayName;
  }

  /**
   * Returns the name a message names this record type by. A config never names one; see IndexType.
   */
  public String getDisplayName() {
    return displayName;
  }
}
