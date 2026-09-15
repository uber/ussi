/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.comparator.sequencedistance;

/**
 * The number of insertions, deletions, and substitutions of single terms needed to turn one
 * sequence into the other. A substitution can move a term out of one multiset and another
 * into the other, shifting the L1 distance by two.
 */
final class LevenshteinDistance extends SequenceDistance {

  LevenshteinDistance() {
    super(/* l1BoundFactor */ 2.0, /* allowsSubstitution */ true, /* allowsTransposition */ false);
  }
}
