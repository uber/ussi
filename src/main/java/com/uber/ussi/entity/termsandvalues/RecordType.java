/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.entity.termsandvalues;

import java.util.Locale;

/**
 * How a {@link LongTermsAndValues} lays its data out. A comparator declares which types it reads
 * and a searchable structure declares which one it stores, so the two are paired by agreeing on a
 * type. Sparse and dense name how a record's coordinates are addressed, by its own terms or by
 * position, not how many are populated.
 */
public enum RecordType {
  /** No terms, and the values are a vector of a fixed dimension. */
  ORDER_AGNOSTIC_DENSE,

  /** Terms in ascending order without repeats, and one value per term. */
  ORDER_AGNOSTIC_SPARSE,

  /** Terms in the order the elements arrived, repeats included, and no values. */
  SEQUENCE;

  /** Returns the name a message names this layout by. A config never names one; see IndexType. */
  public String getDisplayName() {
    return name().toLowerCase(Locale.ROOT);
  }
}
