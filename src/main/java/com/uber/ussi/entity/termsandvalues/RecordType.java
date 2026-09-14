/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.entity.termsandvalues;

/**
 * How a {@link LongTermsAndValues} lays its data out. No type is the norm that the others are
 * exceptions to: a comparator declares which ones it can read, and a searchable structure declares
 * which one it stores, so the two are paired by agreeing on a type rather than by name.
 *
 * <p>A type names a layout and nothing else. The order-agnostic types are the ones whose similarity
 * does not depend on the order their elements arrived in, which is why their terms may be kept
 * sorted; sparse and dense name how a record's coordinates are addressed, by its own terms or by
 * position, rather than how many of them are populated, which nothing here validates. How a record
 * is interpreted once it is read, as a vector or a set or a multiset, belongs to the comparator,
 * and how selective its terms are belongs to the index structure.
 */
public enum RecordType {
  /** No terms, and the values are a vector of a fixed dimension. */
  ORDER_AGNOSTIC_DENSE,

  /** Terms in ascending order without repeats, and one value per term. */
  ORDER_AGNOSTIC_SPARSE,

  /** Terms in the order the elements arrived, repeats included, and no values. */
  SEQUENCE
}
