/* AUTHOR: Ahmed Metwally (ametwally@uber.com) */
package com.uber.ussi.comparator.sequencedistance;

/**
 * The number of insertions and deletions of single elements needed to turn one sequence into the
 * other, the edit distance complementing the longest common subsequence (LCS).
 *
 * <p>It complements the LCS in that the two determine each other: every element outside the longest
 * common subsequence has to be deleted from one sequence or inserted into the other, so this
 * distance is {@code length1 + length2 - 2 * lcsLength}.
 *
 * <p>Without substitution, rewriting an element costs a deletion and an insertion, so this is never
 * below the Levenshtein distance. In exchange each edit moves exactly one element into or out of
 * one multiset, which halves the L1 bound and makes candidate generation more selective.
 */
final class LcsDistance extends SequenceDistance {

  LcsDistance() {
    super(/* l1BoundFactor */ 1.0, /* allowsSubstitution */ false, /* allowsTransposition */ false);
  }
}
