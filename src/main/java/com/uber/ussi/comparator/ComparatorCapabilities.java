/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.comparator;

import java.util.Optional;

/**
 * Reads optional scoring capabilities from a configured {@link Comparator}. Index and merge code
 * asks here rather than branching on concrete comparator types.
 */
public final class ComparatorCapabilities {

  private ComparatorCapabilities() {}

  public static Optional<ConjunctionScored> conjunctionScored(Comparator comparator) {
    if (comparator instanceof ConjunctionScored conjunctionScored) {
      return Optional.of(conjunctionScored);
    }
    return Optional.empty();
  }

  public static Optional<KeyShareBounded> keyShareBounded(Comparator comparator) {
    if (comparator instanceof KeyShareBounded keyShareBounded) {
      return Optional.of(keyShareBounded);
    }
    return Optional.empty();
  }

  public static boolean isDotProductScored(Comparator comparator) {
    return comparator instanceof DotProductScored;
  }

  public static DotProductScored requireDotProductScored(Comparator comparator) {
    if (comparator instanceof DotProductScored dotProductScored) {
      return dotProductScored;
    }
    throw new IllegalArgumentException(
        "The matrix structure requires a comparator that implements DotProductScored.");
  }
}
