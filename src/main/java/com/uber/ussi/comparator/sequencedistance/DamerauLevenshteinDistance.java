/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.comparator.sequencedistance;

/**
 * Levenshtein distance that also counts transposing two adjacent terms as a single edit. A
 * transposition changes neither multiset, so substitution still sets the L1 bound.
 */
final class DamerauLevenshteinDistance extends SequenceDistance {

  DamerauLevenshteinDistance() {
    super(/* l1BoundFactor */ 2.0, /* allowsSubstitution */ true, /* allowsTransposition */ true);
  }
}
