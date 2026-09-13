/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.comparator.sequencedistance;

/**
 * The number of insertions, deletions, and substitutions of single elements needed to turn one
 * sequence into the other.
 *
 * <p>A substitution changes one element without changing either length, so it can move an element
 * out of one multiset and another into the other, shifting the L1 distance by two.
 */
final class LevenshteinDistance extends SequenceDistance {

  LevenshteinDistance() {
    super(/* l1BoundFactor */ 2.0, /* allowsSubstitution */ true, /* allowsTransposition */ false);
  }
}
