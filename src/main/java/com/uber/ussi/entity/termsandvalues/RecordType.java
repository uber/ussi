/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.entity.termsandvalues;

/**
 * How a {@link LongTermsAndValues} lays its data out. No type is the norm that the others are
 * exceptions to: a comparator declares which ones it can read, and a searchable structure declares
 * which one it stores, so the two are paired by agreeing on a type rather than by name.
 */
public enum RecordType {
  /** No terms, and the values are a vector of a fixed dimension. */
  DENSE,

  /** Terms in the order the elements arrived, repeats included, and no values. */
  SEQUENCE,

  /** Terms in ascending order without repeats, and one value per term. */
  SPARSE
}
