/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.comparator.sequencedistance;

/**
 * Levenshtein distance that also counts transposing two adjacent elements as a single edit, so a
 * swap costs one rather than two.
 *
 * <p>A transposition reorders elements without changing either multiset, so it is substitution that
 * still sets the L1 bound.
 */
final class DamerauLevenshteinDistance extends SequenceDistance {

  DamerauLevenshteinDistance() {
    super(/* l1BoundFactor */ 2.0, /* allowsSubstitution */ true, /* allowsTransposition */ true);
  }
}
