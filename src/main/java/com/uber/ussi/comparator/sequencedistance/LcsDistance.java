/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.comparator.sequencedistance;

/**
 * The number of insertions and deletions of single terms needed to turn one sequence into the
 * other, complementing the longest common subsequence (LCS): every term outside the LCS has
 * to be deleted from one sequence or inserted into the other, so this distance is
 * {@code length1 + length2 - 2 * lcsLength}.
 *
 * <p>Without substitution, rewriting a term costs a deletion and an insertion, so this is
 * never below the Levenshtein distance; in exchange each edit moves exactly one term into or
 * out of one multiset, which halves the L1 bound.
 */
final class LcsDistance extends SequenceDistance {

  LcsDistance() {
    super(/* l1BoundFactor */ 1.0, /* allowsSubstitution */ false, /* allowsTransposition */ false);
  }
}
